package pro.wingerapp.vpn

import org.json.JSONArray
import org.json.JSONObject

data class ServerNode(val name: String, val host: String, val port: Int)
data class SubConfig(val raw: String, val servers: List<ServerNode>)

fun readHapp(body: String): SubConfig {
    val arr = JSONArray(body)
    val servers = mutableListOf<ServerNode>()
    for (i in 0 until arr.length()) {
        val cfg = arr.optJSONObject(i) ?: continue
        val name = cfg.optString("remarks").ifBlank { "Сервер ${i + 1}" }
        val vless = firstVless(cfg)
        val vn = vless?.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0)
        servers += ServerNode(name, vn?.optString("address").orEmpty(), vn?.optInt("port", 443) ?: 443)
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

fun delayConfig(raw: String, server: String): String {
    val arr = JSONArray(raw)
    var cfg = arr.optJSONObject(0) ?: error("пустая подписка")
    for (i in 0 until arr.length()) {
        val c = arr.optJSONObject(i) ?: continue
        if (c.optString("remarks") == server) { cfg = c; break }
    }
    val proxy = JSONObject(firstVless(cfg)?.toString() ?: error("нет vless"))
    proxy.put("tag", "proxy")
    return JSONObject()
        .put("outbounds", JSONArray().put(proxy).put(JSONObject().put("tag", "direct").put("protocol", "freedom")))
        .put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1")))
        .put("routing", JSONObject()
            .put("domainStrategy", "AsIs")
            .put("rules", JSONArray().put(JSONObject().put("type", "field").put("network", "tcp,udp").put("outboundTag", "proxy"))))
        .toString()
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

private fun firstVless(cfg: JSONObject): JSONObject? {
    val obs = cfg.optJSONArray("outbounds") ?: return null
    for (i in 0 until obs.length()) {
        val o = obs.optJSONObject(i) ?: continue
        if (o.optString("protocol") == "vless") return o
    }
    return null
}
