package pro.wingerapp.vpn

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter

/** Process-wide Java crash hook. Safe to call from Application, Activity, and the VPN service. */
object CrashGuard {
    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                try {
                    val sw = StringWriter()
                    error.printStackTrace(PrintWriter(sw))
                    val stack = sw.toString().take(3000)
                    VpnLog.add("CRASH thread=${thread.name} ${error.javaClass.name}: ${error.message}\n$stack")
                } catch (_: Throwable) {
                }
                prev?.uncaughtException(thread, error)
            }
            installed = true
        }
    }
}

/** Previous-run core crash files and ApplicationExitInfo. */
object Diag {
    private const val PREFS = "vpn_diag"
    const val STDERR_MARK = "ПРОШЛЫЙ КРАШ ЯДРА (stderr)"
    const val CORE_MARK = "ПРОШЛЫЙ ЛОГ ЯДРА (sing-box)"

    fun harvestCrash(ctx: Context) {
        // Libbox.redirectStderr is not in the Java API. Libbox.setup calls it internally
        // and points debug.SetCrashOutput at workingPath/CrashReport-<source>.log.
        for (name in listOf(
            "CrashReport-.log",
            "CrashReport-.log.old",
            "CrashReport-winger.log",
            "CrashReport-winger.log.old",
            "stderr.log",
        )) {
            dumpAndClear(File(ctx.filesDir, name), STDERR_MARK, head = true)
        }
        val reports = File(ctx.filesDir, "crash_reports")
        if (!reports.isDirectory) return
        val logs = reports.walkTopDown()
            .filter { it.isFile && it.name == "go.log" }
            .sortedByDescending { it.lastModified() }
            .take(3)
            .toList()
        for (file in logs) dumpOnce(ctx, file, STDERR_MARK, head = true)
    }

    fun harvestCoreLog(ctx: Context, rotate: Boolean) {
        val file = File(ctx.filesDir, "singbox.log")
        if (rotate) dumpAndClear(file, CORE_MARK) else dumpOnce(ctx, file, CORE_MARK)
    }

    fun logExitReasons(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        logExitReasons30(ctx)
    }

    private fun logExitReasons30(ctx: Context) {
        try {
            val am = ctx.getSystemService(ActivityManager::class.java) ?: return
            val reasons = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5)
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getLong("exit_seen_ts", 0L)
            var max = seen
            for (info in reasons) {
                if (info.timestamp <= seen) continue
                if (info.timestamp > max) max = info.timestamp
                VpnLog.add(
                    "EXIT ${reasonName(info.reason)} " +
                        "status=${info.status} desc=${info.description ?: "-"} " +
                        "ts=${info.timestamp} importance=${info.importance} proc=${info.processName ?: "-"}"
                )
                if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    info.reason == ApplicationExitInfo.REASON_ANR
                ) {
                    val trace = readTrace(info)
                    if (!trace.isNullOrBlank()) VpnLog.add("EXIT trace:\n$trace")
                }
            }
            if (max > seen) prefs.edit().putLong("exit_seen_ts", max).apply()
        } catch (e: Throwable) {
            VpnLog.add("exit reasons: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "REASON_$reason"
    }

    private fun readTrace(info: ApplicationExitInfo): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            info.traceInputStream?.use { input ->
                val buf = ByteArray(4500)
                val n = input.read(buf)
                if (n <= 0) null else String(buf, 0, n, Charsets.UTF_8).take(4000)
            }
        } catch (e: Throwable) {
            "trace read fail: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun dumpAndClear(file: File, marker: String, head: Boolean = false) {
        try {
            if (!file.exists() || file.length() == 0L) return
            val body = if (head) readExcerpt(file) else readTail(file, 4000)
            VpnLog.add("$marker ${file.name} ${file.length()} байт\n$body")
            file.writeText("")
        } catch (e: Throwable) {
            VpnLog.add("$marker ${file.name} не прочитан: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun dumpOnce(ctx: Context, file: File, marker: String, head: Boolean = false) {
        try {
            if (!file.exists() || file.length() == 0L) return
            val key = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getStringSet("core_seen", emptySet())?.toMutableSet() ?: mutableSetOf()
            if (!seen.add(key)) return
            val body = if (head) readExcerpt(file) else readTail(file, 4000)
            VpnLog.add("$marker ${file.parentFile?.name}/${file.name} ${file.length()} байт\n$body")
            val capped = seen.toList().takeLast(40).toSet()
            prefs.edit().putStringSet("core_seen", HashSet(capped)).apply()
        } catch (e: Throwable) {
            VpnLog.add("$marker ${file.name} не прочитан: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun readTail(file: File, maxChars: Int): String {
        val len = file.length()
        if (len <= maxChars) return file.readText()
        return readAt(file, len - maxChars, maxChars)
    }

    /** Panic header is at the start of a Go crash dump; the tail is the other goroutines. */
    private fun readExcerpt(file: File): String {
        val headN = 6000
        val tailN = 1500
        val len = file.length()
        if (len <= headN + tailN) return file.readText()
        val head = readAt(file, 0, headN).trimEnd()
        val tail = readAt(file, len - tailN, tailN).trimStart()
        return "$head\n…($len байт, середина обрезана)…\n$tail"
    }

    private fun readAt(file: File, offset: Long, max: Int): String {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset.coerceAtLeast(0))
            val buf = ByteArray(max)
            val n = raf.read(buf)
            return if (n <= 0) "" else String(buf, 0, n, Charsets.UTF_8)
        }
    }
}

/** Drops repeated callback lines. Failures should bypass this. */
class LogRate {
    private val last = HashMap<String, Long>()

    @Synchronized
    fun allow(key: String, intervalMs: Long): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val prev = last[key] ?: 0L
        if (now - prev < intervalMs) return false
        last[key] = now
        return true
    }
}
