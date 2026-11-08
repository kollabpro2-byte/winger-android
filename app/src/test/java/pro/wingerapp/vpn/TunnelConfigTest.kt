package pro.wingerapp.vpn

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.wingerapp.vpn.ui.daysUntil
import pro.wingerapp.vpn.ui.grouped

/**
 * The conversion from a subscription entry to the sing-box config is the place
 * where every settings toggle used to disappear. These tests pin the behaviour.
 */
class TunnelConfigTest {

    private fun node(
        remarks: String = "Msk",
        flow: String = "xtls-rprx-vision",
        network: String = "tcp",
        security: String = "reality",
        address: String = "1.2.3.4",
        ws: String = "{}",
        xhttp: String = "{}",
        tls: String = "{}",
    ): String = """
        {
          "remarks": "$remarks",
          "outbounds": [
            {
              "tag": "proxy",
              "protocol": "vless",
              "settings": {
                "vnext": [
                  {
                    "address": "$address",
                    "port": 443,
                    "users": [{ "id": "uuid-1", "flow": "$flow", "encryption": "none" }]
                  }
                ]
              },
              "streamSettings": {
                "network": "$network",
                "security": "$security",
                "realitySettings": {
                  "serverName": "example.com",
                  "publicKey": "PUBKEY",
                  "shortId": "ab12",
                  "fingerprint": "chrome"
                },
                "wsSettings": $ws,
                "xhttpSettings": $xhttp,
                "tlsSettings": $tls
              }
            }
          ]
        }
    """.trimIndent()

    private fun config(vararg nodes: String): String = "[" + nodes.joinToString(",") + "]"

    /** The real pipeline: subscription entry -> selected server -> sing-box config. */
    private fun pipe(nodeJson: String = node(), opts: TunnelOptions = TunnelOptions()): JSONObject =
        JSONObject(toSingBox(xrayConfigFor(config(nodeJson), "Msk"), opts))

    private fun proxy(sb: JSONObject): JSONObject {
        val outbounds = sb.getJSONArray("outbounds")
        return outbounds.getJSONObject(outbounds.length() - 1)
    }

    private fun rules(sb: JSONObject): JSONArray = sb.getJSONObject("route").getJSONArray("rules")

    @Test
    fun realityVlessKeepsFlowAndDnsGoesThroughTunnel() {
        val sb = pipe(opts = TunnelOptions(mtu = 1400))

        val proxy = proxy(sb)
        assertEquals("vless", proxy.getString("type"))
        assertEquals("1.2.3.4", proxy.getString("server"))
        assertEquals(443, proxy.getInt("server_port"))
        assertEquals("uuid-1", proxy.getString("uuid"))
        assertEquals("xtls-rprx-vision", proxy.getString("flow"))
        assertEquals("PUBKEY", proxy.getJSONObject("tls").getJSONObject("reality").getString("public_key"))

        val tun = sb.getJSONArray("inbounds").getJSONObject(0)
        assertEquals(1400, tun.getInt("mtu"))
        assertEquals("gvisor", tun.getString("stack"))
        assertFalse(tun.getBoolean("auto_route"))

        val dns = sb.getJSONObject("dns").getJSONArray("servers")
        assertEquals("bootstrap", dns.getJSONObject(0).getString("tag"))
        assertNull(dns.getJSONObject(0).opt("detour"))
        assertEquals("proxy", dns.getJSONObject(1).getString("detour"))

        val route = rules(sb)
        assertEquals("proxy", route.getJSONObject(route.length() - 1).getString("outbound"))
    }

    @Test
    fun missingVlessFailsClosed() {
        val noProxy = """[{"remarks":"Msk","outbounds":[{"protocol":"freedom","tag":"direct"}]}]"""
        assertThrows(IllegalStateException::class.java) { toSingBox(xrayConfigFor(noProxy, "Msk")) }
        assertThrows(IllegalStateException::class.java) { toSingBox("""{"outbounds":[]}""") }
    }

    @Test
    fun bypassDomainsBecomeDirectRules() {
        val sb = pipe(opts = TunnelOptions(directDomains = listOf("https://bank.ru/pay", "domain:ozon.ru", "  ")))
        val first = rules(sb).getJSONObject(0)
        assertEquals("direct", first.getString("outbound"))
        val suffixes = first.getJSONArray("domain_suffix")
        assertEquals(2, suffixes.length())
        assertEquals("bank.ru", suffixes.getString(0))
        assertEquals("ozon.ru", suffixes.getString(1))
    }

    @Test
    fun muxIsSkippedForVisionFlow() {
        val withVision = pipe(node(flow = "xtls-rprx-vision"), TunnelOptions(useMux = true))
        assertNull(proxy(withVision).optJSONObject("multiplex"))

        val plain = pipe(node(flow = ""), TunnelOptions(useMux = true))
        assertTrue(proxy(plain).getJSONObject("multiplex").getBoolean("enabled"))
    }

    @Test
    fun wsTransportIsConverted() {
        val ws = """{"path":"/vless-ws","headers":{"Host":"cdn.example.com"},"maxEarlyData":2048}"""
        val tls = """{"serverName":"cdn.example.com","alpn":["h2","http/1.1"]}"""
        val sb = pipe(node(network = "ws", security = "tls", ws = ws, tls = tls))
        val proxy = proxy(sb)
        val transport = proxy.getJSONObject("transport")
        assertEquals("ws", transport.getString("type"))
        assertEquals("/vless-ws", transport.getString("path"))
        assertEquals("cdn.example.com", transport.getJSONObject("headers").getString("Host"))
        assertEquals(2048, transport.getInt("max_early_data"))

        val tlsOut = proxy.getJSONObject("tls")
        assertEquals("cdn.example.com", tlsOut.getString("server_name"))
        assertEquals(2, tlsOut.getJSONArray("alpn").length())
        assertNull(proxy.opt("flow"))
    }

    @Test
    fun xhttpDropsFieldsUnknownToTheCore() {
        val extra = """
            {"uplinkHTTPMethod":"PUT","xPaddingBytes":"100-200","xPaddingObfsMode":true,
             "sessionKey":"_session","session_id_key":"X-Session","sessionIdFormat":"hex",
             "scMaxBufferedPosts":3,"scMaxEachPostBytes":"1000000","xmux":{"maxConcurrency":"16-32"}}
        """.trimIndent()
        val xhttp = """{"path":"/x","mode":"auto","host":"cdn.example.com","extra":$extra}"""
        val proxy = proxy(pipe(node(network = "xhttp", xhttp = xhttp)))
        val transport = proxy.getJSONObject("transport")
        assertEquals("xhttp", transport.getString("type"))
        assertEquals("/x", transport.getString("path"))
        assertEquals("auto", transport.getString("mode"))
        assertEquals("cdn.example.com", transport.getString("host"))
        assertEquals("PUT", transport.getString("uplink_http_method"))
        assertEquals("100-200", transport.getString("x_padding_bytes"))
        assertTrue(transport.getBoolean("x_padding_obfs_mode"))
        assertEquals("_session", transport.getString("session_key"))
        // Not in libbox 1.14.4-lx.33: one such key aborts the whole config
        // with "unknown field" and the tunnel never starts.
        assertNull(transport.opt("session_id_key"))
        assertNull(transport.opt("session_id_format"))
        assertNull(transport.opt("sc_max_buffered_posts"))
        assertNull(transport.opt("xmux"))
        assertNull(proxy.opt("flow"))
    }

    @Test
    fun minimalTransportKeepsOnlySafeFields() {
        val xhttp = """{"path":"/x","mode":"auto","extra":{"xPaddingBytes":"100-200","session_id_key":"X"}}"""
        val proxy = proxy(pipe(node(network = "xhttp", xhttp = xhttp), TunnelOptions(minimalTransport = true)))
        val transport = proxy.getJSONObject("transport")
        assertEquals("xhttp", transport.getString("type"))
        assertEquals("/x", transport.getString("path"))
        assertEquals("auto", transport.getString("mode"))
        assertNull(transport.opt("x_padding_bytes"))
    }

    @Test
    fun routeDeclaresDomainResolverForProxyEndpoint() {
        // The tunnel used to come up with no internet: the core could not dial
        // the proxy by name without a resolver ("missing
        // route.default_domain_resolver").
        val route = pipe(node(address = "bad..host")).getJSONObject("route")
        assertEquals("bootstrap", route.getString("default_domain_resolver"))
        assertTrue(route.getBoolean("auto_detect_interface"))
    }

    @Test
    fun ipEndpointNeedsNoResolver() {
        val route = pipe().getJSONObject("route")
        assertNull(route.opt("default_domain_resolver"))
    }

    @Test
    fun malformedProxyAddressDoesNotBreakTheConfig() {
        // A failed lookup must not abort the whole config: the core's bootstrap
        // resolver still gets a chance at dial time. The name below is rejected
        // by InetAddress itself, so the test does not depend on the network.
        val sb = pipe(node(address = "bad..host"))
        assertEquals("bad..host", proxy(sb).getString("server"))
    }

    @Test
    fun ipLiteralIsUsedAsIs() {
        assertEquals("1.2.3.4", proxy(pipe()).getString("server"))
    }

    @Test
    fun hijackedDnsAnswersAreNotUsedAsProxyAddress() {
        // Some ISPs answer every name with 0.0.0.0 / 240.x or a loopback, which
        // makes the tunnel dial nothing at all.
        assertFalse(plausibleAddress("240.0.1.97"))
        assertFalse(plausibleAddress("0.0.0.0"))
        assertFalse(plausibleAddress("127.0.0.1"))
        assertFalse(plausibleAddress("198.18.0.1"))
        assertFalse(plausibleAddress("169.254.1.1"))
        assertFalse(plausibleAddress("fe80::1"))
        assertTrue(plausibleAddress("92.242.30.5"))
        assertTrue(plausibleAddress("2a00:1450:4010:c02::64"))
    }

    @Test
    fun subscriptionServersAreParsed() {
        val sub = readHapp(config(node(remarks = "Москва"), node(remarks = "Питер")))
        assertEquals(2, sub.servers.size)
        assertEquals("Москва", sub.servers[0].name)
        assertEquals("1.2.3.4", sub.servers[0].host)
        assertEquals(443, sub.servers[0].port)
    }

    @Test
    fun singleFileSubscriptionIsRejected() {
        assertThrows(IllegalStateException::class.java) { readHapp("""{"outbounds":[]}""") }
    }

    @Test
    fun base64SubscriptionIsDecoded() {
        // Subscriptions are often base64 with line breaks, and this path used to
        // depend on android.util.Base64, i.e. was untestable off-device.
        val encoded = java.util.Base64.getMimeEncoder().encodeToString(config(node(remarks = "База")).toByteArray())
        val sub = readHapp(encoded)
        assertEquals(1, sub.servers.size)
        assertEquals("База", sub.servers[0].name)
        assertEquals("1.2.3.4", sub.servers[0].host)
    }

    @Test
    fun bypassEntriesAreNormalisedBeforeTheyReachTheCore() {
        val rules = routeRules(
            listOf("domain:bank.ru", "https://sber.ru/pay", " MIR.ru:443 ", "domain", "bank.ru", "https://")
        )
        val direct = rules.getJSONObject(0)
        assertEquals("direct", direct.getString("outbound"))
        val hosts = direct.getJSONArray("domain_suffix")
        assertEquals(3, hosts.length())
        assertEquals("bank.ru", hosts.getString(0))
        assertEquals("sber.ru", hosts.getString(1))
        assertEquals("mir.ru", hosts.getString(2))
        // Everything else still goes through the proxy.
        assertEquals("proxy", rules.getJSONObject(rules.length() - 1).getString("outbound"))
    }

    @Test
    fun serversAreGroupedByTheirName() {
        val groups = grouped(
            listOf(
                ServerNode("Автопилот", "1.1.1.1", 443),
                ServerNode("Германия", "2.2.2.2", 443),
                ServerNode("YouTube обход", "3.3.3.3", 443),
            )
        )
        // "YouTube обход" matches two keywords: it must still be listed once.
        assertEquals(
            listOf(R.string.group_recommended, R.string.group_countries, R.string.group_youtube),
            groups.map { it.titleRes }
        )
        assertEquals(1, groups.first { it.titleRes == R.string.group_countries }.nodes.size)
        assertEquals(3, groups.sumOf { it.nodes.size })
    }

    @Test
    fun daysUntilCountsCalendarDaysAndRejectsNoise() {
        val today = java.time.LocalDate.of(2030, 1, 1)
        assertEquals(10L, daysUntil("2030-01-11", today))
        assertEquals(10L, daysUntil("2030-01-11T09:30:00Z", today))
        assertNull(daysUntil("not-a-date", today))
        assertNull(daysUntil(null, today))
    }

    @Test
    fun logSummaryNeverContainsCredentials() {
        val summary = summarizeSingBox(toSingBox(xrayConfigFor(config(node()), "Msk")))
        assertFalse(summary.contains("uuid-1"))
        assertFalse(summary.contains("PUBKEY"))
        assertFalse(summary.contains("ab12"))
        assertTrue(summary.contains("vless/proxy"))
        assertTrue(summary.contains("resolver=-"))
    }
}
