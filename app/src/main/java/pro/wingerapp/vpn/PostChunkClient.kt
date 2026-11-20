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
 * Клиент транспорта «POST↔POST chunks»: короткие завершённые POST к CDN, ответ
 * читается сразу целиком. Никаких длинных GET и бесконечных потоковых POST —
 * именно на них ломается Selectel CDN.
 *
 * Один [exchange] — один HTTP-обмен: тело ≤ 1 МБ, ответ ≤ 1 МБ, таймаут 15 с.
 * Разбор ответа — [PostChunks.decodeResponse]: ноль или больше кадров.
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
     * Бросает при сетевой ошибке — вызывающий решает про переподключение.
     */
    fun exchange(body: ByteArray, timeoutMs: Long = EXCHANGE_TIMEOUT_MS): List<Lte4.Frame> {
        require(body.size <= PostChunks.MAX_BODY) { "тело POST больше 1 МБ" }
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(BINARY))
            .header(PostChunks.SESSION_HEADER, sessionId)
        if (sendPadding) {
            builder.header(PostChunks.PADDING_HEADER, PostChunks.paddingHeaderValue(random))
        }
        val request = builder.build()
        val perRequest = client.newBuilder()
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        perRequest.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code}")
            }
            val bytes = response.body?.bytes() ?: ByteArray(0)
            return PostChunks.decodeResponse(bytes)
        }
    }

    fun close() {
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }

    private companion object {
        const val EXCHANGE_TIMEOUT_MS = 15_000L
        val BINARY = "application/octet-stream".toMediaType()
    }
}
