package pro.wingerapp.vpn

import java.net.InetSocketAddress
import java.net.Socket

fun tcpPing(host: String, port: Int): Int? {
    if (host.isBlank()) return null
    return runCatching {
        val start = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(host, port), 2500) }
        ((System.nanoTime() - start) / 1_000_000).toInt()
    }.getOrNull()
}
