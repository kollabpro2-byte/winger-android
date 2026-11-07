package pro.wingerapp.vpn

import org.json.JSONArray
import org.json.JSONObject

/**
 * sing-box file log. Enabled for diagnosis. libbox feeds log lines into
 * CommandServer platform writer; that path can panic in TUN mode if debug=true.
 * File output is separate. Flip back to false after diagnosis.
 */
const val CORE_FILE_LOG = true

/** Convert Xray config to minimal sing-box config with TUN. */
fun toSingBox(xrayJson: String, coreLogFile: String? = null, dnsServer: String = "1.1.1.1"): String {
    val xray = JSONObject(xrayJson)
    val outbounds = xray.optJSONArray("outbounds") ?: JSONArray()
    val proxy = firstVless(outbounds) ?: return minimalTun(coreLogFile, dnsServer)
    val sb = JSONObject()
    sb.put("log", coreLog(coreLogFile))
    sb.put("dns", dnsSection(dnsServer))
    sb.put("inbounds", JSONArray().put(tunInbound()))
    val sbOut = JSONArray()
    sbOut.put(JSONObject().put("type", "direct").put("tag", "direct"))
    sbOut.put(convertVless(proxy))
    sb.put("outbounds", sbOut)
    sb.put("route", JSONObject()
        .put("auto_detect_interface", true)
        .put("rules", JSONArray()
            .put(JSONObject().put("action", "sniff"))
            .put(JSONObject().put("action", "hijack-dns").put("protocol", "dns"))
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
            val stack = ib.optString("stack").ifBlank { "default" }
            val mtu = if (ib.has("mtu")) ib.optInt("mtu").toString() else "default"
            tunAddr = "$addr stack=$stack mtu=$mtu autoRoute=${ib.optBoolean("auto_route")}"
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
    return JSONObject().put("level", "debug").put("output", path).put("timestamp", true)
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
        val extra = xhttp.optJSONObject("extra")
        if (extra != null) {
            extra.optString("uplinkHTTPMethod").takeIf { it.isNotBlank() }?.let { transport.put("uplink_http_method", it) }
            extra.optString("xPaddingBytes").takeIf { it.isNotBlank() }?.let { transport.put("x_padding_bytes", it) }
            if (extra.has("xPaddingObfsMode")) transport.put("x_padding_obfs_mode", extra.optBoolean("xPaddingObfsMode"))
            extra.optString("xPaddingPlacement").takeIf { it.isNotBlank() }?.let { transport.put("x_padding_placement", it) }
            extra.optString("xPaddingKey").takeIf { it.isNotBlank() }?.let { transport.put("x_padding_key", it) }
            extra.optString("xPaddingHeader").takeIf { it.isNotBlank() }?.let { transport.put("x_padding_header", it) }
            extra.optString("xPaddingMethod").takeIf { it.isNotBlank() }?.let { transport.put("x_padding_method", it) }
            extra.optString("seqPlacement").takeIf { it.isNotBlank() }?.let { transport.put("seq_placement", it) }
            extra.optString("seqKey").takeIf { it.isNotBlank() }?.let { transport.put("seq_key", it) }
            extra.optString("sessionPlacement").takeIf { it.isNotBlank() }?.let { transport.put("session_placement", it) }
            extra.optString("sessionKey").takeIf { it.isNotBlank() }?.let { transport.put("session_key", it) }
            extra.optString("sessionIDKey").takeIf { it.isNotBlank() }?.let { transport.put("session_id_key", it) }
            extra.optString("sessionIDPlacement").takeIf { it.isNotBlank() }?.let { transport.put("session_id_placement", it) }
            extra.optString("uplinkDataPlacement").takeIf { it.isNotBlank() }?.let { transport.put("uplink_data_placement", it) }
            extra.optString("uplinkDataKey").takeIf { it.isNotBlank() }?.let { transport.put("uplink_data_key", it) }
            extra.optString("sessionIdFormat").takeIf { it.isNotBlank() }?.let { transport.put("session_id_format", it) }
            if (extra.has("scMaxBufferedPosts")) transport.put("sc_max_buffered_posts", extra.optInt("scMaxBufferedPosts"))
            extra.optString("scMaxEachPostBytes").takeIf { it.isNotBlank() }?.let { transport.put("sc_max_each_post_bytes", it) }
            extra.optString("scMinPostsIntervalMs").takeIf { it.isNotBlank() }?.let { transport.put("sc_min_posts_interval_ms", it) }
            val xmux = extra.optJSONObject("xmux")
            if (xmux != null) transport.put("xmux", xmux)
        }
        sb.put("transport", transport)
    }
    val security = stream.optString("security", "")
    if (security == "reality") {
        sb.put("tls", JSONObject()
            .put("enabled", true)
            .put("server_name", reality.optString("serverName"))
            .put("utls", JSONObject().put("enabled", true).put("fingerprint", reality.optString("fingerprint", "chrome")))
            .put("reality", JSONObject()
                .put("enabled", true)
                .put("public_key", reality.optString("publicKey"))
                .put("short_id", reality.optString("shortId"))))
    } else if (security == "tls") {
        val tls = stream.optJSONObject("tlsSettings") ?: JSONObject()
        val serverName = tls.optString("serverName").ifBlank { vnext.optString("address") }
        val tlsObj = JSONObject()
            .put("enabled", true)
            .put("server_name", serverName)
            .put("utls", JSONObject().put("enabled", true).put("fingerprint", tls.optString("fingerprint", "chrome")))
        val alpn = tls.optJSONArray("alpn")
        if (alpn != null && alpn.length() > 0) tlsObj.put("alpn", alpn)
        sb.put("tls", tlsObj)
    }
    return sb
}

private fun tunInbound(): JSONObject = JSONObject()
    .put("type", "tun")
    .put("tag", "tun-in")
    .put("address", JSONArray().put("172.19.0.1/30"))
    .put("mtu", 1500)
    // auto_route stays off: with it on, OpenTun's callback into TunOptions.GetInet4Address
    // nil-panics in libbox 1.14.4-lx.33. Routes are installed by VpnService.Builder.
    .put("auto_route", false)
    .put("strict_route", false)
    .put("stack", "gvisor")
    .put("dns_mode", "hijack")

private fun dnsSection(server: String): JSONObject {
    val upstream = server.trim().ifBlank { "1.1.1.1" }
    return JSONObject()
        .put("servers", JSONArray().put(JSONObject()
            .put("type", "udp")
            .put("tag", "remote")
            .put("server", upstream)))
        .put("final", "remote")
}

private fun minimalTun(coreLogFile: String? = null, dnsServer: String = "1.1.1.1"): String = JSONObject()
    .put("log", coreLog(coreLogFile))
    .put("dns", dnsSection(dnsServer))
    .put("inbounds", JSONArray().put(tunInbound()))
    .put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
    .put("route", JSONObject()
        .put("auto_detect_interface", true)
        .put("rules", JSONArray()
            .put(JSONObject().put("action", "hijack-dns").put("protocol", "dns"))
            .put(JSONObject().put("action", "route").put("outbound", "direct"))))
    .toString()
