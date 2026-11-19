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
    /** Режим: кадры включены всегда, см. [Store.lte4Mode]. */
    private val mode: Int = Lte4.MODE_FRAMED,
    initialStreams: Int = 32,
    /** Потолок для этого устройства: до него поднимаемся, если всё работает. */
    private val maxStreams: Int = initialStreams,
    /** Куда сообщить изменённое число потоков, чтобы оно пережило перезапуск. */
    private val onStreamsChanged: (Int) -> Unit = {},
    private val protect: (Socket) -> Boolean,
) {

    /**
     * Сколько параллельных отправок вниз просить у релея (`f=1cN`). Меняется на
     * лету: если кадры не поднимаются, снижаем — телефон может не тянуть 32.
     */
    @Volatile
    private var streams: Int = initialStreams.coerceIn(1, 32)

    private var server: ServerSocket? = null

    @Volatile
    private var running = false

    /** Порт локального WS-сервера; 0, пока не запущен. */
    @Volatile
    var port: Int = 0
        private set

    private val live = AtomicInteger(0)

    /**
     * Активные батч-сессии. Один общий поток обходит их и выталкивает хвост по
     * расписанию — так на десятки соединений приходится один таймер, а не по
     * потоку на каждое (иначе сотни пробуждений в секунду и батарея).
     */
    private val tickers = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /**
     * Живые локальные сокеты и WS-соединения. Нужны, чтобы при выключении VPN
     * закрыть всё сразу: иначе висят потоки и сокеты, а ядро ждёт EOF вместо
     * мгновенного переподключения.
     */
    private val locals = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val websockets = java.util.concurrent.ConcurrentHashMap.newKeySet<WebSocket>()

    /** Отказы по потолку соединений: логируем редко, иначе шторм забьёт журнал. */
    private val refusals = AtomicInteger(0)

    /** Сколько раз подряд кадровое соединение не поднялось (таймаут или ошибка). */
    private val framedFails = AtomicInteger(0)

    /**
     * Кадры отключены до перезапуска сервиса: три неудачи подряд — и хост идёт
     * обычным /ws. Лучше рабочий туннель без ускорения, чем цикл переподключений.
     */
    @Volatile
    private var framedDisabled = false

    /**
     * Кадры не поднялись. Сначала снижаем число потоков (32 → 16 → 8): телефон
     * может не тянуть 32, но кадры ему вполне по силам. И только после шести
     * неудач уходим на обычный `/ws` — рабочий туннель важнее ускорения.
     */
    private fun noteFramedFailure() {
        val n = framedFails.incrementAndGet()
        if (n % 2 == 0 && streams > 8) {
            val next = if (streams >= 32) 16 else 8
            streams = next
            runCatching { onStreamsChanged(next) }
            VpnLog.add("LTE4 shim: кадры не поднялись ($n) — снижаю потоки до $next")
            return
        }
        if (n >= 6 && !framedDisabled) {
            framedDisabled = true
            VpnLog.add("LTE4 shim: кадры не поднялись $n раз — перехожу на обычный /ws")
        }
    }

    /**
     * Соединение отработало с реальным трафиком — можно вернуть потоки обратно:
     * телефон не «не тянет», а просто попал в плохой момент (нода лежала).
     * Поднимаем по одному шагу, чтобы не устроить качели.
     */
    private fun noteFramedSuccess(bytes: Long) {
        if (bytes < RAMP_BYTES || streams >= maxStreams) return
        val next = when {
            streams <= 8 -> 16
            else -> 32
        }.coerceAtMost(maxStreams)
        streams = next
        runCatching { onStreamsChanged(next) }
        VpnLog.add("LTE4 shim: соединение живое, поднимаю потоки до $next")
    }

    /**
     * Сокеты исходящего WSS обязательно защищаем: приложение исключено из
     * туннеля, но protect() — страховка от петли, если политики изменятся.
     */
    private val client = OkHttpClient.Builder()
        // IPv4 в приоритете. Половина «плавающих» обрывов на мобильных сетях —
        // это AAAA, который резолвится, но не маршрутизируется; ядро у нас тоже
        // по умолчанию берёт только A-записи.
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val all = okhttp3.Dns.SYSTEM.lookup(hostname)
                val v4 = all.filterIsInstance<java.net.Inet4Address>()
                return if (v4.isNotEmpty()) v4 else all
            }
        })
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
        VpnLog.add(
            "LTE4 shim слушает 127.0.0.1:$port, режим ${Lte4.modeName(mode)}" +
                if (mode == Lte4.MODE_FRAMED) ", потоков ${streams.coerceIn(1, 32)}" else ""
        )
        if (mode == Lte4.MODE_AUTO || mode == Lte4.MODE_FRAMED) startTicker()
        return port
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        port = 0
        tickers.clear()
        // Рвём всё, что осталось открытым: после выключения VPN ни сокеты, ни
        // потоки не должны жить своей жизнью.
        for (s in locals) runCatching { s.close() }
        locals.clear()
        for (ws in websockets) runCatching { ws.cancel() }
        websockets.clear()
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }

    /**
     * Один таймер на все батч-сессии. Тик 5 мс нужен, чтобы попадать в окно
     * 10–20 мс после пачки (релей держит хвост, если толкач придёт слишком
     * рано). Когда сессий нет — спит 120 мс, чтобы не будить процесс зря.
     */
    private fun startTicker() {
        thread(isDaemon = true, name = "lte4-auto") {
            while (running) {
                val busy = tickers.isNotEmpty()
                try {
                    Thread.sleep(if (busy) 5 else 120)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running) break
                for (tick in tickers) runCatching { tick() }
            }
        }
    }

    private fun serve(local: Socket) {
        // Страховка от шторма: ядро при неудаче переподключается десятки раз в
        // секунду, и без потолка это сотни потоков и гигабайты стека.
        if (live.get() >= MAX_LIVE) {
            val n = refusals.incrementAndGet()
            // Пишем только первый и каждый сотый отказ, иначе шторм за пару
            // секунд вытеснит из журнала всё полезное.
            if (n == 1 || n % 100 == 0) {
                VpnLog.add("LTE4 shim: отказ по потолку ($MAX_LIVE), всего отказов $n")
            }
            runCatching { local.close() }
            return
        }
        live.incrementAndGet()
        locals += local
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
            locals -= local
            runCatching { local.close() }
        }
    }

    private data class Handshake(val path: String, val host: String, val key: String)

    /** Состояние одного туннеля: открыт ли, какой следующий seq, когда были данные. */
    internal class ConnState {
        @Volatile var open = false
        @Volatile var lastDataAt = System.currentTimeMillis()
        /** Любая активность (отправка или приём) — по ней выбираем частоту PING. */
        @Volatile var lastActivityAt = System.currentTimeMillis()
        /** Когда последний раз уходил PING-толкач. */
        @Volatile var lastPingAt = 0L
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

        /**
         * Пауза между кадрами. По замерам релея: если слать сообщения вплотную,
         * шлюз склеивает пачку и держит всё, кроме первого, ~1,5 с — кадры
         * становились медленнее обычного режима. С паузой ≥5 мс склейки нет.
         */
        const val FRAME_GAP_MS = 6L

        /**
         * Payload одного кадра: 24 КБ — оптимум релея (4–7 МБ/с), и вместе с
         * 6-байтным заголовком не вылезаем за лимит шлюза в 32 КБ на кадр.
         */
        const val FRAME_PAYLOAD = 24 * 1024

        /**
         * Простой, после которого всё равно шлём PING/пустое. Шлюз закрывает
         * соединение через 10 минут тишины, NAT — заметно раньше; одно сообщение
         * в минуту дешевле, чем переподключение и потерянные запросы.
         */
        const val IDLE_KEEPALIVE_MS = 60_000L

        /** PING раз в секунду, пока канал активен — так просит спецификация. */
        const val PING_ACTIVE_MS = 1_000L

        /** Сколько после последней активности считаем канал «горячим». */
        const val ACTIVE_WINDOW_MS = 3_000L

        /**
         * Дырка в нумерации, после которой соединение пересоздаётся.
         * По спецификации релея пропусков не бывает: кадр либо доходит, либо
         * релей сам закрывает соединение, поэтому 15 с ожидания — это уже наша
         * потеря, и тянуть дальше нет смысла.
         */
        const val GAP_TIMEOUT_MS = 15_000L

        /** Сколько байт должно пройти в соединении, чтобы считать его рабочим. */
        const val RAMP_BYTES = 1_000_000L
    }

    /**
     * Подключает шлюз и гоняет трафик в обе стороны, пока одна из сторон не закроется.
     *
     * Режим определяет только то, что уходит и что разбирается: в «флаше» и
     * «выкл» поток байт не меняется, в «кадрах» добавляется заголовок и порядок.
     */
    private fun pumpBoth(local: Socket, input: InputStream, output: OutputStream, hs: Handshake) {
        val startedAt = System.currentTimeMillis()
        // Кадровый режим включается параметром в path: /ws?f=1c32. Если кадры
        // уже трижды не поднялись, этот хост идёт обычным /ws (как раньше), но
        // всё равно через прослойку — она лишь склеивает пачки и толкает хвост.
        val useFramed = mode == Lte4.MODE_FRAMED && !framedDisabled
        val path = if (useFramed) Lte4.framedPath(hs.path, streams) else hs.path
        val url = "wss://" + hs.host + path
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
        websockets += ws
        // Кадрам даём 5 секунд (по спецификации), обычному пути — 12: там TLS
        // поднимает уже сам шлюз и на плохой сети это дольше.
        val openTimeout = if (useFramed) 5L else 12L
        if (!opened.await(openTimeout, TimeUnit.SECONDS) || !state.open) {
            VpnLog.add("LTE4 shim: WSS не открылся за $openTimeout с")
            websockets -= ws
            runCatching { ws.cancel() }
            if (useFramed) noteFramedFailure()
            return
        }
        if (useFramed) framedFails.set(0)
        VpnLog.add(
            "LTE4 shim: WSS открыт, режим ${Lte4.modeName(mode)}, path $path"
        )

        // Батч-режимы: «авто» (сырые байты) и «кадры». Данные копятся в пачки, но
        // шлюзу они уходят с паузами: по замерам релея, если слать сообщения
        // вплотную, шлюз склеивает пачку и держит всё, кроме первого, ~1,5 с.
        // Именно это и делало кадры медленными — теперь кадры идут по одному,
        // не чаще раза в FRAME_GAP_MS.
        val batched = mode == Lte4.MODE_AUTO || mode == Lte4.MODE_FRAMED
        val framed = useFramed
        val upLock = Any()
        val autoUp = if (batched) AutoUplink(maxBatch = FRAME_PAYLOAD, idleMs = 15) else null
        val pusher = if (batched) TailPusher() else null
        val frameQueue = if (framed) java.util.ArrayDeque<ByteArray>() else null
        var lastFrameAt = -1_000_000L

        fun pusherMessage(): ByteString =
            if (framed) Lte4.encode(Lte4.TYPE_PING, 0).toByteString() else EMPTY.toByteString()

        fun sendPayloadLocked(payload: ByteArray): Boolean =
            ws.send(
                (if (framed) Lte4.encode(Lte4.TYPE_DATA, state.seq.getAndIncrement(), payload)
                else payload).toByteString()
            )

        /** «Авто»: пачка уходит сразу, задержка шлюза тут не копится. */
        fun sendPartsNowLocked(parts: List<ByteArray>) {
            var ok = true
            for (p in parts) {
                if (!sendPayloadLocked(p)) {
                    ok = false
                    break
                }
            }
            if (ok) ok = ws.send(pusherMessage())
            if (!ok) state.open = false else pusher?.onBatchSent(System.currentTimeMillis())
        }

        /** «Кадры»: по одному кадру на тик, чтобы шлюз не склеивал пачку. */
        fun pumpFramesLocked(now: Long): Boolean {
            val q = frameQueue ?: return false
            if (q.isEmpty()) return false
            if (now - lastFrameAt < FRAME_GAP_MS) return true
            if (!sendPayloadLocked(q.removeFirst())) {
                state.open = false
                return true
            }
            lastFrameAt = now
            if (q.isEmpty()) pusher?.onBatchSent(now)
            return true
        }

        fun deliverLocked(parts: List<ByteArray>) {
            val q = frameQueue
            if (q == null) {
                sendPartsNowLocked(parts)
            } else {
                q.addAll(parts)
                pumpFramesLocked(System.currentTimeMillis())
            }
        }

        val upForTick = autoUp
        val pushForTick = pusher
        val ticker: (() -> Unit)? = if (upForTick != null && pushForTick != null) {
            {
                synchronized(upLock) {
                    if (state.open) {
                        val now = System.currentTimeMillis()
                        // Дырка в нумерации или переполнение буфера: по спецификации
                        // релея пропусков не бывает, значит кадр потерян — соединение
                        // надо пересоздать, а не тянуть дальше кривой поток.
                        if (useFramed && (state.reorder.overflowed ||
                                state.reorder.isStalled(now, GAP_TIMEOUT_MS))
                        ) {
                            val why = if (state.reorder.overflowed) "переполнение буфера"
                            else "дырка в нумерации дольше ${GAP_TIMEOUT_MS / 1000} с"
                            VpnLog.add("LTE4 shim: $why — пересоздаю соединение")
                            state.open = false
                        } else
                        // Пока в очереди есть кадры, толкачи не нужны: хвост не ушёл.
                        if (!pumpFramesLocked(now)) {
                            val parts = upForTick.flushIfIdle(now)
                            if (parts.isNotEmpty()) {
                                deliverLocked(parts)
                            } else if (pushForTick.takeIfDue(now)) {
                                if (!ws.send(pusherMessage())) state.open = false
                                state.lastPingAt = now
                            } else {
                                // PING раз в секунду, пока канал активен, и раз в
                                // минуту в простое: держит NAT и простой шлюза.
                                val idle = now - state.lastActivityAt
                                val interval = if (idle < ACTIVE_WINDOW_MS) PING_ACTIVE_MS
                                else IDLE_KEEPALIVE_MS
                                if (now - state.lastPingAt >= interval) {
                                    if (!ws.send(pusherMessage())) state.open = false
                                    state.lastPingAt = now
                                }
                            }
                        }
                    }
                }
            }
        } else {
            null
        }
        if (ticker != null) tickers += ticker

        // «Флаш»: пустое сообщение после каждого куска — молоток, но надёжный.
        // Заодно продлевает простой шлюза (10 минут).
        if (mode == Lte4.MODE_FLUSH) {
            thread(isDaemon = true, name = "lte4-ping") {
                while (state.open && running) {
                    val idle = System.currentTimeMillis() - state.lastDataAt
                    Thread.sleep(if (idle < 3000) 300 else 5000)
                    if (!state.open || !running) break
                    if (!ws.send(EMPTY.toByteString())) break
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
                    if (autoUp != null && pusher != null) {
                        // Склейка: отправляем, только когда пачка набралась.
                        synchronized(upLock) {
                            pusher.onData()
                            val parts = autoUp.offer(chunk, System.currentTimeMillis())
                            if (parts.isNotEmpty()) deliverLocked(parts)
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
                    state.lastActivityAt = state.lastDataAt
                    state.outFrames++
                    state.sentBytes += chunk.size
                    offset += size
                }
            }
        } catch (_: Throwable) {
            // Обрыв любой стороны — закрываем обе.
        } finally {
            if (ticker != null) tickers -= ticker
            websockets -= ws
            if (useFramed && state.open) {
                noteFramedSuccess(state.sentBytes + state.recvBytes)
            }
            // Хвост, который ещё лежит в склейке или в очереди кадров, надо отдать:
            // иначе последний кусок запроса не дойдёт и страница/файл повиснут.
            if (autoUp != null && pusher != null && state.open) {
                val sentTail = synchronized(upLock) {
                    val rest = autoUp.drainNow()
                    val q = frameQueue
                    var any = rest.isNotEmpty()
                    if (any) {
                        if (q == null) sendPartsNowLocked(rest) else q.addAll(rest)
                    }
                    if (q != null && q.isNotEmpty()) {
                        // На закрытии паузы не нужны: отдаём остаток сразу.
                        while (q.isNotEmpty()) {
                            if (!sendPayloadLocked(q.removeFirst())) break
                        }
                        ws.send(pusherMessage())
                        any = true
                    }
                    any
                }
                if (sentTail) runCatching { Thread.sleep(120) }
            }
            state.open = false
            runCatching { ws.close(1000, "bye") }
            val ms = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
            val rate = state.sentBytes * 1000 / ms / 1024
            val batchedInfo = autoUp?.let {
                ", пачек ${it.batches}/по паузе ${it.idleFlushes}, толкачей ${pusher?.sent ?: 0}"
            } ?: ""
            VpnLog.add(
                "LTE4 shim: закрыто (${Lte4.modeName(mode)}) за $ms мс, ↑${state.outFrames}/" +
                    "${state.sentBytes} б (${rate} КБ/с) ↓${state.inFrames}/${state.recvBytes} б$batchedInfo"
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
        try {
            deliverToCore(bytes, output, state)
        } catch (_: Throwable) {
            // Локальный сокет закрылся (ядро ушло раньше шлюза). Гасим пару
            // аккуратно: исключение из колбэка OkHttp иначе валит соединение
            // грязно и с шумом в логе.
            state.open = false
        }
    }

    private fun deliverToCore(bytes: ByteArray, output: OutputStream, state: ConnState) {
        state.lastActivityAt = System.currentTimeMillis()
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
