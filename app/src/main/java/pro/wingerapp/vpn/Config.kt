package pro.wingerapp.vpn

import org.json.JSONArray
import org.json.JSONObject

data class ServerNode(val name: String, val host: String, val port: Int)
data class SubConfig(val raw: String, val servers: List<ServerNode>)

fun readSubConfig(body: String): SubConfig {
    val root = JSONObject(body)
    val outbounds = root.getJSONArray("outbounds")
    val byTag = mutableMapOf<String, ServerNode>()
    for (i in 0 until outbounds.length()) {
        val o = outbounds.getJSONObject(i)
        if (o.optString("type") == "vless") {
            byTag[o.optString("tag")] = ServerNode(
                o.optString("tag"),
                o.optString("server"),
                o.optInt("server_port", 443)
            )
        }
    }
    val servers = mutableListOf<ServerNode>()
    for (i in 0 until outbounds.length()) {
        val o = outbounds.getJSONObject(i)
        if (o.optString("type") == "selector") {
            val opts = o.optJSONArray("outbounds") ?: continue
            for (j in 0 until opts.length()) {
                val tag = opts.getString(j)
                servers += byTag[tag] ?: ServerNode(tag, "", 443)
            }
        }
    }
    if (servers.isEmpty()) error("в конфиге нет серверов")
    return SubConfig(body, servers)
}

fun configFor(raw: String, server: String): String {
    val root = JSONObject(raw)
    val outbounds = root.getJSONArray("outbounds")
    for (i in 0 until outbounds.length()) {
        val o = outbounds.getJSONObject(i)
        if (o.optString("type") == "selector") o.put("default", server)
    }
    return root.toString()
}

/** Добавляет LTE-обходы из Happ JSON, их нет в шаблоне sing-box. */
fun mergeBypass(singbox: String, happ: String): String {
    val root = JSONObject(singbox)
    val outbounds = root.getJSONArray("outbounds")
    val selector = (0 until outbounds.length())
        .map { outbounds.getJSONObject(it) }
        .firstOrNull { it.optString("type") == "selector" } ?: return singbox
    val opts = selector.optJSONArray("outbounds") ?: JSONArray().also { selector.put("outbounds", it) }
    val have = (0 until opts.length()).map { opts.getString(it) }.toMutableSet()
    val arr = JSONArray(happ)
    for (i in 0 until arr.length()) {
        val cfg = arr.optJSONObject(i) ?: continue
        val name = cfg.optString("remarks")
        if (!name.contains("LTE") || name.contains("Автопилот") || name in have) continue
        val vless = (cfg.optJSONArray("outbounds") ?: continue).let { obs ->
            (0 until obs.length()).map { obs.getJSONObject(it) }.firstOrNull { it.optString("protocol") == "vless" }
        } ?: continue
        val vn = vless.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0) ?: continue
        val user = vn.optJSONArray("users")?.optJSONObject(0) ?: continue
        val ss = vless.optJSONObject("streamSettings") ?: continue
        val xh = ss.optJSONObject("xhttpSettings") ?: JSONObject()
        val tls = ss.optJSONObject("tlsSettings") ?: JSONObject()
        val ob = JSONObject()
            .put("type", "vless")
            .put("tag", name)
            .put("server", vn.optString("address"))
            .put("server_port", vn.optInt("port", 443))
            .put("uuid", user.optString("id"))
            .put("tls", JSONObject()
                .put("enabled", true)
                .put("server_name", tls.optString("serverName"))
                .put("utls", JSONObject().put("enabled", true).put("fingerprint", tls.optString("fingerprint", "firefox"))))
            .put("transport", JSONObject()
                .put("type", "xhttp")
                .put("host", xh.optString("host"))
                .put("path", xh.optString("path", "/"))
                .put("mode", xh.optString("mode", "packet-up")))
        outbounds.put(ob)
        opts.put(name)
        have += name
    }
    return root.toString()
}
