package pro.wingerapp.vpn

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Локальная прослойка «LTE4 shim» между sing-box и Yandex API Gateway.
 *
 * Зачем: шлюз принимает WebSocket, но пересылает сообщения в наш сервер
 * параллельно и вразнобой, а следующее сообщение придерживает до прихода
 * следующего. VLESS — это байтовый поток, и перемешанные куски ломают его.
 * Прослойка нумерует каждый кусок (seq), добавляет PING, чтобы выталкивать
 * застрявшее, и восстанавливает порядок в обратную сторону.
 *
 * Схема:
 * ```
 * sing-box → ws://127.0.0.1:<port>  →  [shim]  →  wss://<домен шлюза>/<path>
 * ```
 * TLS делает прослойка, поэтому в конфиге ядра для таких серверов нет `tls`.
 */
internal class Lte4Shim(
    /** Режим из настроек: [Lte4.MODE_FLUSH] по умолчанию, см. [Lte4Wire]. */
    private val mode: Int = Lte4.MODE_FLUSH,
    private val protect: (Socket) -> Boolean,
) {

    private var server: ServerSocket? = null

    @Volatile
    private var running = false

    /** Порт локального WS-сервера; 0, пока не запущен. */
    @Volatile
    var port: Int = 0
        private set

    private val live = AtomicInteger(0)

    /**
     * Активные сессии режима «авто». Один общий поток обходит их и выталкивает
     * хвост по паузе — так на десятки соединений приходится один таймер, а не
     * по потоку на каждое (иначе сотни пробуждений в секунду и батарея).
     */
    private val autoSessions = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /**
     * Сокеты исходящего WSS обязательно защищаем: приложение исключено из
     * туннеля, но protect() — страховка от петли, если политики изменятся.
     */
    private val client = OkHttpClient.Builder()
        .socketFactory(object : javax.net.SocketFactory() {
            override fun createSocket(): Socket = Socket().also { protect(it) }
            override fun createSocket(host: String, port: Int): Socket =
                Socket(host, port).also { protect(it) }
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
                Socket(host, port, localHost, localPort).also { protect(it) }
            override fun createSocket(host: InetAddress, port: Int): Socket =
                Socket(host, port).also { protect(it) }
            override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
                Socket(address, port, localAddress, localPort).also { protect(it) }
        })
        // Свои PING живут в протоколе шлюза, штатный ping OkHttp не нужен.
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()

    /** Поднимает сервер на 127.0.0.1 и возвращает порт. Бросает, если порт не взят. */
    fun start(): Int {
        if (running) return port
        // Локальный сервер слушает петлю: трафик до sing-box не покидает устройство,
        // поэтому protect() ему не нужен — защищаем только исходящий WSS.
        val ss = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = ss
        port = ss.localPort
        running = true
        thread(isDaemon = true, name = "lte4-accept") {
            while (running) {
                val sock = try {
                    ss.accept()
                } catch (_: Throwable) {
                    break
                }
                thread(isDaemon = true, name = "lte4-conn") { serve(sock) }
            }
        }
        VpnLog.add("LTE4 shim слушает 127.0.0.1:$port, режим ${Lte4.modeName(mode)}")
        if (mode == Lte4.MODE_AUTO) startAutoScheduler()
        return port
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        port = 0
        autoSessions.clear()
    }

    /**
     * Один таймер на все «авто»-сессии. Тик 10 мс даёт задержку выталкивания
     * 25–35 мс, а когда сессий нет — спит 120 мс, чтобы не будить процесс зря.
     */
    private fun startAutoScheduler() {
        thread(isDaemon = true, name = "lte4-auto") {
            while (running) {
                val busy = autoSessions.isNotEmpty()
                try {
                    Thread.sleep(if (busy) 10 else 120)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running) break
                for (flush in autoSessions) runCatching { flush() }
            }
        }
    }

    private fun serve(local: Socket) {
        // Страховка от шторма: ядро при неудаче переподключается десятки раз в
        // секунду, и без потолка это сотни потоков и гигабайты стека.
        if (live.get() >= MAX_LIVE) {
            VpnLog.add("LTE4 shim: отказ, уже $MAX_LIVE соединений")
            runCatching { local.close() }
            return
        }
        live.incrementAndGet()
        try {
            local.tcpNoDelay = true
            local.soTimeout = 0
            val input = local.getInputStream()
            val output = local.getOutputStream()
            val request = readHandshake(input) ?: run {
                local.close(); return
            }
            val gateway = request.host
            if (!Lte4.isLte4Gateway(gateway)) {
                VpnLog.add("LTE4 shim: отказ, хост не шлюз Яндекса")
                local.close(); return
            }
            if (!writeHandshake(output, request.key)) {
                local.close(); return
            }
            pumpBoth(local, input, output, request)
        } catch (e: Throwable) {
            VpnLog.add("LTE4 shim: соединение закрыто (${e.javaClass.simpleName})")
        } finally {
            live.decrementAndGet()
            runCatching { local.close() }
        }
    }

    private data class Handshake(val path: String, val host: String, val key: String)

    /** Состояние одного туннеля: открыт ли, какой следующий seq, когда были данные. */
    internal class ConnState {
        @Volatile var open = false
        @Volatile var lastDataAt = System.currentTimeMillis()
        val seq = AtomicLong(0)
        var inFrames = 0L
        var outFrames = 0L
        var sentBytes = 0L
        var recvBytes = 0L

        /**
         * Свой буфер порядка на каждое соединение: seq начинается с 0 у каждой
         * новой сессии, поэтому общий буфер отбрасывал бы кадры второй сессии
         * как «старые».
         */
        val reorder = ReorderBuffer()
    }

    private fun readHandshake(input: InputStream): Handshake? {
        val buf = StringBuilder()
        val one = ByteArray(1)
        while (buf.length < 8192) {
            if (input.read(one) < 0) return null
            buf.append(one[0].toInt().toChar())
            if (buf.endsWith("\r\n\r\n")) break
        }
        val lines = buf.split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ') ?: return null
        if (requestLine.size < 2 || !requestLine[0].equals("GET", true)) return null
        val path = requestLine[1]
        var host = ""
        var key = ""
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            when (name) {
                "host" -> host = value.substringBefore(':')
                "sec-websocket-key" -> key = value
            }
        }
        if (host.isBlank() || key.isBlank()) return null
        return Handshake(path, host, key)
    }

    private fun writeHandshake(out: OutputStream, key: String): Boolean = try {
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray())
        )
        out.write(
            ("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray()
        )
        out.flush()
        true
    } catch (_: Throwable) {
        false
    }

    private fun InputStream.readFully(dst: ByteArray) {
        var offset = 0
        while (offset < dst.size) {
            val n = read(dst, offset, dst.size - offset)
            if (n < 0) throw java.io.EOFException()
            offset += n
        }
    }

    /** Собирает одно сообщение клиента (с учётом фрагментации), отвечает на ping. */
    private fun readClientMessage(input: InputStream, output: OutputStream): ByteArray? {
        var assembled: java.io.ByteArrayOutputStream? = null
        while (true) {
            val b0 = input.read()
            if (b0 < 0) return null
            val b1 = input.read()
            if (b1 < 0) return null
            val fin = b0 and 0x80 != 0
            val opcode = b0 and 0x0F
            val masked = b1 and 0x80 != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) {
                len = ((input.read() shl 8) or input.read()).toLong()
            } else if (len == 127L) {
                var v = 0L
                repeat(8) { v = (v shl 8) or input.read().toLong() }
                len = v
            }
            if (len < 0 || len > 1_048_576L) return null
            val mask = if (masked) ByteArray(4).also { input.readFully(it) } else null
            val payload = ByteArray(len.toInt()).also { input.readFully(it) }
            if (mask != null) {
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            when (opcode) {
                0x8 -> return null
                0x9 -> {
                    writeFrame(output, 0xA, payload)
                    continue
                }
                0xA -> continue
                0x2, 0x0 -> {
                    if (assembled == null) assembled = java.io.ByteArrayOutputStream()
                    assembled.write(payload)
                    if (fin) return assembled.toByteArray()
                }
                else -> continue
            }
        }
    }

    /** Сервер → клиент: без маски, длину пишем по RFC 6455. */
    private fun writeFrame(out: OutputStream, opcode: Int, payload: ByteArray) {
        out.write(0x80 or opcode)
        when {
            payload.size < 126 -> out.write(payload.size)
            payload.size < 65536 -> {
                out.write(126)
                out.write(payload.size shr 8)
                out.write(payload.size and 0xFF)
            }
            else -> {
                out.write(127)
                var len = payload.size.toLong()
                val bytes = ByteArray(8)
                for (i in 7 downTo 0) {
                    bytes[i] = (len and 0xFF).toByte()
                    len = len shr 8
                }
                out.write(bytes)
            }
        }
        out.write(payload)
        out.flush()
    }

    private companion object {
        const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** Потолок одновременных соединений через прослойку. */
        const val MAX_LIVE = 96

        /** Пустое WS-сообщение: им выталкиваем хвост пачки из шлюза. */
        val EMPTY = ByteArray(0)
    }

    /**
     * Подключает шлюз и гоняет трафик в обе стороны, пока одна из сторон не закроется.
     *
     * Режим определяет только то, что уходит и что разбирается: в «флаше» и
     * «выкл» поток байт не меняется, в «кадрах» добавляется заголовок и порядок.
     */
    private fun pumpBoth(local: Socket, input: InputStream, output: OutputStream, hs: Handshake) {
        val url = "wss://" + hs.host + hs.path
        val state = ConnState()
        val opened = CountDownLatch(1)
        val ws = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    state.open = true
                    opened.countDown()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    onGatewayMessage(bytes.toByteArray(), output, state)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // Код закрытия — главный признак: 1006 обрыв без close-кадра,
                    // 1011 шлюз/сервер упал, 4xxx — политика. Без него гадать бесполезно.
                    VpnLog.add("LTE4 shim: шлюз закрывает ${code}${reason.take(40).let { if (it.isBlank()) "" else " $it" }}")
                    state.open = false
                    runCatching { local.close() }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    state.open = false
                    VpnLog.add("LTE4 shim: закрыто шлюзом $code")
                    runCatching { local.close() }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    state.open = false
                    VpnLog.add(
                        "LTE4 shim: WSS ${t.javaClass.simpleName}" +
                            (response?.code?.let { " http $it" } ?: "")
                    )
                    opened.countDown()
                    runCatching { local.close() }
                }
            }
        )
        if (!opened.await(12, TimeUnit.SECONDS) || !state.open) {
            VpnLog.add("LTE4 shim: WSS не открылся за 12 с")
            runCatching { ws.cancel() }
            return
        }
        VpnLog.add("LTE4 shim: WSS открыт, режим ${Lte4.modeName(mode)}")

        // «Авто»: копим байты и выталкиваем хвост только когда пачка закрылась —
        // по размеру (32 КБ) или по паузе (25 мс). Порядок держит upLock: и поток
        // чтения, и планировщик отправляют из-под него, иначе сообщения могут
        // обогнать друг друга и поток VLESS развалится.
        val upLock = Any()
        val autoUp = if (mode == Lte4.MODE_AUTO) AutoUplink() else null
        fun sendBatchLocked(parts: List<ByteArray>) {
            var ok = true
            for (p in parts) {
                if (!ws.send(p.toByteString())) {
                    ok = false
                    break
                }
            }
            // Пустое сообщение выталкивает хвост пачки из шлюза.
            if (ok && !ws.send(EMPTY.toByteString())) ok = false
            if (!ok) state.open = false
        }
        val flusher: (() -> Unit)? = autoUp?.let { up ->
            {
                synchronized(upLock) {
                    if (state.open) {
                        val parts = up.flushIfIdle(System.currentTimeMillis())
                        if (parts.isNotEmpty()) sendBatchLocked(parts)
                    }
                }
            }
        }
        if (flusher != null) autoSessions += flusher

        // PING (или пустое сообщение) выталкивает застрявшее в шлюзе: 300 мс первые
        // 3 с после последних данных, дальше раз в 5 с — чтобы не закрылось по простою.
        if (Lte4Wire.keepAlive(mode, 0) != null) {
            thread(isDaemon = true, name = "lte4-ping") {
                while (state.open && running) {
                    val idle = System.currentTimeMillis() - state.lastDataAt
                    Thread.sleep(if (idle < 3000) 300 else 5000)
                    if (!state.open || !running) break
                    val ka = Lte4Wire.keepAlive(mode, state.seq.get()) ?: break
                    ws.send(ka.toByteString())
                }
            }
        }

        try {
            read@ while (state.open && running) {
                val message = readClientMessage(input, output) ?: break
                var offset = 0
                while (offset < message.size) {
                    val size = minOf(Lte4.MAX_CHUNK, message.size - offset)
                    val chunk = message.copyOfRange(offset, offset + size)
                    if (autoUp != null) {
                        // Склейка: отправляем, только когда пачка набралась.
                        synchronized(upLock) {
                            val parts = autoUp.offer(chunk, System.currentTimeMillis())
                            if (parts.isNotEmpty()) sendBatchLocked(parts)
                        }
                        if (!state.open) break@read
                    } else {
                        val seq = if (Lte4Wire.inboundIsFramed(mode)) state.seq.getAndIncrement() else 0L
                        // send() == false значит соединение уже закрывается: добивать
                        // остаток сообщения бессмысленно, выходим из чтения целиком.
                        var ok = true
                        for (part in Lte4Wire.outbound(mode, seq, chunk)) {
                            if (!ws.send(part.toByteString())) {
                                ok = false
                                break
                            }
                        }
                        if (!ok) break@read
                    }
                    state.lastDataAt = System.currentTimeMillis()
                    state.outFrames++
                    state.sentBytes += chunk.size
                    offset += size
                }
            }
        } catch (_: Throwable) {
            // Обрыв любой стороны — закрываем обе.
        } finally {
            if (flusher != null) autoSessions -= flusher
            // Хвост, который ещё лежит в склейке, надо отдать: иначе последний
            // кусок запроса не дойдёт и страница/файл повиснут.
            if (autoUp != null && state.open) {
                synchronized(upLock) {
                    val rest = autoUp.drainNow()
                    if (rest.isNotEmpty()) sendBatchLocked(rest)
                }
            }
            state.open = false
            runCatching { ws.close(1000, "bye") }
            val batched = autoUp?.let { ", пачек ${it.batches}/по паузе ${it.idleFlushes}" } ?: ""
            VpnLog.add(
                "LTE4 shim: закрыто (${Lte4.modeName(mode)}), ↑${state.outFrames}/${state.sentBytes} б " +
                    "↓${state.inFrames}/${state.recvBytes} б$batched"
            )
        }
    }

    /**
     * Сообщение от шлюза.
     *
     * «Кадры»: PING игнорируем, DATA ставим в очередь порядка и отдаём по seq.
     * «Флаш» и «выкл»: это уже готовые байты VLESS — отдаём как есть, порядок
     * не трогаем, иначе поток развалится.
     */
    private fun onGatewayMessage(bytes: ByteArray, output: OutputStream, state: ConnState) {
        if (!Lte4Wire.inboundIsFramed(mode)) {
            if (bytes.isEmpty()) return
            state.inFrames++
            state.recvBytes += bytes.size
            writeFrame(output, 0x2, bytes)
            return
        }
        val frame = Lte4.decode(bytes) ?: return
        if (frame.type == Lte4.TYPE_PING) return
        state.inFrames++
        state.recvBytes += frame.payload.size
        synchronized(state) {
            for (payload in state.reorder.put(frame.seq, frame.payload)) {
                var offset = 0
                while (offset < payload.size) {
                    val size = minOf(Lte4.MAX_CHUNK, payload.size - offset)
                    writeFrame(output, 0x2, payload.copyOfRange(offset, offset + size))
                    offset += size
                }
            }
        }
        if (state.inFrames % 400L == 0L) {
            VpnLog.add(
                "LTE4 shim: ↑${state.outFrames} ↓${state.inFrames}, " +
                    "переупорядочено ${state.reorder.reordered}, отброшено ${state.reorder.dropped}, " +
                    "буфер ${state.reorder.maxDepth}, " +
                    "ожидание ${state.reorder.lastWaitMs}/${state.reorder.maxWaitMs} мс"
            )
        }
    }

}
