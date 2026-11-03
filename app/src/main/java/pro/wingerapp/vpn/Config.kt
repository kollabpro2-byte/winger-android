package pro.wingerapp.vpn

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** vless:// ссылка из подписки Remnawave → конфиг sing-box. */
fun singBoxConfig(link: String): String {
    val uri = Uri.parse(link)
    val uuid = uri.userInfo.orEmpty()
    val host = uri.host.orEmpty()
    val port = if (uri.port > 0) uri.port else 443
    fun q(k: String) = uri.getQueryParameter(k).orEmpty()

    val tls = JSONObject()
        .put("enabled", true)
        .put("server_name", q("sni").ifBlank { q("peer") }.ifBlank { host })
        .put("utls", JSONObject().put("enabled", true).put("fingerprint", q("fp").ifBlank { "chrome" }))
    if (q("security") == "reality" || q("pbk").isNotBlank()) {
        tls.put("reality", JSONObject()
            .put("enabled", true)
            .put("public_key", q("pbk"))
            .put("short_id", q("sid")))
    }

    val outbound = JSONObject()
        .put("type", "vless")
        .put("tag", "proxy")
        .put("server", host)
        .put("server_port", port)
        .put("uuid", uuid)
        .put("tls", tls)
    if (q("flow").isNotBlank()) outbound.put("flow", q("flow"))
    val net = q("type").ifBlank { "tcp" }
    if (net == "xhttp" || net == "splithttp") {
        outbound.put("transport", JSONObject()
            .put("type", "xhttp")
            .put("path", q("path").ifBlank { "/" })
            .put("host", q("host")))
    }

    return JSONObject()
        .put("log", JSONObject().put("level", "warn"))
        .put("inbounds", JSONArray().put(JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("address", JSONArray().put("172.19.0.1/30"))
            .put("auto_route", true)
            .put("strict_route", true)))
        .put("outbounds", JSONArray()
            .put(outbound)
            .put(JSONObject().put("type", "direct").put("tag", "direct")))
        .put("route", JSONObject().put("auto_detect_interface", true))
        .toString()
}
