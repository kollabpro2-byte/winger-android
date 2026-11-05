package pro.wingerapp.vpn

import libv2ray.Libv2ray
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

fun httpPing(config: String, url: String): Int? {
    val target = url.trim().ifBlank { "https://www.gstatic.com/generate_204" }
    return runCatching {
        val ms = Libv2ray.measureOutboundDelay(config, target)
        if (ms < 0) null else ms.toInt()
    }.getOrNull()
}
