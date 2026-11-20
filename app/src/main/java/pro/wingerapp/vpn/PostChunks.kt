package pro.wingerapp.vpn

import java.io.ByteArrayOutputStream

/**
 * Транспорт «POST↔POST chunks» для Selectel CDN (LTE 5).
 *
 * Зачем: Selectel CDN буферизует или рвёт длинный GET, поэтому обычный xhttp
 * (аплинк POST, даунлинк длинный GET) там висит и обрывает большие ответы.
 * Здесь в обе стороны идут только **завершённые короткие POST** с телом и
 * ответом — HTTP-прокси CDN такое пропускает.
 *
 * Кадрирование совместимо с framed LTE 4: `[0x01, тип, seq u32 BE, payload]`,
 * тип 0x00 — DATA, 0x01 — PING. Ответ на POST — ноль или больше кадров подряд;
 * пустой ответ значит «данных нет».
 *
 * Всё, что здесь есть, — чистая логика без сети: политика кусков, сборка тела,
 * разбор ответа и расписание повторов. Это позволяет проверять формат тестами,
 * пока серверная половина не готова.
 */
internal object PostChunks {

    /** Максимум тела одного POST: больше CDN/nginx не примут. */
    const val MAX_BODY = 1024 * 1024

    /** Заголовок паддинга: query Selectel режет, поэтому только header. */
    const val PADDING_HEADER = "X-Padding"

    /** Заголовок подтверждения даунлинка: последний непрерывно полученный seq. */
    const val ACK_HEADER = "X-Ack"

    /** Заголовок идентификатора сессии. */
    const val SESSION_HEADER = "X-Session"

    /** Сколько ждать ответ на POST с данными: сервер держит до 80 мс, запас большой. */
    const val DATA_TIMEOUT_MS = 15_000L

    /** Poll: 2 с плюс long-poll на сервере (250–500 мс). */
    const val POLL_TIMEOUT_MS = 2_500L

    /** Сколько минимум и максимум байт паддинга (по спеке 64–192). */
    val PADDING_RANGE = 64..192

    /**
     * Политика обмена. Значения по умолчанию — из спеки: кусок 128 КБ (старт
     * между 64 и 256), пауза 30 мс (внутри 15–50), два параллельных POST.
     */
    data class Policy(
        val chunkBytes: Int = 128 * 1024,
        val pauseMs: Long = 30,
        val parallel: Int = 2,
    ) {
        init {
            require(chunkBytes in 1024..MAX_BODY) { "chunkBytes вне 1..1 МБ" }
        }

        companion object {
            /** Разбирает значения из подписки, подрезая их в допустимые рамки. */
            fun from(
                chunkBytes: Int?,
                pauseMs: Long?,
                parallel: Int?,
            ): Policy = Policy(
                chunkBytes = (chunkBytes ?: 128 * 1024).coerceIn(16 * 1024, 256 * 1024),
                pauseMs = (pauseMs ?: 30L).coerceIn(0L, 500L),
                parallel = (parallel ?: 2).coerceIn(1, 4),
            )
        }
    }

    /** Тело одного POST для куска данных. */
    fun uplinkData(seq: Long, chunk: ByteArray): ByteArray = Lte4.encode(Lte4.TYPE_DATA, seq, chunk)

    /** Тело PING: тот же кадр без payload, seq любой (сервер игнорирует). */
    fun uplinkPing(): ByteArray = Lte4.encode(Lte4.TYPE_PING, 0)

    /** Тело CLOSE: клиент закрывает VLESS-поток, сервер закрывает сессию. */
    fun uplinkClose(): ByteArray = Lte4.encode(Lte4.TYPE_CLOSE, 0)

    /**
     * Разбирает тело ответа на кадры.
     *
     * Формат совместим с framed LTE 4: у кадра нет поля длины, payload идёт до
     * конца сообщения. Поэтому **один POST несёт один кадр** — серверу, чтобы
     * отдать два куска, нужно ответить двумя POST'ами. Так формат остаётся тем
     * же, что у WS, и не приходится придумывать префикс длины, который сервер
     * не знает.
     */
    fun decodeResponse(body: ByteArray): List<Lte4.Frame> {
        if (body.size < Lte4.HEADER) return emptyList()
        return listOfNotNull(Lte4.decode(body))
    }

    /**
     * Сколько подождать перед следующим poll, когда данных не пришло:
     * 20 → 50 → 100 → 200 мс, дальше потолок. Сбрасывается в ноль, как только
     * данные появились.
     */
    fun pollDelayMs(emptyPolls: Int): Long = when {
        emptyPolls <= 0 -> FIRST_POLL_MS
        emptyPolls == 1 -> 50L
        emptyPolls == 2 -> 100L
        else -> MAX_POLL_MS
    }

    private const val FIRST_POLL_MS = 20L
    private const val MAX_POLL_MS = 200L

    /** Паддинг случайной длины в диапазоне спеки, base64url без хвостов. */
    fun paddingHeaderValue(random: java.util.Random): String {
        val size = PADDING_RANGE.first + random.nextInt(PADDING_RANGE.last - PADDING_RANGE.first + 1)
        val bytes = ByteArray(size).also(random::nextBytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Идентификатор сессии: 16 байт base62-подобной строкой. */
    fun sessionId(random: java.util.Random): String {
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val out = StringBuilder(22)
        repeat(22) { out.append(alphabet[random.nextInt(alphabet.length)]) }
        return out.toString()
    }

    /**
     * Режет поток на куски не больше [Policy.chunkBytes]. Последний кусок может
     * быть короче; пустой вход — пустой список (нечего слать).
     */
    fun split(chunk: ByteArray, policy: Policy): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (chunk.size <= policy.chunkBytes) return listOf(chunk)
        val out = ArrayList<ByteArray>(chunk.size / policy.chunkBytes + 1)
        var offset = 0
        while (offset < chunk.size) {
            val size = minOf(policy.chunkBytes, chunk.size - offset)
            out += chunk.copyOfRange(offset, offset + size)
            offset += size
        }
        return out
    }

    /** Склеивает payload'ы кадров в один массив — для записи в локальный сокет. */
    fun joinPayloads(frames: List<Lte4.Frame>): ByteArray {
        val out = ByteArrayOutputStream()
        for (frame in frames) {
            if (frame.type == Lte4.TYPE_PING) continue
            out.write(frame.payload)
        }
        return out.toByteArray()
    }
}

/**
 * Параметры режима, которые ядро передаёт прослойке в заголовках локального
 * WS-запроса. Так подписка задаёт поведение, не заставляя приложение угадывать:
 * `X-Post-Chunks: 1` включает режим, остальные заголовки уточняют политику.
 */
internal object PostChunkParams {
    const val HEADER_ENABLED = "x-post-chunks"
    private const val HEADER_CHUNK = "x-post-chunk-bytes"
    private const val HEADER_PAUSE = "x-post-pause-ms"
    private const val HEADER_PARALLEL = "x-post-parallel"
    private const val HEADER_PADDING = "x-padding"

    /** Хост Selectel CDN: `*.selcdn.net`. */
    fun isPostChunksHost(host: String): Boolean {
        val h = host.trim().lowercase().substringBefore(':')
        return h == "selcdn.net" || h.endsWith(".selcdn.net")
    }

    /**
     * Режим из подписки: сервер помечает узел `mode: "post-chunks"`. Обычные
     * клиенты такой режим не поймут, поэтому хост и держат скрытым; флаг
     * `postChunks` в `extra` тоже принимаем — он был в первой версии.
     */
    fun isPostChunksMode(xhttpMode: String, extra: Map<String, Any?>): Boolean {
        val mode = xhttpMode.trim().lowercase()
        if (mode == "post-chunks") return true
        if (extra["postChunks"] == true) return true
        return (extra["wingerTransport"] as? String)?.trim()?.lowercase() == "post-chunks"
    }

    fun isEnabled(headers: Map<String, String>): Boolean =
        headers[HEADER_ENABLED]?.trim() == "1"

    fun policy(headers: Map<String, String>): PostChunks.Policy = PostChunks.Policy.from(
        headers[HEADER_CHUNK]?.trim()?.toIntOrNull(),
        headers[HEADER_PAUSE]?.trim()?.toLongOrNull(),
        headers[HEADER_PARALLEL]?.trim()?.toIntOrNull(),
    )

    fun padding(headers: Map<String, String>): Boolean = headers[HEADER_PADDING]?.trim() != "0"
}

