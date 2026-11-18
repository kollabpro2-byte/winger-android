package pro.wingerapp.vpn

import java.nio.ByteBuffer

/**
 * Бинарный формат кадров прослойки «LTE4 shim».
 *
 * ```
 * байт 0      версия 0x01
 * байт 1      тип: 0x00 DATA, 0x01 PING
 * байты 2..5  seq, uint32 big-endian (с 0, +1 на каждый DATA в соединении)
 * дальше      payload (только у DATA)
 * ```
 *
 * Шлюз Яндекса пересылает сообщения параллельно и вразнобой, поэтому порядок
 * восстанавливает принимающая сторона по seq — см. [ReorderBuffer].
 */
internal object Lte4 {
    const val VERSION: Int = 0x01
    const val TYPE_DATA: Int = 0x00
    const val TYPE_PING: Int = 0x01

    /**
     * Предел payload одного DATA. У шлюза два ограничения: сообщение до 128 КБ и
     * кадр до 32 КБ, а OkHttp не умеет фрагментировать исходящий кадр сам,
     * поэтому режем на 32 КБ и ставим каждому куску свой seq.
     */
    const val MAX_CHUNK: Int = 32 * 1024

    /** Заголовок кадра: версия, тип и seq. */
    const val HEADER: Int = 6

    data class Frame(val type: Int, val seq: Long, val payload: ByteArray)

    fun encode(type: Int, seq: Long, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteBuffer.allocate(HEADER + payload.size)
        out.put(VERSION.toByte())
        out.put(type.toByte())
        out.putInt(seq.toInt())
        out.put(payload)
        return out.array()
    }

    /** Возвращает null, если кадр короче заголовка, чужая версия или неизвестный тип. */
    fun decode(bytes: ByteArray): Frame? {
        if (bytes.size < HEADER) return null
        if (bytes[0].toInt() and 0xFF != VERSION) return null
        val type = bytes[1].toInt() and 0xFF
        if (type != TYPE_DATA && type != TYPE_PING) return null
        val seq = ByteBuffer.wrap(bytes, 2, 4).int.toLong() and 0xFFFFFFFFL
        val payload = if (bytes.size > HEADER) bytes.copyOfRange(HEADER, bytes.size) else ByteArray(0)
        return Frame(type, seq, payload)
    }

    /** Признак шлюза Яндекса: по нему сервер получает прослойку, остальные — как раньше. */
    fun isLte4Gateway(host: String): Boolean {
        val h = host.trim().lowercase()
        return h == "apigw.yandexcloud.net" || h.endsWith(".apigw.yandexcloud.net")
    }

    /** Прослойка выключена: ядро ходит в шлюз напрямую, как Xray. */
    const val MODE_OFF: Int = 0

    /**
     * Байты не меняются, но после каждого сообщения уходит пустое: шлюз
     * придерживает сообщение до прихода следующего, и пустое его выталкивает.
     */
    const val MODE_FLUSH: Int = 1

    /**
     * Кадры с версией, типом и seq. Требует такой же разбор на стороне сервера
     * за шлюзом — без него поток превращается в мусор.
     */
    const val MODE_FRAMED: Int = 2

    fun modeName(mode: Int): String = when (mode) {
        MODE_OFF -> "выкл"
        MODE_FLUSH -> "флаш"
        MODE_FRAMED -> "кадры"
        else -> "?"
    }
}

/**
 * Что именно прослойка отправляет в шлюз на один кусок потока.
 *
 * Вынесено из сетевого кода, чтобы поведение режимов проверялось тестами:
 * ошибка здесь стоит дорого — ядро видит «ядро запущено, а трафика нет».
 */
internal object Lte4Wire {

    /** Сообщения для шлюза: в «кадровом» режиме это кадр, иначе — сам кусок. */
    fun outbound(mode: Int, seq: Long, chunk: ByteArray): List<ByteArray> = when (mode) {
        Lte4.MODE_FRAMED -> listOf(Lte4.encode(Lte4.TYPE_DATA, seq, chunk))
        Lte4.MODE_FLUSH -> listOf(chunk, EMPTY)
        else -> listOf(chunk)
    }

    /** Периодический «толкач»: в кадрах — PING, во флаше — пустое сообщение. */
    fun keepAlive(mode: Int, seq: Long): ByteArray? = when (mode) {
        Lte4.MODE_FRAMED -> Lte4.encode(Lte4.TYPE_PING, seq)
        Lte4.MODE_FLUSH -> EMPTY
        else -> null
    }

    /** Нужно ли разбирать входящие кадры и восстанавливать порядок. */
    fun inboundIsFramed(mode: Int): Boolean = mode == Lte4.MODE_FRAMED

    private val EMPTY = ByteArray(0)
}

/**
 * Восстановление порядка DATA-кадров по seq.
 *
 * Шлюз может прислать seq 5 раньше 4 и «придержать» последний, пока не придёт
 * следующий. Держим пришедшие не по порядку и отдаём строго по возрастанию.
 */
internal class ReorderBuffer(private val maxBuffered: Int = 512) {
    private val pending = java.util.TreeMap<Long, ByteArray>()
    /** Когда кадр пришёл — по нему считаем, сколько он прождал «дыру» в seq. */
    private val arrivedAt = HashMap<Long, Long>()
    private var nextSeq = 0L

    /** Сколько раз кадр пришёл раньше предыдущего (счётчик для лога). */
    var reordered: Long = 0L
        private set

    /** Сколько кадров пришлось отбросить как дубли или мусор. */
    var dropped: Long = 0L
        private set

    /** Максимальная глубина буфера — видно, насколько сильно шлюз перемешивает. */
    var maxDepth: Int = 0
        private set

    /** Сколько прождал последний отданный кадр, мс. Видно «пачки» шлюза. */
    var lastWaitMs: Long = 0L
        private set

    /** Худшее ожидание за сессию, мс. */
    var maxWaitMs: Long = 0L
        private set

    /** Кладёт кадр и возвращает всё, что теперь можно отдать по порядку. */
    fun put(seq: Long, payload: ByteArray, nowMs: Long = System.currentTimeMillis()): List<ByteArray> {
        if (seq < nextSeq) {
            dropped++
            return emptyList()
        }
        if (seq > nextSeq) reordered++
        pending[seq] = payload
        arrivedAt[seq] = nowMs
        if (pending.size > maxDepth) maxDepth = pending.size
        if (pending.size > maxBuffered) {
            // Защита от «дыры»: если предыдущий кадр потерян, дальше ждать нечего.
            dropped += pending.size
            pending.clear()
            arrivedAt.clear()
            lastWaitMs = 0L
            nextSeq = seq + 1
            return listOf(payload)
        }
        val out = ArrayList<ByteArray>(4)
        while (true) {
            val head = pending.remove(nextSeq) ?: break
            val arrived = arrivedAt.remove(nextSeq) ?: nowMs
            lastWaitMs = (nowMs - arrived).coerceAtLeast(0L)
            if (lastWaitMs > maxWaitMs) maxWaitMs = lastWaitMs
            out += head
            nextSeq++
        }
        return out
    }
}
