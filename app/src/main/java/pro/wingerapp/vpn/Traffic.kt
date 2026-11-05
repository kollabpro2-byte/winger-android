package pro.wingerapp.vpn

import org.json.JSONObject
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

fun parseCoreTraffic(raw: String): Pair<Long, Long> {
    if (raw.isBlank()) return 0L to 0L
    var up = 0L
    var down = 0L
    val obj = runCatching { JSONObject(raw) }.getOrNull()
    if (obj != null) {
        for (key in obj.keys()) {
            val value = when (val v = obj.opt(key)) {
                is Number -> v.toLong()
                is JSONObject -> v.optLong("uplink", v.optLong("up", 0)) to v.optLong("downlink", v.optLong("down", 0))
                else -> null
            }
            when (value) {
                is Long -> {
                    if (key.contains("uplink") || key.endsWith("up")) up += value
                    if (key.contains("downlink") || key.endsWith("down")) down += value
                }
                is Pair<*, *> -> {
                    up += value.first as Long
                    down += value.second as Long
                }
            }
        }
        if (up + down > 0) return down to up
    }
    Regex("uplink[^0-9]{0,12}(\\d+)").findAll(raw).forEach { up += it.groupValues[1].toLong() }
    Regex("downlink[^0-9]{0,12}(\\d+)").findAll(raw).forEach { down += it.groupValues[1].toLong() }
    return down to up
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
