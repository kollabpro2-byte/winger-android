package pro.wingerapp.vpn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Один узел — один outbound.
 *
 * Раньше узел с несколькими XHTTP-входами разводился на три outbound'а по
 * диапазонам порта источника. Проверка на телефоне показала: деление по порту
 * распределяет **соединения**, а не байты, поэтому одиночный поток (видео) всё
 * равно упирается в лимит одного соединения к краю CDN, а конфиг только
 * усложняется. Многоканальность убрана; честность пинга решается отдельно
 * ([isCdnNode]).
 */
class SingleChannelTest {

    private fun config(paths: List<String>, uuid: String = "uuid-1"): String {
        val outs = paths.mapIndexed { i, p ->
            """
            { "tag": "ya${i + 1}", "protocol": "vless",
              "settings": { "vnext": [ { "address": "ya5.example", "port": 443,
                "users": [ { "id": "$uuid", "encryption": "none" } ] } ] },
              "streamSettings": { "network": "xhttp", "security": "tls",
                "tlsSettings": { "serverName": "ya5.example" },
                "xhttpSettings": { "host": "ya5.example", "path": "$p", "mode": "packet-up" } } }
            """.trimIndent()
        }.joinToString(",\n")
        return """[ { "remarks": "ya", "outbounds": [ $outs,
            { "tag": "direct", "protocol": "freedom" } ] } ]"""
    }

    private fun build(paths: List<String>): JSONObject =
        JSONObject(toSingBox(xrayConfigFor(config(paths), "ya"), TunnelOptions()))

    private fun tags(sb: JSONObject): List<String> {
        val outs = sb.getJSONArray("outbounds")
        return (0 until outs.length()).map { outs.getJSONObject(it).getString("tag") }
    }

    private fun splitRules(sb: JSONObject): List<JSONObject> {
        val rules = sb.getJSONObject("route").getJSONArray("rules")
        return (0 until rules.length()).map { rules.getJSONObject(it) }
            .filter { it.has("source_port_range") }
    }

    @Test
    fun threePathsBecomeOneOutbound() {
        val sb = build(listOf("/a", "/b", "/c"))
        assertEquals(listOf("direct", "proxy"), tags(sb))
    }

    @Test
    fun noSourcePortRules() {
        val sb = build(listOf("/a", "/b", "/c"))
        assertEquals(0, splitRules(sb).size)
    }

    /** Первый вход используется как есть: путь из подписки не переписывается. */
    @Test
    fun firstPathIsKept() {
        val sb = build(listOf("/a", "/b"))
        val path = sb.getJSONArray("outbounds").getJSONObject(1)
            .getJSONObject("transport").getString("path")
        assertEquals("/a", path)
    }

    // ---- честность пинга: CDN-узел не измеряем ----

    private fun node(host: String, extraTls: String = ""): String = """
        [ { "remarks": "node", "outbounds": [
          { "tag": "proxy", "protocol": "vless",
            "settings": { "vnext": [ { "address": "$host", "port": 443,
              "users": [ { "id": "uuid-1", "encryption": "none" } ] } ] },
            "streamSettings": { "network": "tcp", "security": "tls",
              "tlsSettings": { "serverName": "$host" $extraTls } } },
          { "tag": "direct", "protocol": "freedom" } ] } ]
    """.trimIndent()

    @Test
    fun pinnedCertMeansCdn() {
        // Пиннинг серта — признак края CDN: TCP завершает край, не сервер.
        val pinned = ", \"pinnedPeerCertSha256\": \"ab12\""
        assertTrue(isCdnNode(node("edge.example", pinned), "node"))
    }

    @Test
    fun knownCdnHostMeansCdn() {
        assertTrue(isCdnNode(node("ya5.wingertop.pro"), "node"))
    }

    @Test
    fun plainServerIsNotCdn() {
        assertFalse(isCdnNode(node("msk.example"), "node"))
    }
}
