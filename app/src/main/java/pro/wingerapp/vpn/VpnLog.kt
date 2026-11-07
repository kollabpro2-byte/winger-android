package pro.wingerapp.vpn

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VpnLog {
    private lateinit var file: File

    @Synchronized
    fun init(ctx: Context) {
        try {
            file = File(ctx.filesDir, "vpn.log")
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun add(line: String) {
        try {
            if (!::file.isInitialized) return
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val body = line.replace("\r\n", "\n").replace('\r', '\n')
            val formatted = body.lineSequence().mapIndexed { i, part ->
                if (i == 0) "$ts $part" else "         $part"
            }.joinToString("\n")
            file.appendText(formatted + "\n")
            if (file.length() > 64_000) {
                val lines = file.readLines()
                file.writeText(lines.takeLast(200).joinToString("\n") + "\n")
            }
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun text(): String = try {
        if (::file.isInitialized && file.exists()) file.readText().takeLast(12_000) else "лога нет"
    } catch (_: Throwable) {
        "лог недоступен"
    }

    @Synchronized
    fun clear() {
        try {
            if (::file.isInitialized) file.writeText("")
        } catch (_: Throwable) {
        }
    }
}
