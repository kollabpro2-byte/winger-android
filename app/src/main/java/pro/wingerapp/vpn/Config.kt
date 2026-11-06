package pro.wingerapp.vpn

import org.json.JSONArray
import org.json.JSONObject

data class ServerNode(val name: String, val host: String, val port: Int)
data class SubConfig(val raw: String, val servers: List<ServerNode>)

fun readHapp(body: String): SubConfig {
    val text = body.trim()
    val source = when {
        text.startsWith("[") -> text
        text.startsWith("{") -> throw IllegalStateException("подписка пришла одним файлом, а не списком серверов")
        else -> {
            val decoded = runCatching {
                String(android.util.Base64.decode(text, android.util.Base64.DEFAULT))
            }.getOrNull()?.trim().orEmpty()
            if (decoded.startsWith("[")) decoded else throw IllegalStateException("не удалось прочитать список серверов")
        }
    }
    val arr = JSONArray(source)
    val servers = mutableListOf<ServerNode>()
    for (i in 0 until arr.length()) {
        val cfg = arr.optJSONObject(i) ?: continue
        val name = cfg.optString("remarks").ifBlank { "Сервер ${i + 1}" }
        val point = firstEndpoint(cfg)
        servers += ServerNode(name, point?.host.orEmpty(), point?.port ?: 443)
    }
    if (servers.isEmpty()) error("в подписке нет серверов")
    return SubConfig(body, servers)
}

fun xrayConfigFor(raw: String, server: String, ignoreGeo: Boolean = false, dns: String? = null, directDomains: List<String> = emptyList()): String {
    val arr = JSONArray(raw)
    var cfg = arr.optJSONObject(0) ?: error("пустая подписка")
    for (i in 0 until arr.length()) {
        val c = arr.optJSONObject(i) ?: continue
        if (c.optString("remarks") == server) { cfg = c; break }
    }
    cfg.remove("remarks")
    cfg.remove("meta")
    val inbounds = cfg.optJSONArray("inbounds") ?: JSONArray().also { cfg.put("inbounds", it) }
    inbounds.put(JSONObject()
        .put("tag", "tun")
        .put("protocol", "tun")
        .put("settings", JSONObject().put("name", "xray0").put("MTU", 1500))
        .put("sniffing", JSONObject().put("enabled", true).put("routeOnly", true).put("destOverride", JSONArray().put("http").put("tls").put("quic"))))
    val routing = cfg.optJSONObject("routing") ?: JSONObject().also { cfg.put("routing", it) }
    routing.put("domainStrategy", "IPIfNonMatch")
    val rules = routing.optJSONArray("rules") ?: JSONArray().also { routing.put("rules", it) }
    mapKnown(cfg)
    val routed = if (ignoreGeo) withoutGeo(rules) else withRussianDirect(rules)
    routing.put("rules", routed)
    if (ignoreGeo) {
        val target = routing.optJSONArray("balancers")?.optJSONObject(0)?.optString("tag")
        val rule = JSONObject().put("type", "field").put("inboundTag", JSONArray().put("tun"))
        if (!target.isNullOrBlank()) rule.put("balancerTag", target)
        else rule.put("outboundTag", firstVless(cfg)?.optString("tag") ?: "proxy")
        routing.put("rules", JSONArray().put(rule))
        stripGeo(cfg)
    }
    cfg.put("stats", JSONObject())
    val policy = cfg.optJSONObject("policy") ?: JSONObject().also { cfg.put("policy", it) }
    val system = policy.optJSONObject("system") ?: JSONObject()
    system.put("statsOutboundUplink", true)
    system.put("statsOutboundDownlink", true)
    policy.put("system", system)
    if (!dns.isNullOrBlank()) {
        val dnsObj = cfg.optJSONObject("dns") ?: JSONObject().also { cfg.put("dns", it) }
        dnsObj.put("servers", JSONArray().put(dns))
    }
    val extra = JSONArray()
    directDomains.forEach { host ->
        val clean = host.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore("/").substringBefore(":")
        if (clean.isNotBlank()) extra.put("domain:$clean")
    }
    if (extra.length() > 0) {
        ensureDirect(cfg)
        val now = routing.optJSONArray("rules") ?: JSONArray()
        val next = JSONArray().put(JSONObject().put("type", "field").put("outboundTag", "direct").put("domain", extra))
        for (i in 0 until now.length()) next.put(now.get(i))
        routing.put("rules", next)
    }
    return cfg.toString()
}

data class Endpoint(val host: String, val port: Int)

fun tcpEndpoints(raw: String, server: String): List<Endpoint> {
    val cfg = namedConfig(raw, server)
    val obs = cfg.optJSONArray("outbounds") ?: return emptyList()
    val found = mutableListOf<Endpoint>()
    for (i in 0 until obs.length()) {
        val outbound = obs.optJSONObject(i) ?: continue
        val proto = outbound.optString("protocol").lowercase()
        if (proto in setOf("freedom", "blackhole", "dns", "loopback", "")) continue
        // Whole outbound, not only settings: xhttp/splithttp still connect to address:port.
        val before = found.size
        collectEndpoints(outbound, found)
        if (found.size == before) found += looseEndpoints(outbound)
    }
    return found.distinct()
}

fun delayConfigs(raw: String, server: String): List<String> {
    val cfg = namedConfig(raw, server)
    val proxies = vlessOutbounds(cfg).take(4)
    return proxies.map { proxy ->
        val tagged = JSONObject(proxy.toString()).put("tag", "proxy")
        JSONObject()
            .put("outbounds", JSONArray().put(tagged).put(JSONObject().put("tag", "direct").put("protocol", "freedom")))
            .put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1")))
            .put("routing", JSONObject()
                .put("domainStrategy", "AsIs")
                .put("rules", JSONArray().put(JSONObject().put("type", "field").put("network", "tcp,udp").put("outboundTag", "proxy"))))
            .toString()
    }
}

private fun namedConfig(raw: String, server: String): JSONObject {
    val arr = JSONArray(raw)
    var cfg = arr.optJSONObject(0) ?: JSONObject()
    for (i in 0 until arr.length()) {
        val c = arr.optJSONObject(i) ?: continue
        if (c.optString("remarks") == server) return c
    }
    return cfg
}

private fun vlessOutbounds(cfg: JSONObject): List<JSONObject> {
    val obs = cfg.optJSONArray("outbounds") ?: return emptyList()
    val ready = mutableListOf<JSONObject>()
    val rest = mutableListOf<JSONObject>()
    for (i in 0 until obs.length()) {
        val o = obs.optJSONObject(i) ?: continue
        if (!o.optString("protocol").equals("vless", true)) continue
        if (hasAddress(o)) ready += o else rest += o
    }
    return if (ready.isNotEmpty()) ready else rest
}

private fun hasAddress(outbound: JSONObject): Boolean {
    val settings = outbound.optJSONObject("settings") ?: return false
    val vnext = settings.optJSONArray("vnext") ?: return settings.optString("address").isNotBlank()
    for (i in 0 until vnext.length()) {
        if (vnext.optJSONObject(i)?.optString("address").orEmpty().isNotBlank()) return true
    }
    return false
}

private fun jsonPort(node: JSONObject): Int {
    val raw = node.opt("port")
    return when (raw) {
        is Number -> raw.toInt()
        is String -> raw.trim().toIntOrNull() ?: 0
        else -> 0
    }
}

private fun endpointHost(node: JSONObject): String =
    node.optString("address").ifBlank { node.optString("host") }.ifBlank { node.optString("server") }

private fun usableHost(host: String): Boolean =
    host.isNotBlank() && host != "0.0.0.0" && host != "127.0.0.1" && !host.contains("://") && !host.startsWith("geo") && !host.contains(" ")

private fun firstEndpoint(cfg: JSONObject): Endpoint? {
    val obs = cfg.optJSONArray("outbounds") ?: return null
    for (i in 0 until obs.length()) {
        val outbound = obs.optJSONObject(i) ?: continue
        val proto = outbound.optString("protocol").lowercase()
        if (proto in setOf("freedom", "blackhole", "dns", "loopback", "")) continue
        val found = mutableListOf<Endpoint>()
        collectEndpoints(outbound, found)
        if (found.isEmpty()) found += looseEndpoints(outbound)
        if (found.isNotEmpty()) return found.first()
    }
    return null
}

/** Address and port live on different objects (port only on the outbound). */
private fun looseEndpoints(outbound: JSONObject): List<Endpoint> {
    val hosts = mutableListOf<String>()
    val ports = mutableListOf<Int>()
    fun walk(node: Any?) {
        when (node) {
            is JSONObject -> {
                val host = endpointHost(node)
                if (usableHost(host)) hosts += host
                val port = jsonPort(node)
                if (port in 1..65535) ports += port
                val keys = node.keys()
                while (keys.hasNext()) walk(node.opt(keys.next()))
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
        }
    }
    walk(outbound)
    val port = ports.firstOrNull() ?: 443
    return hosts.distinct().map { Endpoint(it, port) }
}

private fun collectEndpoints(node: Any?, out: MutableList<Endpoint>) {
    when (node) {
        is JSONObject -> {
            val host = endpointHost(node)
            val port = jsonPort(node)
            if (usableHost(host) && port in 1..65535) {
                out += Endpoint(host, port)
            }
            val keys = node.keys()
            while (keys.hasNext()) collectEndpoints(node.opt(keys.next()), out)
        }
        is JSONArray -> for (i in 0 until node.length()) collectEndpoints(node.opt(i), out)
    }
}

private fun ensureDirect(cfg: JSONObject) {
    val outbounds = cfg.optJSONArray("outbounds") ?: JSONArray().also { cfg.put("outbounds", it) }
    for (i in 0 until outbounds.length()) {
        if (outbounds.optJSONObject(i)?.optString("tag") == "direct") return
    }
    outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
}

private fun withRussianDirect(rules: JSONArray): JSONArray {
    val domains = JSONObject().put("type", "field").put("outboundTag", "direct").put("domain", JSONArray()
        .put("domain:ozon.ru").put("domain:ozon.com").put("domain:ozon.by").put("domain:ozon.kz")
        .put("domain:wildberries.ru").put("domain:wb.ru").put("geosite:category-ru"))
    val ips = JSONObject().put("type", "field").put("outboundTag", "direct").put("ip", JSONArray().put("geoip:ru"))
    val next = JSONArray().put(domains).put(ips)
    for (i in 0 until rules.length()) next.put(rules.get(i))
    return next
}

private val geoMap = mapOf(
    "geosite:torrent" to "geosite:category-public-tracker",
    "geosite:whitelist" to "geosite:category-ru",
    "geosite:category-geoblock-ru" to "geosite:category-ru",
    "geosite:twitch-ads" to "geosite:category-ads"
)

private fun mapKnown(node: Any?) {
    when (node) {
        is JSONObject -> node.keys().asSequence().toList().forEach { k ->
            val v = node.opt(k)
            if (v is String) node.put(k, swap(v)) else mapKnown(v)
        }
        is JSONArray -> for (i in 0 until node.length()) {
            when (val v = node.opt(i)) {
                is String -> node.put(i, swap(v))
                else -> mapKnown(v)
            }
        }
    }
}

private fun swap(s: String): String {
    var out = s
    geoMap.forEach { (a, b) -> out = out.replace(a, b) }
    return out
}

private fun withoutGeo(rules: JSONArray): JSONArray {
    val clean = JSONArray()
    for (i in 0 until rules.length()) {
        val r = rules.optJSONObject(i) ?: continue
        if (hasGeo(r.toString())) continue
        clean.put(r)
    }
    return clean
}

private fun stripGeo(node: Any?) {
    when (node) {
        is JSONObject -> {
            val keys = node.keys().asSequence().toList()
            for (k in keys) {
                val v = node.opt(k)
                if (v is String && hasGeo(v)) node.remove(k)
                else if (v is JSONArray) {
                    val keep = JSONArray()
                    for (i in 0 until v.length()) {
                        val item = v.opt(i)
                        if (item is String && hasGeo(item)) continue
                        if (item is JSONObject && hasGeo(item.toString())) continue
                        keep.put(item)
                    }
                    node.put(k, keep)
                    stripGeo(keep)
                } else stripGeo(v)
            }
        }
        is JSONArray -> for (i in 0 until node.length()) stripGeo(node.opt(i))
    }
}

private fun hasGeo(text: String) = "geosite:" in text || "geoip:" in text

private fun firstVless(cfg: JSONObject): JSONObject? = vlessOutbounds(cfg).firstOrNull()
