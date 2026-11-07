package pro.wingerapp.vpn

import org.json.JSONArray
import org.json.JSONObject

/**
 * sing-box file log. Kept off: libbox always feeds log lines into CommandServer's
 * platform writer, and that path panicked in TUN mode. A file `output` does not
 * detach the writer. writeDebugMessage itself runs only when SetupOptions.debug
 * is true, which this app does not set. Flip the flag to also write warn logs
 * to [toSingBox]'s coreLogFile.
 */
const val CORE_FILE_LOG = false

/** Convert Xray config to minimal sing-box config with TUN. */
fun toSingBox(xrayJson: String, coreLogFile: String? = null): String {
    val xray = JSONObject(xrayJson)
    val outbounds = xray.optJSONArray("outbounds") ?: JSONArray()
    val proxy = firstVless(outbounds) ?: return minimalTun(coreLogFile)
    val sb = JSONObject()
    sb.put("log", coreLog(coreLogFile))
    sb.put("inbounds", JSONArray().put(JSONObject()
        .put("type", "tun")
        .put("tag", "tun-in")
        .put("address", JSONArray().put("172.19.0.1/30"))
        .put("auto_route", false)
        .put("strict_route", false)))
    val sbOut = JSONArray()
    sbOut.put(JSONObject().put("type", "direct").put("tag", "direct"))
    sbOut.put(convertVless(proxy))
    sb.put("outbounds", sbOut)
    sb.put("route", JSONObject()
        .put("auto_detect_interface", true)
        .put("rules", JSONArray()
            .put(JSONObject().put("action", "sniff"))
            .put(JSONObject().put("action", "route").put("outbound", "proxy"))))
    return sb.toString()
}

/** Short config summary. Omits uuid, keys, short id, and passwords. */
fun summarizeSingBox(sbConfig: String): String = try {
    val o = JSONObject(sbConfig)
    val inbounds = o.optJSONArray("inbounds") ?: JSONArray()
    var tun = false
    var tunAddr = "-"
    val inDesc = ArrayList<String>(inbounds.length())
    for (i in 0 until inbounds.length()) {
        val ib = inbounds.optJSONObject(i) ?: continue
        val type = ib.optString("type", "?")
        val addr = ib.opt("address")?.toString()?.take(80).orEmpty()
        if (type == "tun") {
            tun = true
            if (addr.isNotBlank()) tunAddr = addr
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
            val transport = ob.optJSONObject("transport")?.optString("type").orEmpty().ifBlank { "-" }
            val tls = ob.optJSONObject("tls")
            val tlsOn = tls?.optBoolean("enabled") == true
            val reality = tls?.optJSONObject("reality")?.optBoolean("enabled") == true
            val flow = ob.optString("flow").ifBlank { "-" }
            proxyLine = "proxy=$host:$port transport=$transport tls=$tlsOn reality=$reality flow=$flow"
        }
    }
    val dns = o.has("dns") && !o.isNull("dns")
    val rules = o.optJSONObject("route")?.optJSONArray("rules")?.length() ?: 0
    "cfg tun=$tun addr=$tunAddr in=[${inDesc.joinToString(",")}] out=[${outDesc.joinToString(",")}] $proxyLine dns=$dns rules=$rules"
} catch (e: Throwable) {
    "cfg summary fail: ${e.javaClass.simpleName}: ${e.message}"
}

private fun coreLog(path: String?): JSONObject {
    if (!CORE_FILE_LOG || path.isNullOrBlank()) return JSONObject().put("disabled", true)
    return JSONObject().put("level", "warn").put("output", path).put("timestamp", true)
}

private fun firstVless(obs: JSONArray): JSONObject? {
    for (i in 0 until obs.length()) {
        val o = obs.optJSONObject(i) ?: continue
        if (o.optString("protocol").equals("vless", true)) return o
    }
    return null
}

private fun convertVless(xray: JSONObject): JSONObject {
    val settings = xray.optJSONObject("settings") ?: JSONObject()
    val vnext = settings.optJSONArray("vnext")?.optJSONObject(0) ?: JSONObject()
    val users = vnext.optJSONArray("users")?.optJSONObject(0) ?: JSONObject()
    val stream = xray.optJSONObject("streamSettings") ?: JSONObject()
    val reality = stream.optJSONObject("realitySettings") ?: JSONObject()
    val network = stream.optString("network", "tcp")
    val sb = JSONObject()
    sb.put("type", "vless")
    sb.put("tag", "proxy")
    sb.put("server", vnext.optString("address"))
    sb.put("server_port", vnext.optInt("port", 443))
    sb.put("uuid", users.optString("id"))
    val flow = users.optString("flow", "")
    if (flow.isNotBlank() && network != "xhttp") sb.put("flow", flow)
    if (network == "xhttp") {
        val xhttp = stream.optJSONObject("xhttpSettings") ?: JSONObject()
        val transport = JSONObject().put("type", "xhttp")
        xhttp.optString("path").takeIf { it.isNotBlank() }?.let { transport.put("path", it) }
        xhttp.optString("mode").takeIf { it.isNotBlank() }?.let { transport.put("mode", it) }
        xhttp.optString("host").takeIf { it.isNotBlank() }?.let { transport.put("host", it) }
        sb.put("transport", transport)
    }
    if (stream.optString("security") == "reality") {
        sb.put("tls", JSONObject()
            .put("enabled", true)
            .put("server_name", reality.optString("serverName"))
            .put("utls", JSONObject().put("enabled", true).put("fingerprint", reality.optString("fingerprint", "chrome")))
            .put("reality", JSONObject()
                .put("enabled", true)
                .put("public_key", reality.optString("publicKey"))
                .put("short_id", reality.optString("shortId"))))
    }
    return sb
}

private fun minimalTun(coreLogFile: String? = null): String = JSONObject()
    .put("log", coreLog(coreLogFile))
    .put("inbounds", JSONArray().put(JSONObject()
        .put("type", "tun")
        .put("tag", "tun-in")
        .put("address", JSONArray().put("172.19.0.1/30"))))
    .put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
    .toString()
