package pro.wingerapp.vpn

import org.json.JSONArray
import org.json.JSONObject

/** Convert Xray config to minimal sing-box config with TUN. */
fun toSingBox(xrayJson: String): String {
    val xray = JSONObject(xrayJson)
    val outbounds = xray.optJSONArray("outbounds") ?: JSONArray()
    val proxy = firstVless(outbounds) ?: return minimalTun()
    val sb = JSONObject()
    // logging disabled: known native panic in TUN mode when Go writes via CommandServer
    sb.put("log", JSONObject().put("disabled", true))
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

private fun minimalTun(): String = JSONObject()
    .put("log", JSONObject().put("disabled", true))
    .put("inbounds", JSONArray().put(JSONObject()
        .put("type", "tun")
        .put("tag", "tun-in")
        .put("address", JSONArray().put("172.19.0.1/30"))))
    .put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
    .toString()
