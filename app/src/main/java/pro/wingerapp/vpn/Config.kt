package pro.wingerapp.vpn

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
