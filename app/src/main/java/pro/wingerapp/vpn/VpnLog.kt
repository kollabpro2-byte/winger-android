package pro.wingerapp.vpn

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VpnLog {
    private lateinit var file: File

    /** Сколько живёт лог: раз в час он очищается целиком. */
    private const val ROTATE_MS = 60L * 60L * 1000L

    /** Предел размера между ротациями. */
    private const val MAX_BYTES = 64_000L

    /** Сколько последних строк оставлять при подрезке по размеру. */
    private const val KEEP_LINES = 200

    private var lastRotate = System.currentTimeMillis()

    @Synchronized
    fun init(ctx: Context) {
        try {
            file = File(ctx.filesDir, "vpn.log")
            lastRotate = System.currentTimeMillis()
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun add(line: String) {
        try {
            if (!::file.isInitialized) return
            rotateIfDueLocked()
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val body = line.replace("\r\n", "\n").replace('\r', '\n')
            val formatted = body.lineSequence().mapIndexed { i, part ->
                if (i == 0) "$ts $part" else "         $part"
            }.joinToString("\n")
            file.appendText(formatted + "\n")
            if (file.length() > MAX_BYTES) {
                val lines = file.readLines()
                file.writeText(lines.takeLast(KEEP_LINES).joinToString("\n") + "\n")
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Часовая ротация. Вызывается из каждой записи и из heartbeat сервиса:
     * лог не должен расти сутками и занимать память устройства.
     */
    @Synchronized
    fun rotateIfDue() {
        try {
            if (::file.isInitialized) rotateIfDueLocked()
        } catch (_: Throwable) {
        }
    }

    private fun rotateIfDueLocked() {
        val now = System.currentTimeMillis()
        if (now - lastRotate < ROTATE_MS) return
        lastRotate = now
        file.writeText("")
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
            if (::file.isInitialized) {
                file.writeText("")
                lastRotate = System.currentTimeMillis()
            }
        } catch (_: Throwable) {
        }
    }

    /** Подрезает файл лога ядра: он пишется вне нашего контроля и растёт молча. */
    @Synchronized
    fun pruneCoreLog(coreFile: File?, maxBytes: Long = 1_500_000L) {
        try {
            if (coreFile == null || !coreFile.exists()) return
            if (coreFile.length() <= maxBytes) return
            val kb = coreFile.length() / 1024
            // Ядро держит файл в режиме append, поэтому обнуление безопасно:
            // следующая запись снова начнётся с начала файла.
            coreFile.writeText("")
            add("лог ядра очищен (было $kb КБ)")
        } catch (_: Throwable) {
        }
    }
}
