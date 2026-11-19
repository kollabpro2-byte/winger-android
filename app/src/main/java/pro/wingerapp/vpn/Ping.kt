package pro.wingerapp.vpn

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP-пинг: время TCP-рукопожатия с конечным адресом сервера.
 *
 * Честность: измеряется только `connect`, DNS в замер не входит, ICMP не
 * используется (на мобильных сетях он часто зарезан). Скорость: адреса
 * кэшируются на 10 минут — иначе на подписке из 50 серверов каждый замер делает
 * 50 DNS-запросов; из двух попыток берётся лучшая.
 *
 * Мусорные адреса отбрасываются: `0.0.0.0`, `127.0.0.1`, link-local и multicast
 * принимают TCP мгновенно (0–1 мс), и именно из-за них «все пинги стали 1 мс».
 */
fun tcpPing(host: String, port: Int, attempts: Int = 2): Int? {
    if (host.isBlank() || port !in 1..65535) return null
    val ips = resolveCached(host)
    if (ips.isEmpty()) return null
    var best: Int? = null
    // До двух адресов одного имени: если один недоступен, второй может работать.
    for (ip in ips.take(2)) {
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
        if (best != null) return best
    }
    return best
}

private const val CONNECT_TIMEOUT_MS = 1200
private const val IP_TTL_MS = 10L * 60L * 1000L
private const val FAST_ENOUGH_MS = 120

private data class CachedIps(val at: Long, val ips: List<InetAddress>)

private val ipCache = HashMap<String, CachedIps>()
private val ipLock = Any()

/**
 * Годится ли адрес для замера. Служебные адреса отвечают на TCP мгновенно,
 * не выходя в сеть, и дают «1 мс» у любого сервера.
 */
internal fun isUsableAddress(addr: InetAddress): Boolean = !(
    addr.isAnyLocalAddress || addr.isLoopbackAddress ||
        addr.isLinkLocalAddress || addr.isMulticastAddress
    )

/**
 * Адрес, полученный по имени, не должен быть локальным: так выглядит подменённый
 * или заблокированный DNS ( captive portal, «домен заблокирован» ), и пинг до
 * такого адреса опять даст «1 мс» у нерабочего сервера.
 */
internal fun isSaneResolvedAddress(addr: InetAddress): Boolean =
    isUsableAddress(addr) && !addr.isSiteLocalAddress

/** Резолв с кэшем: IPv4 в приоритете, AAAA на мобильных сетях часто не ходит. */
internal fun resolveCached(host: String): List<InetAddress> {
    val now = System.currentTimeMillis()
    synchronized(ipLock) {
        val hit = ipCache[host]
        if (hit != null && now - hit.at < IP_TTL_MS) return hit.ips
    }
    val ips = pickIps(host)
    synchronized(ipLock) { ipCache[host] = CachedIps(now, ips) }
    return ips
}

private fun pickIps(host: String): List<InetAddress> {
    // Литерал адреса резолвить незачем, но проверить на служебность надо.
    val literal = runCatching { InetAddress.getByName(host) }.getOrNull()
    if (literal != null && host.count { c -> c == '.' } == 3) {
        return if (isUsableAddress(literal)) listOf(literal) else emptyList()
    }
    val all = runCatching { InetAddress.getAllByName(host).toList() }.getOrNull() ?: return emptyList()
    val usable = all.filter { isSaneResolvedAddress(it) }
    val v4 = usable.filterIsInstance<Inet4Address>()
    return (if (v4.isNotEmpty()) v4 else usable).distinct()
}

/** Сбросить кэш адресов — при смене сети, чтобы не держать старые IP. */
fun clearPingCaches() {
    synchronized(ipLock) { ipCache.clear() }
}



