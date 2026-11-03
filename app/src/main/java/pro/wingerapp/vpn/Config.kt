package pro.wingerapp.vpn

import org.json.JSONObject

data class SubConfig(val raw: String, val servers: List<String>)

/** Конфиг, который Remnawave уже собрала для sing-box. */
fun readSubConfig(body: String): SubConfig {
    val root = JSONObject(body)
    val outbounds = root.getJSONArray("outbounds")
    val servers = mutableListOf<String>()
    for (i in 0 until outbounds.length()) {
        val o = outbounds.getJSONObject(i)
        if (o.optString("type") == "selector") {
            val opts = o.optJSONArray("outbounds") ?: continue
            for (j in 0 until opts.length()) servers += opts.getString(j)
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
