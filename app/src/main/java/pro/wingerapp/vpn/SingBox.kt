package pro.wingerapp.vpn

import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Knobs that only the service knows about: DNS choice, the "без туннеля" list,
 * MTU and whether the core writes its own log file.
 */
data class TunnelOptions(
    val dnsServer: String? = null,
    val directDomains: List<String> = emptyList(),
    /**
     * Насколько глубоко брать маршрутизацию и DNS из подписки:
     * [RoutingPlan.OFF] — только наши настройки, [RoutingPlan.RULES] — правила,
     * [RoutingPlan.RULES_AND_DNS] — правила и DNS-серверы.
     */
    val subRouting: Int = RoutingPlan.OFF,
    val useMux: Boolean = false,
    val mtu: Int = 1500,
    val coreLogFile: String? = null,
    /**
     * Файл кэша ядра (там же живут скачанные rule-set). null — без кэша.
     */
    val cacheFile: String? = null,
    /**
     * Drop every optional XHTTP knob. The core rejects the whole config with
     * "unknown field" on a single unrecognised key, so the service retries with
     * this flag after a decode error instead of leaving the user without a VPN.
     */
    val minimalTransport: Boolean = false,
    /**
     * Concrete XHTTP keys the core refused ("cannot unmarshal … / unknown field …").
     * The service reads the field name out of the decode error and retries with
     * just that key dropped — otherwise one bad knob costs every extra field,
     * including the `uplink_data_placement` that keeps uploads out of the body.
     */
    val dropXhttpFields: Set<String> = emptySet(),
    /**
     * Порт локальной прослойки LTE4. Ноль — прослойка выключена. Для серверов
     * `*.apigw.yandexcloud.net` ядро ходит не напрямую, а в `127.0.0.1:shimPort`,
     * а TLS и WebSocket к шлюзу поднимает сама прослойка.
     */
    val shimPort: Int = 0,
    /**
     * Allow IPv6 literals for the proxy address. Off by default: a v6 dial that
     * never answers is indistinguishable from "no internet" in the UI.
     */
    val ipv6Proxy: Boolean = false,
)

/**
 * Turn the selected Xray outbound from the subscription into a working sing-box
 * config.
 *
 * Fails closed on purpose: a subscription without a VLESS outbound throws instead
 * of bringing up a direct tunnel that would silently send everything in the clear
 * while the UI claims the VPN is on.
 */
fun toSingBox(xrayJson: String, opts: TunnelOptions = TunnelOptions()): String {
    val xray = JSONObject(xrayJson)
    val outbounds = xray.optJSONArray("outbounds") ?: JSONArray()
    val proxy = firstVless(outbounds)
        ?: error("в подписке нет VLESS-сервера — туннель не поднят")
    // Один узел — один outbound. Деление потоков по диапазонам порта источника
    // убрано: оно делит соединения, а не байты, поэтому одиночный поток (видео)
    // всё равно упирается в лимит одного соединения к краю CDN.
    val proxyOutbound = convertVless(
        proxy, opts.useMux, opts.minimalTransport, opts.dropXhttpFields, opts.ipv6Proxy, opts.shimPort,
        tag = "proxy",
    )
    val sb = JSONObject()
    sb.put("log", coreLog(opts.coreLogFile))
    // Правила и DNS из подписки — если пользователь их включил и они там есть.
    val plan = if (opts.subRouting != RoutingPlan.OFF) RoutingPlan.build(xray) else null
    if (plan != null && plan.skipped.isNotEmpty()) {
        VpnLog.add("роутинг подписки: не перенесено ${plan.skipped.size} (${plan.skipped.take(6).joinToString(", ")})")
    }
    val subDns = if (opts.subRouting == RoutingPlan.RULES_AND_DNS) plan?.dns else null
    sb.put("dns", subDns ?: dnsSection(opts.dnsServer))
    sb.put("inbounds", JSONArray().put(tunInbound(opts.mtu)))
    val outboundsJson = JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct"))
    outboundsJson.put(proxyOutbound)
    sb.put("outbounds", outboundsJson)
    // Кэш ядра: сюда sing-box кладёт скачанные rule-set (geosite/geoip), иначе
    // они качались бы при каждом подключении.
    if (opts.cacheFile != null) {
        sb.put(
            "experimental",
            JSONObject().put(
                "cache_file",
                JSONObject().put("enabled", true).put("path", opts.cacheFile),
            ),
        )
    }
    sb.put(
        "route",
        routeSection(opts.directDomains, proxyOutbound.optString("server"), plan?.rules, plan?.ruleSets),
    )
    return sb.toString()
}


/**
 * A dial by name needs a resolver, otherwise every connection fails with
 * "missing route.default_domain_resolver" while the tun looks healthy. It is
 * only emitted when the proxy endpoint is still a name: an IP literal must not
 * carry extra fields a future core could reject.
 */
private fun routeSection(
    directDomains: List<String>,
    proxyServer: String,
    subRules: JSONArray? = null,
    ruleSets: JSONArray? = null,
): JSONObject {
    val route = JSONObject()
        .put("auto_detect_interface", true)
        .put("rules", routeRules(directDomains, subRules))
    if (ruleSets != null) route.put("rule_set", ruleSets)
    if (!isIpLiteral(proxyServer)) route.put("default_domain_resolver", "bootstrap")
    return route
}

private fun isIpLiteral(value: String): Boolean = value.matches(IPV4) || value.contains(':')

/** Short config summary for the log. Omits uuid, keys, short id and passwords. */
fun summarizeSingBox(sbConfig: String): String = try {
    val o = JSONObject(sbConfig)
    val inbounds = o.optJSONArray("inbounds") ?: JSONArray()
    var tunLine = "tun=-"
    val inDesc = ArrayList<String>(inbounds.length())
    for (i in 0 until inbounds.length()) {
        val ib = inbounds.optJSONObject(i) ?: continue
        val type = ib.optString("type", "?")
        val addr = ib.opt("address")?.toString()?.take(80).orEmpty()
        if (type == "tun") {
            tunLine = "tun=$addr stack=${ib.optString("stack").ifBlank { "default" }} " +
                "mtu=${ib.optInt("mtu", 0)} autoRoute=${ib.optBoolean("auto_route")}"
        }
        inDesc += if (addr.isBlank()) type else "$type $addr"
    }
    val outs = o.optJSONArray("outbounds") ?: JSONArray()
    val outDesc = ArrayList<String>(outs.length())
    var proxyLine = "proxy=-"
    for (i in 0 until outs.length()) {
        val ob = outs.optJSONObject(i) ?: continue
        val type = ob.optString("type", "?")
        val tag = ob.optString("tag")
        outDesc += "$type/$tag"
        if (tag == "proxy" || type == "vless") {
            val host = ob.optString("server")
            val port = ob.optInt("server_port", 0)
            val transport = ob.optJSONObject("transport")?.optString("type").orEmpty().ifBlank { "tcp" }
            val tls = ob.optJSONObject("tls")
            val tlsOn = tls?.optBoolean("enabled") == true
            val reality = tls?.optJSONObject("reality")?.optBoolean("enabled") == true
            val flow = ob.optString("flow").ifBlank { "-" }
            val mux = ob.optJSONObject("multiplex")?.optBoolean("enabled") == true
            proxyLine = "proxy=$host:$port transport=$transport tls=$tlsOn reality=$reality " +
                "flow=$flow mux=$mux"
        }
    }
    val servers = o.optJSONObject("dns")?.optJSONArray("servers")
    val serverCount = servers?.length() ?: 0
    val detour = servers?.optJSONObject(serverCount - 1)?.optString("detour").orEmpty().ifBlank { "-" }
    val routeObj = o.optJSONObject("route")
    val resolver = routeObj?.optString("default_domain_resolver").orEmpty().ifBlank { "-" }
    val rules = routeObj?.optJSONArray("rules")?.length() ?: 0
    "cfg $tunLine in=[${inDesc.joinToString(",")}] out=[${outDesc.joinToString(",")}] " +
        "$proxyLine dns=$serverCount dnsDetour=$detour resolver=$resolver rules=$rules"
} catch (e: Throwable) {
    "cfg summary fail: ${e.javaClass.simpleName}: ${e.message}"
}

/**
 * The core writes its own log only when the service passed a path: debug builds
 * always do, release builds do it after the user flips the diagnostics switch.
 */
private fun coreLog(path: String?): JSONObject =
    if (path.isNullOrBlank()) JSONObject().put("disabled", true)
    else JSONObject().put("level", "debug").put("output", path).put("timestamp", true)

private fun firstVless(obs: JSONArray): JSONObject? {
    for (i in 0 until obs.length()) {
        val o = obs.optJSONObject(i) ?: continue
        if (o.optString("protocol").equals("vless", true)) return o
    }
    return null
}

/**
 * Bypass entries come from the UI as bare hosts, but older versions stored
 * "domain:bank.ru" and users may paste URLs. Normalise both.
 */
internal fun routeRules(
    raw: List<String>,
    subRules: JSONArray? = null,
): JSONArray {
    val hosts = raw.mapNotNull { line ->
        val host = line.trim().lowercase()
            .removePrefix("domain:")
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore(':')
            .trim()
        host.takeIf { it.isNotBlank() && it != "domain" }
    }.distinct()
    val rules = JSONArray()
    // Список пользователя — первым: он явный, и его выбор важнее подписки.
    if (hosts.isNotEmpty()) {
        rules.put(JSONObject()
            .put("domain_suffix", JSONArray(hosts))
            .put("action", "route")
            .put("outbound", "direct"))
    }
    rules.put(JSONObject().put("action", "sniff"))
    // Перехват DNS нужен один раз и до правил маршрутизации.
    rules.put(JSONObject().put("action", "hijack-dns").put("protocol", "dns"))
    // Правила из подписки — в том же порядке, как в Xray. Готовый перехват DNS
    // из них пропускаем: выше уже есть.
    if (subRules != null) {
        for (i in 0 until subRules.length()) {
            val rule = subRules.optJSONObject(i) ?: continue
            if (rule.optString("action") == "hijack-dns") continue
            rules.put(rule)
        }
    }
    // Всё остальное — в единственный прокси-канал.
    rules.put(JSONObject().put("action", "route").put("outbound", "proxy"))
    return rules
}

/**
 * Two resolvers:
 *  - `bootstrap` resolves the proxy endpoint itself. It must stay outside the
 *    tunnel: the tunnel does not exist yet, and sing-box fails with
 *    "missing route.default_domain_resolver" when the only resolver is behind
 *    the proxy.
 *  - `remote` handles user queries inside the tunnel.
 */
private fun dnsSection(server: String?): JSONObject {
    val upstream = normalizeDns(server)
    return JSONObject()
        .put("servers", JSONArray()
            .put(JSONObject()
                .put("type", "udp")
                .put("tag", "bootstrap")
                .put("server", "1.1.1.1"))
            .put(JSONObject()
                .put("type", "udp")
                .put("tag", "remote")
                .put("server", upstream)
                .put("detour", "proxy")))
        .put("final", "remote")
}

private fun normalizeDns(server: String?): String {
    val value = server?.trim().orEmpty().removePrefix("udp://")
    return when {
        value.matches(IPV4) -> value
        value.contains(':') && !value.contains('/') && !value.contains("://") -> value
        else -> "1.1.1.1"
    }
}

/**
 * auto_route stays off: with it on, OpenTun's callback into TunOptions.GetInet4Address
 * nil-panics in libbox 1.14.4-lx.33. Routes are installed by VpnService.Builder.
 *
 * IPv6 is not configured here on purpose. The service routes ::/0 into the tun
 * without an IPv6 address, so v6 packets are dropped instead of leaking past
 * the tunnel.
 */
private fun tunInbound(mtu: Int): JSONObject = JSONObject()
    .put("type", "tun")
    .put("tag", "tun-in")
    .put("address", JSONArray().put("172.19.0.1/30"))
    .put("mtu", mtu)
    .put("auto_route", false)
    .put("strict_route", false)
    .put("stack", "gvisor")

private fun convertVless(
    xray: JSONObject,
    useMux: Boolean,
    minimalTransport: Boolean,
    dropXhttpFields: Set<String> = emptySet(),
    ipv6Proxy: Boolean = false,
    shimPort: Int = 0,
    tag: String = "proxy",
): JSONObject = convertVlessBody(xray, useMux, minimalTransport, dropXhttpFields, ipv6Proxy, shimPort, tag)

private fun convertVlessBody(
    xray: JSONObject,
    useMux: Boolean,
    minimalTransport: Boolean,
    dropXhttpFields: Set<String>,
    ipv6Proxy: Boolean,
    shimPort: Int,
    tag: String,
): JSONObject {
    val settings = xray.optJSONObject("settings") ?: JSONObject()
    val vnext = settings.optJSONArray("vnext")?.optJSONObject(0) ?: JSONObject()
    val users = vnext.optJSONArray("users")?.optJSONObject(0) ?: JSONObject()
    val stream = xray.optJSONObject("streamSettings") ?: JSONObject()
    val network = stream.optString("network", "tcp").lowercase()
    val address = vnext.optString("address")
    // Шлюз Яндекса ломает VLESS поверх WS (перемешивает сообщения), поэтому для
    // него ядро ходит в локальную прослойку, а TLS и WS к шлюзу делает она.
    // Только ровно тот случай, что описан в подписке LTE 4: домен шлюза и
    // path=/ws. Любой другой path — обычный VLESS-WS, как у всех остальных.
    val wsPath = stream.optJSONObject("wsSettings")?.optString("path").orEmpty()
    val viaShim = shimPort > 0 && network == "ws" &&
        Lte4.isLte4Gateway(address) && Lte4.isGatewayPath(wsPath)

    val sb = JSONObject()
    sb.put("type", "vless")
    sb.put("tag", tag)
    if (viaShim) {
        sb.put("server", "127.0.0.1")
        sb.put("server_port", shimPort)
    } else {
        sb.put("server", resolveServer(address, ipv6Proxy))
        sb.put("server_port", vnext.optInt("port", 443))
    }
    sb.put("uuid", users.optString("id"))
    val flow = users.optString("flow", "")
    // Vision flow only exists for raw TCP; ws/grpc/xhttp configs that carry it
    // anyway are rejected by the core, and mux cannot be combined with it.
    val flowUsable = flow.isNotBlank() && network in setOf("tcp", "raw", "")
    if (flowUsable) sb.put("flow", flow)
    users.optString("packetEncoding").takeIf { it.isNotBlank() }?.let { sb.put("packet_encoding", it) }
    // Xray по умолчанию гоняет UDP как xudp; если в подписке поле не указано,
    // без него sing-box не умеет UDP через прокси (DNS внутри туннеля, звонки).
    if (sb.optString("packet_encoding").isBlank()) sb.put("packet_encoding", "xudp")
    if (viaShim) {
        // До прослойки — обычный ws без TLS: она сама поднимает WSS к шлюзу.
        // Host = домен шлюза: по нему прослойка понимает, куда подключаться.
        val ws = stream.optJSONObject("wsSettings") ?: JSONObject()
        val t = JSONObject().put("type", "ws")
        ws.optString("path").takeIf { it.isNotBlank() }?.let { t.put("path", it) }
        t.put("headers", JSONObject().put("Host", address))
        sb.put("transport", t)
        return sb
    }
    // LTE 5: Selectel CDN буферизует длинный GET, поэтому xhttp там висит. Если
    // подписка помечает узел `extra.postChunks`, ядро ходит в прослойку, а она
    // обменивается с CDN короткими POST в обе стороны.
    val xhttpSettings = stream.optJSONObject("xhttpSettings")
    val xhttpExtra = xhttpSettings?.optJSONObject("extra")
    val viaPostChunks = shimPort > 0 &&
        network in setOf("xhttp", "splithttp") &&
        PostChunkParams.isPostChunksHost(address) &&
        xhttpExtra?.optBoolean("postChunks", false) == true
    if (viaPostChunks) {
        val t = JSONObject().put("type", "ws")
        xhttpSettings?.optString("path")?.takeIf { it.isNotBlank() }?.let { t.put("path", it) }
        val headers = JSONObject()
            .put("Host", address)
            .put("X-Post-Chunks", "1")
        xhttpExtra?.optInt("postChunkBytes", 0)?.takeIf { it > 0 }
            ?.let { headers.put("X-Post-Chunk-Bytes", it.toString()) }
        xhttpExtra?.optInt("postPauseMs", 0)?.takeIf { it > 0 }
            ?.let { headers.put("X-Post-Pause-Ms", it.toString()) }
        xhttpExtra?.optInt("postParallel", 0)?.takeIf { it > 0 }
            ?.let { headers.put("X-Post-Parallel", it.toString()) }
        if (xhttpExtra?.optBoolean("postPadding", true) == false) {
            headers.put("X-Padding", "0")
        }
        t.put("headers", headers)
        sb.put("transport", t)
        if (shimPort > 0) {
            sb.put("server", "127.0.0.1")
            sb.put("server_port", shimPort)
        }
        return sb
    }
    // For WebSocket the Host header must be the domain (SNI), not the resolved IP:
    // gateways route by Host, and our `server` field is already an IP.
    val hostForWs = stream.optJSONObject("tlsSettings")?.optString("serverName").orEmpty()
        .ifBlank { address }
    transportFor(stream, network, minimalTransport, dropXhttpFields, hostForWs)?.let { sb.put("transport", it) }
    tlsFor(stream, address, network)?.let { sb.put("tls", it) }
    if (useMux && !flowUsable) {
        sb.put("multiplex", JSONObject().put("enabled", true).put("protocol", "smux"))
    }
    return sb
}

/**
 * The core cannot dial the proxy by name on its own (see
 * route.default_domain_resolver above), so resolve it here and keep the domain
 * only for TLS/Reality `server_name`. A failed or slow lookup is not fatal: the
 * name is passed through and the core's bootstrap resolver takes over.
 */
private fun resolveServer(host: String, allowIpv6: Boolean = false): String {
    val raw = host.trim()
    if (raw.isBlank()) return raw
    if (raw.matches(IPV4)) return raw
    // An IPv6 literal in the subscription cannot be turned into an A record;
    // it is passed through as-is (and simply will not dial when the network has
    // no v6 route). Hostnames are the case this setting really controls.
    if (raw.contains(':') && !raw.contains("://")) return raw
    val answer = ArrayBlockingQueue<String>(1)
    thread(isDaemon = true, name = "proxy-lookup") {
        val addresses = runCatching { InetAddress.getAllByName(raw).toList() }.getOrNull()
        val usable = addresses?.mapNotNull { it.hostAddress }?.filter { plausibleAddress(it) }.orEmpty()
        val ipv4 = usable.firstOrNull { it.matches(IPV4) }
        // IPv6 off (the default): take an A record; if the host has none, hand
        // the NAME to the core so route.default_domain_resolver decides, instead
        // of pinning an address family that the network may not route.
        val pick = ipv4 ?: if (allowIpv6) usable.firstOrNull() else null
        // Nothing plausible (hijacked or blocked DNS) -> hand the name over to
        // the core and let route.default_domain_resolver deal with it.
        runCatching { answer.offer(pick ?: raw, LOOKUP_TIMEOUT_SEC, TimeUnit.SECONDS) }
    }
    val picked = runCatching { answer.poll(LOOKUP_TIMEOUT_SEC, TimeUnit.SECONDS) }.getOrNull()
    // Этот резолв идёт из процесса приложения, а он исключён из туннеля
    // (addDisallowedApplication), то есть DNS уходит мимо VPN — соединение к
    // серверу не зацикливается само на себя. В лог пишем без адреса.
    VpnLog.add(
        "прокси $raw: " + when {
            picked == null || picked == raw -> "A-записи нет, имя отдано ядру"
            picked.contains(':') -> "взят IPv6-адрес"
            else -> "взят IPv4-адрес (резолв вне туннеля)"
        }
    )
    return picked?.takeIf { it.isNotBlank() } ?: raw
}

/**
 * Bogus answers from a DNS that hijacks everything: 0.0.0.0/8, loopback,
 * 224.0.0.0/3 (multicast + reserved 240/4) and the 198.18/15 test range. Such an
 * IP would make the tunnel dial nothing at all, which looks exactly like "VPN is
 * on, internet is gone".
 */
internal fun plausibleAddress(value: String): Boolean {
    if (value.contains(':')) {
        val v6 = value.lowercase()
        return !(v6 == "::" || v6 == "::1" || v6.startsWith("fe80"))
    }
    val parts = value.split('.').map { it.toIntOrNull() ?: return false }
    if (parts.size != 4 || parts.any { it !in 0..255 }) return false
    val a = parts[0]
    val b = parts[1]
    return when {
        a == 0 || a == 127 || a >= 224 -> false
        a == 169 && b == 254 -> false
        a == 198 && (b == 18 || b == 19) -> false
        else -> true
    }
}

private const val LOOKUP_TIMEOUT_SEC = 3L
private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

private fun tlsFor(stream: JSONObject, address: String, network: String = ""): JSONObject? {
    return when (stream.optString("security", "").lowercase()) {
        "reality" -> {
            val reality = stream.optJSONObject("realitySettings") ?: JSONObject()
            JSONObject()
                .put("enabled", true)
                .put("server_name", reality.optString("serverName"))
                .put("utls", JSONObject()
                    .put("enabled", true)
                    .put("fingerprint", reality.optString("fingerprint").ifBlank { "chrome" }))
                .put("reality", JSONObject()
                    .put("enabled", true)
                    .put("public_key", reality.optString("publicKey"))
                    .put("short_id", reality.optString("shortId")))
        }
        "tls" -> {
            val tlsSettings = stream.optJSONObject("tlsSettings") ?: JSONObject()
            val tls = JSONObject()
                .put("enabled", true)
                .put("server_name", tlsSettings.optString("serverName").ifBlank { address })
                .put("utls", JSONObject()
                    .put("enabled", true)
                    .put("fingerprint", tlsSettings.optString("fingerprint").ifBlank { "chrome" }))
            val alpn = tlsSettings.optJSONArray("alpn")
            when {
                // ALPN из подписки уважаем всегда: если там h2 — значит так и надо.
                alpn != null && alpn.length() > 0 -> tls.put("alpn", alpn)
                // WebSocket и httpupgrade апгрейдятся ТОЛЬКО по HTTP/1.1. Если в
                // ссылке ALPN не указан, sing-box по умолчанию предложит h2, и
                // Yandex API Gateway (как и любой nginx с h2) откажет в upgrade:
                // сервер выглядит «мёртвым», хотя TLS проходит. Поэтому для ws
                // подставляем http/1.1 сами.
                network in setOf("ws", "httpupgrade", "http", "h2") ->
                    tls.put("alpn", JSONArray().put("http/1.1"))
            }
            // Пиннинг сертификата (Xray: pinnedPeerCertSha256 / verifyPeerCertByName)
            // ядро не умеет. На практике это признак неполной цепочки сертов у края
            // CDN, и проверка по системным корням не сойдётся — сервер выглядел бы
            // мёртвым. Отключаем проверку только для этого сервера: шифрование
            // остаётся, имя по-прежнему уходит в SNI.
            val pinned = tlsSettings.optString("pinnedPeerCertSha256").isNotBlank() ||
                tlsSettings.optString("verifyPeerCertByName").isNotBlank()
            if (pinned) {
                tls.put("insecure", true)
                VpnLog.add("TLS: пиннинг серта не поддержан ядром — проверка отключена для этого сервера")
            }
            tls
        }
        else -> null
    }
}

/** Xray network names → sing-box transports. Anything unknown is left out. */
private fun transportFor(
    stream: JSONObject,
    network: String,
    minimal: Boolean = false,
    drop: Set<String> = emptySet(),
    hostFallback: String = "",
): JSONObject? = when (network) {
    "xhttp", "splithttp" -> xhttpTransport(stream, minimal, drop)
    "ws" -> {
        val ws = stream.optJSONObject("wsSettings") ?: JSONObject()
        val t = JSONObject().put("type", "ws")
        ws.optString("path").takeIf { it.isNotBlank() }?.let { t.put("path", it) }
        // Host обязателен: без него sing-box подставит адрес сервера, а он у нас
        // уже заменён на IP (resolveServer), и шлюз ответит 403 на upgrade.
        val host = ws.optJSONObject("headers")?.optString("Host")?.takeIf { it.isNotBlank() }
            ?: hostFallback.takeIf { it.isNotBlank() }
        host?.let { t.put("headers", JSONObject().put("Host", it)) }
        if (!minimal) {
            ws.optInt("maxEarlyData", 0).takeIf { it > 0 }?.let { t.put("max_early_data", it) }
            ws.optString("earlyDataHeaderName").takeIf { it.isNotBlank() }
                ?.let { t.put("early_data_header_name", it) }
        }
        t
    }
    "grpc" -> {
        val grpc = stream.optJSONObject("grpcSettings") ?: JSONObject()
        val t = JSONObject().put("type", "grpc")
        grpc.optString("serviceName").takeIf { it.isNotBlank() }?.let { t.put("service_name", it) }
        t
    }
    "httpupgrade" -> {
        val h = stream.optJSONObject("httpupgradeSettings") ?: JSONObject()
        val t = JSONObject().put("type", "httpupgrade")
        h.optString("path").takeIf { it.isNotBlank() }?.let { t.put("path", it) }
        h.optString("host").takeIf { it.isNotBlank() }?.let { t.put("host", it) }
        t
    }
    "h2", "http" -> {
        val h = stream.optJSONObject("httpSettings") ?: JSONObject()
        val t = JSONObject().put("type", "http")
        h.optString("path").takeIf { it.isNotBlank() }?.let { t.put("path", it) }
        val hosts = h.optJSONArray("host")
        if (hosts != null && hosts.length() > 0) t.put("host", JSONArray().put(hosts.optString(0)))
        t
    }
    else -> null
}

/**
 * sing-box-lx XHTTP. Only the keys this libbox build actually understands:
 * the decoder rejects the whole config with "unknown field" on anything else,
 * and the tunnel then never starts.
 *
 * Dropped for good: `session_id_*` (no such JSON tags in the binary) and `xmux`
 * (exists only as an opaque struct whose field names are not pinned here).
 *
 * Passed THROUGH since the 413 investigation: `uplink_data_placement`,
 * `uplink_data_key` and `sc_*`. All of them exist as JSON tags in libbox
 * 1.14.4-lx.33 — `UplinkDataPlacement json:"uplink_data_placement,omitempty"`,
 * `sc_max_each_post_bytes`, `sc_max_buffered_posts`,
 * `sc_min_posts_interval_ms`. Dropping `uplink_data_placement` was exactly why
 * two of the three LTE nodes answered 413: without it sing-box puts the uplink
 * payload into request BODIES, and the nginx in front of those nodes rejects
 * every request that carries a body (measured: 413 even on 8 bytes). Xray
 * clients honour the field by putting the payload into headers, which is why
 * the same subscription worked in Incy.
 */
private fun xhttpTransport(stream: JSONObject, minimal: Boolean, drop: Set<String> = emptySet()): JSONObject {
    val xhttp = stream.optJSONObject("xhttpSettings") ?: JSONObject()
    val transport = JSONObject().put("type", "xhttp")
    xhttp.optString("path").takeIf { it.isNotBlank() }?.let { transport.put("path", it) }
    xhttp.optString("mode").takeIf { it.isNotBlank() }?.let { transport.put("mode", it) }
    xhttp.optString("host").takeIf { it.isNotBlank() }?.let { transport.put("host", it) }
    if (minimal) return transport
    val extra = xhttp.optJSONObject("extra") ?: return transport
    fun str(key: String, target: String) {
        if (target in drop) return
        extra.optString(key).takeIf { it.isNotBlank() }?.let { transport.put(target, it) }
    }
    str("uplinkHTTPMethod", "uplink_http_method")
    // The uplink direction: where the payload travels. `header` keeps it out of
    // the request body, which is what the 413-prone nodes require.
    str("uplinkDataPlacement", "uplink_data_placement")
    str("uplinkDataKey", "uplink_data_key")
    // Post sizing. TYPES MATTER here: this build declares
    // `sc_max_buffered_posts` as int64, so a string aborts the whole config
    // ("cannot unmarshal string into Go struct field V2RayXHTTPOptions.
    // sc_max_buffered_posts of type int64") and the minimal-transport fallback
    // then drops EVERY extra — including the uplink placement that fixes 413.
    // `sc_max_each_post_bytes` is a range string ("500000-1000000") and is
    // accepted as-is; `sc_min_posts_interval_ms` is left out on purpose.
    str("scMaxEachPostBytes", "sc_max_each_post_bytes")
    val buffered = extra.opt("scMaxBufferedPosts")
    val bufferedPosts = when (buffered) {
        is Number -> buffered.toInt()
        is String -> buffered.trim().toIntOrNull()
        else -> null
    }
    if ("sc_max_buffered_posts" !in drop) {
        bufferedPosts?.takeIf { it > 0 }?.let { transport.put("sc_max_buffered_posts", it) }
    }
    str("xPaddingBytes", "x_padding_bytes")
    if (extra.has("xPaddingObfsMode")) transport.put("x_padding_obfs_mode", extra.optBoolean("xPaddingObfsMode"))
    str("xPaddingPlacement", "x_padding_placement")
    str("xPaddingKey", "x_padding_key")
    str("xPaddingHeader", "x_padding_header")
    str("xPaddingMethod", "x_padding_method")
    str("seqPlacement", "seq_placement")
    str("seqKey", "seq_key")
    str("sessionPlacement", "session_placement")
    str("sessionKey", "session_key")
    return transport
}
