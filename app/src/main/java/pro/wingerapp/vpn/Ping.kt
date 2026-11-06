package pro.wingerapp.vpn

import libv2ray.Libv2ray
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

// Higher concurrency for faster full-list ping (IncY-style)
private val coreGate = Semaphore(16)

/**
 * TCP handshake time (fallback). IPv4 preferred — IPv6 often blackholes in RU.
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
                socket.connect(InetSocketAddress(ip, port), 1800)
                ((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(1)
            }
        }.getOrNull()
        if (ms != null) return ms
    }
    return null
}

/**
 * Real delay through Xray core (measureOutboundDelay).
 * This is what IncY / v2rayNG use for accurate "working" servers.
 * Returns half the measured RTT (like many clients display) or null on failure.
 */
fun httpPing(config: String, url: String): Int? {
    if (!coreGate.tryAcquire(6, TimeUnit.SECONDS)) return null
    try {
        val target = url.trim().ifBlank { "https://www.gstatic.com/generate_204" }
        val box = java.util.concurrent.atomic.AtomicReference<Int?>(null)
        val worker = Thread {
            box.set(runCatching {
                val ms = Libv2ray.measureOutboundDelay(config, target)
                when {
                    ms < 0 -> null
                    ms > 6000 -> null
                    else -> (ms / 2).toInt().coerceAtLeast(1)
                }
            }.getOrNull())
        }
        worker.isDaemon = true
        worker.start()
        worker.join(3200)
        return if (worker.isAlive) null else box.get()
    } finally {
        coreGate.release()
    }
}
