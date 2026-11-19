package pro.wingerapp.vpn

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пинг должен идти до конечного сервера, а не до первого хопа цепочки.
 * В CDN-схеме первый outbound смотрит на край, а настоящий сервер — в конце.
 */
class EndpointChainTest {

    private fun cfg(outbounds: String): String = """
        [
          { "remarks": "node", "outbounds": $outbounds }
        ]
    """.trimIndent()

    @Test
    fun chainEndsAtTheRealServerNotAtTheEdge() {
        val raw = cfg(
            """
            [
              { "tag": "proxy", "protocol": "vless",
                "settings": { "vnext": [ { "address": "edge.cdn.example", "port": 443 } ] },
                "proxySettings": { "tag": "origin" } },
              { "tag": "origin", "protocol": "vless",
                "settings": { "vnext": [ { "address": "real.example.com", "port": 8443 } ] } },
              { "tag": "direct", "protocol": "freedom" }
            ]
            """
        )
        val terminal = terminalEndpoints(raw, "node")
        assertEquals(listOf(Endpoint("real.example.com", 8443)), terminal)
    }

    @Test
    fun dialerProxyIsFollowedToo() {
        val raw = cfg(
            """
            [
              { "tag": "proxy", "protocol": "vless",
                "settings": { "vnext": [ { "address": "edge.example", "port": 443 } ] },
                "streamSettings": { "sockopt": { "dialerProxy": "hop2" } } },
              { "tag": "hop2", "protocol": "vless",
                "settings": { "vnext": [ { "address": "hop2.example", "port": 443 } ] },
                "streamSettings": { "sockopt": { "dialerProxy": "origin" } } },
              { "tag": "origin", "protocol": "trojan",
                "settings": { "servers": [ { "address": "final.example", "port": 9443 } ] } }
            ]
            """
        )
        assertEquals(listOf(Endpoint("final.example", 9443)), terminalEndpoints(raw, "node"))
    }

    @Test
    fun singleServerIsItsOwnTerminal() {
        val raw = cfg(
            """
            [
              { "tag": "proxy", "protocol": "vless",
                "settings": { "vnext": [ { "address": "gw.apigw.yandexcloud.net", "port": 443 } ] } },
              { "tag": "direct", "protocol": "freedom" }
            ]
            """
        )
        assertEquals(
            listOf(Endpoint("gw.apigw.yandexcloud.net", 443)),
            terminalEndpoints(raw, "node"),
        )
    }

    @Test
    fun cycleInTheChainTerminates() {
        val raw = cfg(
            """
            [
              { "tag": "a", "protocol": "vless",
                "settings": { "vnext": [ { "address": "a.example", "port": 443 } ] },
                "proxySettings": { "tag": "b" } },
              { "tag": "b", "protocol": "vless",
                "settings": { "vnext": [ { "address": "b.example", "port": 443 } ] },
                "proxySettings": { "tag": "a" } }
            ]
            """
        )
        val terminal = terminalEndpoints(raw, "node")
        assertTrue(terminal.isNotEmpty())
        // Цикл не должен висеть: выходим на первом повторе.
        assertTrue(terminal.first().host in setOf("a.example", "b.example"))
    }

    @Test
    fun configWithoutOutboundsYieldsNothing() {
        assertTrue(terminalEndpoints("""[ { "remarks": "x", "outbounds": [] } ]""", "x").isEmpty())
        assertTrue(terminalEndpoints("[]", "x").isEmpty())
    }

    @Test
    fun passwordAndUuidAreNotNeededForPing() {
        // Пинг не должен зависеть от секретов: адрес берётся из vnext.
        val raw = cfg(
            """
            [
              { "tag": "p", "protocol": "vless",
                "settings": { "vnext": [ { "address": "1.2.3.4", "port": 4443,
                  "users": [ { "id": "secret-uuid" } ] } ] } }
            ]
            """
        )
        assertEquals(listOf(Endpoint("1.2.3.4", 4443)), terminalEndpoints(raw, "node"))
    }

    @Test
    fun malformedJsonDoesNotThrow() {
        assertTrue(terminalEndpoints("{not json", "x").isEmpty())
    }

    /**
     * 0.0.0.0 и 127.0.0.1 принимают TCP мгновенно, не выходя в сеть — из-за них
     * у всех серверов одновременно появлялся «пинг 1 мс».
     */
    @Test
    fun serviceAddressesAreNotMeasured() {
        assertFalse(isUsableAddress(InetAddress.getByName("0.0.0.0")))
        assertFalse(isUsableAddress(InetAddress.getByName("127.0.0.1")))
        assertFalse(isUsableAddress(InetAddress.getByName("169.254.1.1")))
        assertTrue(isUsableAddress(InetAddress.getByName("8.8.8.8")))
    }

    /** Имя, которое резолвится в локальную сеть, — это подменённый DNS. */
    @Test
    fun nameResolvedToLanIsIgnored() {
        assertFalse(isSaneResolvedAddress(InetAddress.getByName("192.168.1.1")))
        assertFalse(isSaneResolvedAddress(InetAddress.getByName("10.0.0.7")))
        assertFalse(isSaneResolvedAddress(InetAddress.getByName("172.19.0.1")))
        assertTrue(isSaneResolvedAddress(InetAddress.getByName("8.8.8.8")))
    }

    /** Явный LAN-адрес в подписке — выбор пользователя, его мерим. */
    @Test
    fun explicitLanLiteralIsStillAllowed() {
        assertTrue(isUsableAddress(InetAddress.getByName("192.168.1.10")))
    }
}
