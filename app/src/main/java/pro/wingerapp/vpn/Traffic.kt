package pro.wingerapp.vpn

import java.io.File

fun tunTraffic(): Pair<Long, Long>? {
    val text = runCatching { File("/proc/net/dev").readText() }.getOrNull() ?: return null
    var best: Pair<Long, Long>? = null
    for (line in text.lineSequence()) {
        val trimmed = line.trim()
        val name = trimmed.substringBefore(":").trim()
        if (!name.startsWith("tun")) continue
        val parts = trimmed.substringAfter(":").trim().split(Regex("\\s+"))
        if (parts.size < 9) continue
        val rx = parts[0].toLongOrNull() ?: continue
        val tx = parts[8].toLongOrNull() ?: continue
        if (best == null || rx + tx > best.first + best.second) best = rx to tx
    }
    return best
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
