package pro.wingerapp.vpn

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** TCP handshake time. IPv4 preferred — IPv6 often blackholes in RU. */
fun tcpPing(host: String, port: Int): Int? {
    if (host.isBlank() || port !in 1..65535) return null
    val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrNull() ?: return null
    val v4 = addresses.filterIsInstance<Inet4Address>()
    val ordered = (if (v4.isNotEmpty()) v4 else addresses).take(2)
    for (ip in ordered) {
        val ms = runCatching {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(ip, port), 1800)
                ((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(1)
            }
        }.getOrNull()
        if (ms != null) return ms
    }
    return null
}

