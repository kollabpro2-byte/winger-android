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
    val useMux: Boolean = false,
    val mtu: Int = 1500,
    val coreLogFile: String? = null,
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
    val proxyOutbound = convertVless(proxy, opts.useMux, opts.minimalTransport, opts.dropXhttpFields, opts.ipv6Proxy)
    val sb = JSONObject()
    sb.put("log", coreLog(opts.coreLogFile))
    sb.put("dns", dnsSection(opts.dnsServer))
    sb.put("inbounds", JSONArray().put(tunInbound(opts.mtu)))
    sb.put("outbounds", JSONArray()
        .put(JSONObject().put("type", "direct").put("tag", "direct"))
        .put(proxyOutbound))
    sb.put("route", routeSection(opts.directDomains, proxyOutbound.optString("server")))
    return sb.toString()
}

/**
 * A dial by name needs a resolver, otherwise every connection fails with
 * "missing route.default_domain_resolver" while the tun looks healthy. It is
 * only emitted when the proxy endpoint is still a name: an IP literal must not
 * carry extra fields a future core could reject.
 */
private fun routeSection(directDomains: List<String>, proxyServer: String): JSONObject {
    val route = JSONObject()
        .put("auto_detect_interface", true)
        .put("rules", routeRules(directDomains))
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
internal fun routeRules(raw: List<String>): JSONArray {
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
    if (hosts.isNotEmpty()) {
        rules.put(JSONObject()
            .put("domain_suffix", JSONArray(hosts))
            .put("action", "route")
            .put("outbound", "direct"))
    }
    rules.put(JSONObject().put("action", "sniff"))
    rules.put(JSONObject().put("action", "hijack-dns").put("protocol", "dns"))
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

private fun convertVless(xray: JSONObject, useMux: Boolean, minimalTransport: Boolean, dropXhttpFields: Set<String> = emptySet(), ipv6Proxy: Boolean = false): JSONObject {
    val settings = xray.optJSONObject("settings") ?: JSONObject()
    val vnext = settings.optJSONArray("vnext")?.optJSONObject(0) ?: JSONObject()
    val users = vnext.optJSONArray("users")?.optJSONObject(0) ?: JSONObject()
    val stream = xray.optJSONObject("streamSettings") ?: JSONObject()
    val network = stream.optString("network", "tcp").lowercase()
    val sb = JSONObject()
    sb.put("type", "vless")
    sb.put("tag", "proxy")
    sb.put("server", resolveServer(vnext.optString("address"), ipv6Proxy))
    sb.put("server_port", vnext.optInt("port", 443))
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
    val address = vnext.optString("address")
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
