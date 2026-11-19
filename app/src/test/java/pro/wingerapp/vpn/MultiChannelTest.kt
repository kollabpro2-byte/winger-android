package pro.wingerapp.vpn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Схема «клиент → CDN → несколько входов»: один узел приходит несколькими
 * XHTTP-outbound'ами с разными путями. Потоки надо развести по ним, иначе
 * суммарная скорость упирается в лимит одного TCP-соединения к краю CDN.
 */
class MultiChannelTest {

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

    private fun build(paths: List<String>, multi: Boolean = true, uuid: String = "uuid-1"): JSONObject =
        JSONObject(
            toSingBox(xrayConfigFor(config(paths, uuid), "ya"), TunnelOptions(multiChannel = multi))
        )

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
    fun threePathsBecomeThreeOutbounds() {
        val sb = build(listOf("/a", "/b", "/c"))
        assertEquals(listOf("direct", "proxy", "proxy2", "proxy3"), tags(sb))

        val outs = sb.getJSONArray("outbounds")
        val paths = (1..3).map { outs.getJSONObject(it).getJSONObject("transport").getString("path") }
        assertEquals(listOf("/a", "/b", "/c"), paths)
    }

    /**
     * balancer'а в sing-box нет, поэтому делим потоки по диапазонам порта
     * источника: он у каждого соединения случайный, значит деление равномерное.
     */
    @Test
    fun flowsAreSplitBySourcePortRange() {
        val split = splitRules(build(listOf("/a", "/b", "/c")))
        assertEquals(3, split.size)
        assertEquals(listOf("proxy", "proxy2", "proxy3"), split.map { it.getString("outbound") })
        assertEquals("0:21844", split[0].getJSONArray("source_port_range").getString(0))
        assertEquals("43690:65535", split[2].getJSONArray("source_port_range").getString(0))
    }

    @Test
    fun singlePathStaysSingle() {
        val sb = build(listOf("/a"))
        assertEquals(listOf("direct", "proxy"), tags(sb))
        assertEquals(0, splitRules(sb).size)
    }

    @Test
    fun multiChannelCanBeDisabled() {
        val sb = build(listOf("/a", "/b", "/c"), multi = false)
        assertEquals(listOf("direct", "proxy"), tags(sb))
        assertEquals(0, splitRules(sb).size)
    }

    /** Разные UUID — это разные узлы, склеивать их в каналы нельзя. */
    @Test
    fun differentUuidIsNotMerged() {
        val raw = """
            [
              { "remarks": "ya", "outbounds": [
                { "tag": "ya1", "protocol": "vless",
                  "settings": { "vnext": [ { "address": "ya5.example", "port": 443,
                    "users": [ { "id": "uuid-1" } ] } ] },
                  "streamSettings": { "network": "xhttp", "security": "tls",
                    "xhttpSettings": { "path": "/a", "mode": "packet-up" } } },
                { "tag": "ya2", "protocol": "vless",
                  "settings": { "vnext": [ { "address": "ya5.example", "port": 443,
                    "users": [ { "id": "uuid-2" } ] } ] },
                  "streamSettings": { "network": "xhttp", "security": "tls",
                    "xhttpSettings": { "path": "/b", "mode": "packet-up" } } },
                { "tag": "direct", "protocol": "freedom" } ] }
            ]
        """.trimIndent()
        val sb = JSONObject(toSingBox(xrayConfigFor(raw, "ya"), TunnelOptions()))
        assertEquals(listOf("direct", "proxy"), tags(sb))
        assertEquals(0, splitRules(sb).size)
    }
}
