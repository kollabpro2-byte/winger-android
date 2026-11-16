package pro.wingerapp.vpn

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VpnLog {
    private lateinit var file: File

    /** Сколько живёт лог по умолчанию: 30 минут (настраивается в настройках). */
    private const val DEFAULT_PERIOD_MS = 30L * 60L * 1000L

    /** Текущий период ротации; меняется из настроек. */
    @Volatile
    private var periodMs = DEFAULT_PERIOD_MS

    /** Предел размера между ротациями. */
    private const val MAX_BYTES = 64_000L

    /** Сколько последних строк оставлять при подрезке по размеру. */
    private const val KEEP_LINES = 200

    private var lastRotate = System.currentTimeMillis()

    /** Период очистки журнала в минутах: 30 по умолчанию, максимум 120. */
    @Synchronized
    fun setPeriodMinutes(minutes: Int) {
        periodMs = minutes.coerceIn(5, 120) * 60_000L
    }

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
        // Раньше запись шла в вызывающем потоке — а вызывают её из UI (нажатие на
        // круг, остановка туннеля). Один поток-писатель убирает файловые операции
        // с главного потока и сохраняет порядок строк.
        writer.execute { addNow(line) }
    }

    private fun addNow(line: String) {
        synchronized(this) {
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
    }

    /**
     * Часовая ротация. Вызывается из каждой записи и из heartbeat сервиса:
     * лог не должен расти сутками и занимать память устройства.
     */
    fun rotateIfDue() {
        writer.execute {
            synchronized(this) {
                try {
                    if (::file.isInitialized) rotateIfDueLocked()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun rotateIfDueLocked() {
        val now = System.currentTimeMillis()
        if (now - lastRotate < periodMs) return
        lastRotate = now
        file.writeText("")
    }

    /** Читать нужно из фонового потока: файл может быть до 64 КБ. */
    @Synchronized
    fun text(): String = try {
        if (::file.isInitialized && file.exists()) file.readText().takeLast(12_000) else "лога нет"
    } catch (_: Throwable) {
        "лог недоступен"
    }

    fun clear() {
        writer.execute {
            synchronized(this) {
                try {
                    if (::file.isInitialized) {
                        file.writeText("")
                        lastRotate = System.currentTimeMillis()
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    /**
     * Единственный поток-писатель: все файловые операции с логом идут здесь, а не
     * в вызывающем потоке. `add()` вызывают из UI (нажатие на круг, остановка
     * туннеля), и синхронный `appendText` там давал заметные подвисания.
     */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vpn-log").apply { isDaemon = true }
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
