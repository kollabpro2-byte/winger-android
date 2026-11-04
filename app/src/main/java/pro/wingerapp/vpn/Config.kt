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

fun xrayConfigFor(raw: String, server: String): String {
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
        .put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls").put("quic"))))
    val routing = cfg.optJSONObject("routing") ?: JSONObject().also { cfg.put("routing", it) }
    val rules = routing.optJSONArray("rules") ?: JSONArray().also { routing.put("rules", it) }
    val target = routing.optJSONArray("balancers")?.optJSONObject(0)?.optString("tag")
    val rule = JSONObject().put("type", "field").put("inboundTag", JSONArray().put("tun"))
    if (!target.isNullOrBlank()) rule.put("balancerTag", target)
    else rule.put("outboundTag", firstVless(cfg)?.optString("tag") ?: "proxy")
    val next = JSONArray().put(rule)
    for (i in 0 until rules.length()) next.put(rules.get(i))
    routing.put("rules", next)
    return cfg.toString()
}

private fun firstVless(cfg: JSONObject): JSONObject? {
    val obs = cfg.optJSONArray("outbounds") ?: return null
    for (i in 0 until obs.length()) {
        val o = obs.optJSONObject(i) ?: continue
        if (o.optString("protocol") == "vless") return o
    }
    return null
}
