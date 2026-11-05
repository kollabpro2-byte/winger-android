package pro.wingerapp.vpn

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import libv2ray.Libv2ray
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private val httpGate = Semaphore(8)

fun tcpPing(host: String, port: Int): Int? {
    if (host.isBlank() || port !in 1..65535) return null
    return runCatching {
        val socket = bareSocket()
        socket.use {
            val start = System.nanoTime()
            it.connect(InetSocketAddress(host, port), 3000)
            ((System.nanoTime() - start) / 1_000_000).toInt().takeIf { ms -> ms in 0..3000 }
        }
    }.getOrNull()
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

private fun bareSocket(): Socket {
    val app = runCatching { pro.wingerapp.vpn.net.Api.app }.getOrNull() ?: return Socket()
    val cm = app.getSystemService(ConnectivityManager::class.java)
    val net = cm.allNetworks.firstOrNull {
        val caps = cm.getNetworkCapabilities(it) ?: return@firstOrNull false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
    return (net?.socketFactory?.createSocket() ?: Socket()) as Socket
}
