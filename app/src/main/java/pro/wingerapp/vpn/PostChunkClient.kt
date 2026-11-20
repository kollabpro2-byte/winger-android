package pro.wingerapp.vpn

import java.net.InetAddress
import java.net.Socket
import java.util.Random
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Отказ сервера с кодом. `410` — сессия потеряна (сервер открыл бы новую
 * молча, поэтому это единственный способ узнать), `503` — лимит сессий или
 * перегрузка, ждать `Retry-After`.
 */
internal class PostChunkHttpException(
    val code: Int,
    val retryAfterMs: Long?,
) : java.io.IOException("HTTP $code")

/**
 * Клиент транспорта «POST↔POST chunks»: короткие завершённые POST к CDN, ответ
 * читается сразу целиком. Никаких длинных GET и бесконечных потоковых POST —
 * именно на них ломается Selectel CDN.
 *
 * Один [exchange] — один HTTP-обмен: тело ≤ 1 МБ, ответ ≤ 1 МБ. Заголовки:
 * `X-Session` (идентификатор сессии), `X-Ack` (последний непрерывно полученный
 * down seq — по нему сервер понимает, что можно забыть) и `X-Padding`.
 */
internal class PostChunkClient(
    private val url: String,
    private val sendPadding: Boolean = true,
    private val protect: (Socket) -> Boolean = { true },
    private val random: Random = Random(),
) {
    /** Идентификатор сессии: сервер может связать по нему POST'ы одного туннеля. */
    val sessionId: String = PostChunks.sessionId(random)

    private val client = OkHttpClient.Builder()
        .socketFactory(object : javax.net.SocketFactory() {
            override fun createSocket(): Socket = Socket().also { protect(it) }
            override fun createSocket(host: String, port: Int): Socket =
                Socket(host, port).also { protect(it) }
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
                Socket(host, port, localHost, localPort).also { protect(it) }
            override fun createSocket(host: InetAddress, port: Int): Socket =
                Socket(host, port).also { protect(it) }
            override fun createSocket(
                address: InetAddress,
                port: Int,
                localAddress: InetAddress,
                localPort: Int,
            ): Socket = Socket(address, port, localAddress, localPort).also { protect(it) }
        })
        // Короткие обмены: соединение не держим, каждый POST завершён.
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(EXCHANGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(EXCHANGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Один обмен: отправляет [body] и возвращает кадры ответа.
     *
     * [ack] — последний непрерывно полученный down seq; `-1` значит «ещё ничего
     * не получено», такой заголовок не отправляем. [timeoutMs] разный: у POST с
     * данными 15 с, у poll — 2.5 с (2 с плюс long-poll на сервере).
     *
     * Бросает [PostChunkHttpException] на отказ с кодом (410/503 важны) и
     * `IOException` на сетевую ошибку.
     */
    fun exchange(
        body: ByteArray,
        ack: Long = -1,
        timeoutMs: Long = PostChunks.DATA_TIMEOUT_MS,
    ): List<Lte4.Frame> {
        require(body.size <= PostChunks.MAX_BODY) { "тело POST больше 1 МБ" }
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(BINARY))
            .header(PostChunks.SESSION_HEADER, sessionId)
        if (ack >= 0) builder.header(PostChunks.ACK_HEADER, ack.toString())
        if (sendPadding) {
            builder.header(PostChunks.PADDING_HEADER, PostChunks.paddingHeaderValue(random))
        }
        val request = builder.build()
        val perRequest = client.newBuilder()
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        perRequest.newCall(request).execute().use { response ->
            when (val code = response.code) {
                // Сессия потеряна: сервер молча открыл бы новую с нуля — так
                // клиент хотя бы узнаёт и начинает нумерацию заново.
                410 -> throw PostChunkHttpException(code, null)
                // Лимит сессий или перегрузка: ждём столько, сколько сказали.
                503 -> throw PostChunkHttpException(code, retryAfterMs(response))
                204 -> return emptyList()
                else -> if (!response.isSuccessful) throw PostChunkHttpException(code, null)
            }
            val bytes = response.body?.bytes() ?: ByteArray(0)
            return PostChunks.decodeResponse(bytes)
        }
    }

    /** `Retry-After` в миллисекундах, если сервер его прислал (секунды). */
    private fun retryAfterMs(response: okhttp3.Response): Long? {
        val raw = response.header("Retry-After")?.trim() ?: return null
        val seconds = raw.toLongOrNull() ?: return null
        return (seconds * 1000L).coerceIn(0L, MAX_RETRY_AFTER_MS)
    }

    fun close() {
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }

    private companion object {
        const val EXCHANGE_TIMEOUT_MS = 15_000L
        /** Потолок `Retry-After`: больше ждать одного POST бессмысленно. */
        const val MAX_RETRY_AFTER_MS = 5_000L
        val BINARY = "application/octet-stream".toMediaType()
    }
}
