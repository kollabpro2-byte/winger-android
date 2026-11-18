package pro.wingerapp.vpn

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP-пинг: время TCP-рукопожатия с конечным адресом сервера.
 *
 * Честность: измеряется только `connect`, DNS в замер не входит, ICMP не
 * используется (на мобильных сетях он часто зарезан). Скорость: адрес кэшируется
 * на 10 минут — иначе на подписке из 50 серверов каждый замер делает 50
 * DNS-запросов; из двух попыток берётся лучшая.
 */
fun tcpPing(host: String, port: Int, attempts: Int = 2): Int? {
    if (host.isBlank() || port !in 1..65535) return null
    val ip = resolveCached(host) ?: return null
    var best: Int? = null
    repeat(attempts.coerceIn(1, 4)) {
        val ms = runCatching {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                ((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(1)
            }
        }.getOrNull() ?: return@repeat
        if (best == null || ms < best!!) best = ms
        // Первая же быстрая попытка — дальше незачем, пользователь ждёт ответа.
        if (best!! <= FAST_ENOUGH_MS) return best
    }
    return best
}

private const val CONNECT_TIMEOUT_MS = 1200
private const val IP_TTL_MS = 10L * 60L * 1000L
private const val FAST_ENOUGH_MS = 120

private data class CachedIp(val at: Long, val ip: InetAddress)

private val ipCache = HashMap<String, CachedIp>()
private val ipLock = Any()

/** Резолв с кэшем: IPv4 в приоритете, AAAA на мобильных сетях часто не ходит. */
internal fun resolveCached(host: String): InetAddress? {
    val now = System.currentTimeMillis()
    synchronized(ipLock) {
        val hit = ipCache[host]
        if (hit != null && now - hit.at < IP_TTL_MS) return hit.ip
    }
    val ip = pickIp(host) ?: return null
    synchronized(ipLock) { ipCache[host] = CachedIp(now, ip) }
    return ip
}

private fun pickIp(host: String): InetAddress? {
    // Литерал адреса резолвить незачем.
    val literal = runCatching { InetAddress.getByName(host) }.getOrNull()
    if (literal != null && host.count { c -> c == '.' } == 3) return literal
    val all = runCatching { InetAddress.getAllByName(host).toList() }.getOrNull() ?: return null
    val v4 = all.filterIsInstance<Inet4Address>()
    return (if (v4.isNotEmpty()) v4 else all).firstOrNull()
}

/** Сбросить кэш адресов — при смене сети, чтобы не держать старые IP. */
fun clearPingCaches() {
    synchronized(ipLock) { ipCache.clear() }
}


