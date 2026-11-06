package pro.wingerapp.vpn

import libv2ray.Libv2ray
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private val httpGate = Semaphore(8)

/**
 * TCP handshake time, the same measurement v2rayNG / INCY show as "TCP ping":
 * a plain connect to the proxy address and port. IPv4 is preferred because
 * mobile IPv6 in RU often blackholes and would make every server look dead.
 * The app is excluded from its own VPN, so the socket must not be bound to a
 * specific network — that bind failed on every server.
 */
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
                socket.connect(InetSocketAddress(ip, port), 3000)
                ((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(1)
            }
        }.getOrNull()
        if (ms != null) return ms
    }
    return null
}

fun httpPing(config: String, url: String): Int? {
    if (!httpGate.tryAcquire(8, TimeUnit.SECONDS)) return null
    try {
        val target = url.trim().ifBlank { "https://www.gstatic.com/generate_204" }
        val box = java.util.concurrent.atomic.AtomicReference<Int?>(null)
        val worker = Thread {
            box.set(runCatching {
                val ms = Libv2ray.measureOutboundDelay(config, target)
                if (ms < 0 || ms > 8000) null else (ms / 2).toInt().coerceAtLeast(1)
            }.getOrNull())
        }
        worker.isDaemon = true
        worker.start()
        worker.join(4000)
        return if (worker.isAlive) null else box.get()
    } finally {
        httpGate.release()
    }
}
