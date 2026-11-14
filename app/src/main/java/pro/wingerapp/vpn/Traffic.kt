package pro.wingerapp.vpn

import android.net.TrafficStats
import android.os.Process

/**
 * Байты туннеля для строки «↑ / ↓».
 *
 * `/proc/net/dev` приложениям недоступен с Android 10, поэтому раньше счётчики
 * всегда были нулями. Считаем по своему UID: ядро sing-box работает в нашем
 * процессе, значит его соединения с сервером — это и есть трафик туннеля.
 * `first` = получено (rx), `second` = отдано (tx), как и раньше.
 */
fun tunTraffic(): Pair<Long, Long>? {
    val uid = Process.myUid()
    val rx = TrafficStats.getUidRxBytes(uid)
    val tx = TrafficStats.getUidTxBytes(uid)
    if (rx < 0 || tx < 0) return null
    return rx to tx
}

fun fmtBytes(n: Long): String {
    val v = n.coerceAtLeast(0)
    return when {
        v < 1024 -> "$v Б"
        v < 1024 * 1024 -> "%.1f КБ".format(v / 1024.0).replace('.', ',')
        v < 1024L * 1024 * 1024 -> "%.1f МБ".format(v / 1024.0 / 1024).replace('.', ',')
        else -> "%.2f ГБ".format(v / 1024.0 / 1024 / 1024).replace('.', ',')
    }
}
