package pro.wingerapp.vpn

import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Транспорт «POST↔POST chunks» (LTE 5 через Selectel CDN) — офлайн-часть:
 * формат кадра, сборка тела, разбор ответа, нарезка и расписание poll'ов.
 * Сеть здесь не участвует: серверный инбаунд появится позже.
 */
class PostChunksTest {

    @Test
    fun uplinkChunkIsOneDataFrame() {
        val payload = "hello".toByteArray()
        val body = PostChunks.uplinkData(7, payload)
        val frame = Lte4.decode(body)!!
        assertEquals(Lte4.TYPE_DATA, frame.type)
        assertEquals(7L, frame.seq)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun pingIsAFrameWithoutPayload() {
        val frame = Lte4.decode(PostChunks.uplinkPing())!!
        assertEquals(Lte4.TYPE_PING, frame.type)
        assertEquals(0, frame.payload.size)
    }

    /**
     * Ответ сервера — один кадр (у кадра нет длины, payload идёт до конца
     * сообщения: так же, как в WS). Пустой ответ значит «данных нет».
     */
    @Test
    fun responseIsASingleFrame() {
        val body = PostChunks.uplinkData(5, byteArrayOf(1, 2, 3))
        val frames = PostChunks.decodeResponse(body)
        assertEquals(1, frames.size)
        assertEquals(5L, frames[0].seq)
        assertArrayEquals(byteArrayOf(1, 2, 3), PostChunks.joinPayloads(frames))
    }

    @Test
    fun emptyResponseMeansNoData() {
        assertTrue(PostChunks.decodeResponse(ByteArray(0)).isEmpty())
    }

    /** Мусор и обрезанный заголовок не должны выглядеть как данные. */
    @Test
    fun shortOrForeignBodyIsIgnored() {
        assertTrue(PostChunks.decodeResponse(byteArrayOf(1, 2, 3)).isEmpty())
        assertTrue(PostChunks.decodeResponse(byteArrayOf(0x02, 0x00, 0, 0, 0, 1)).isEmpty())
        assertTrue(PostChunks.decodeResponse(byteArrayOf(0x01, 0x09, 0, 0, 0, 1)).isEmpty())
    }

    /** PING в ответе — служебный кадр: в туннель его payload не попадает. */
    @Test
    fun pingInResponseCarriesNoData() {
        val frames = PostChunks.decodeResponse(PostChunks.uplinkPing())
        assertEquals(1, frames.size)
        assertEquals(0, PostChunks.joinPayloads(frames).size)
    }

    @Test
    fun hugeIncomingChunkIsSplitIntoPolicySizedPieces() {
        val policy = PostChunks.Policy(chunkBytes = 64 * 1024, pauseMs = 30, parallel = 2)
        val chunk = ByteArray(160 * 1024) { (it % 251).toByte() }
        val parts = PostChunks.split(chunk, policy)
        assertEquals(listOf(64 * 1024, 64 * 1024, 32 * 1024), parts.map { it.size })
        assertArrayEquals(chunk, parts.reduce { acc, bytes -> acc + bytes })
    }

    @Test
    fun emptyChunkIsNotSent() {
        assertTrue(PostChunks.split(ByteArray(0), PostChunks.Policy()).isEmpty())
    }

    @Test
    fun policyIsClampedToTheSpecRanges() {
        val tooBig = PostChunks.Policy.from(chunkBytes = 8 * 1024 * 1024, pauseMs = 5_000, parallel = 64)
        assertEquals(256 * 1024, tooBig.chunkBytes)
        assertEquals(500L, tooBig.pauseMs)
        assertEquals(4, tooBig.parallel)

        val tooSmall = PostChunks.Policy.from(chunkBytes = 100, pauseMs = -1, parallel = 0)
        assertEquals(16 * 1024, tooSmall.chunkBytes)
        assertEquals(0L, tooSmall.pauseMs)
        assertEquals(1, tooSmall.parallel)

        val defaults = PostChunks.Policy.from(null, null, null)
        assertEquals(128 * 1024, defaults.chunkBytes)
        assertEquals(30L, defaults.pauseMs)
        assertEquals(2, defaults.parallel)
    }

    /** Пустые poll'ы расходятся 20 → 50 → 100 → 200 и упираются в потолок. */
    @Test
    fun emptyPollBackoffGrowsToItsCeiling() {
        assertEquals(20L, PostChunks.pollDelayMs(0))
        assertEquals(50L, PostChunks.pollDelayMs(1))
        assertEquals(100L, PostChunks.pollDelayMs(2))
        assertEquals(200L, PostChunks.pollDelayMs(3))
        assertEquals(200L, PostChunks.pollDelayMs(50))
    }

    @Test
    fun paddingHeaderMatchesTheSpec() {
        val random = Random(42)
        repeat(20) {
            val value = PostChunks.paddingHeaderValue(random)
            val bytes = java.util.Base64.getUrlDecoder().decode(value)
            assertTrue(bytes.size in PostChunks.PADDING_RANGE)
            assertTrue(!value.contains('='))
        }
    }

    @Test
    fun sessionIdIsRandomAndLongEnough() {
        val random = Random(7)
        val first = PostChunks.sessionId(random)
        val second = PostChunks.sessionId(random)
        assertEquals(22, first.length)
        assertTrue(first != second)
        assertTrue(first.all { it.isLetterOrDigit() })
    }

    @Test
    fun hostIsRecognisedOnlyForSelcdn() {
        assertTrue(PostChunkParams.isPostChunksHost("abc123.selcdn.net"))
        assertTrue(PostChunkParams.isPostChunksHost("selcdn.net"))
        assertTrue(PostChunkParams.isPostChunksHost("ABC.selcdn.net:443"))
        assertTrue(!PostChunkParams.isPostChunksHost("selcdn.net.evil.com"))
        assertTrue(!PostChunkParams.isPostChunksHost("ya5.wingertop.pro"))
    }

    @Test
    fun policyComesFromTheHandshakeHeaders() {
        val headers = mapOf(
            "x-post-chunks" to "1",
            "x-post-chunk-bytes" to "65536",
            "x-post-pause-ms" to "25",
            "x-post-parallel" to "2",
        )
        assertTrue(PostChunkParams.isEnabled(headers))
        val policy = PostChunkParams.policy(headers)
        assertEquals(65536, policy.chunkBytes)
        assertEquals(25L, policy.pauseMs)
        assertEquals(2, policy.parallel)
        assertTrue(PostChunkParams.padding(headers))

        // Без заголовков — значения по умолчанию из спеки.
        val defaults = PostChunkParams.policy(emptyMap())
        assertEquals(128 * 1024, defaults.chunkBytes)
        assertEquals(30L, defaults.pauseMs)
        assertTrue(!PostChunkParams.isEnabled(emptyMap()))
        assertTrue(!PostChunkParams.padding(mapOf("x-padding" to "0")))
    }

    /** Помеченный узел уходит в прослойку: ws на 127.0.0.1, без TLS. */
    @Test
    fun markedNodeGoesThroughTheShim() {
        val json = """
            [
              { "remarks": "lte5", "outbounds": [
                { "tag": "proxy", "protocol": "vless",
                  "settings": { "vnext": [ { "address": "abc.selcdn.net", "port": 443,
                    "users": [ { "id": "uuid-1" } ] } ] },
                  "streamSettings": { "network": "xhttp", "security": "tls",
                    "tlsSettings": { "serverName": "abc.selcdn.net" },
                    "xhttpSettings": { "path": "/some/path", "mode": "packet-up",
                      "extra": { "postChunks": true, "postChunkBytes": 65536,
                        "postPauseMs": 25, "postParallel": 2 } } } },
                { "tag": "direct", "protocol": "freedom" } ] }
            ]
        """.trimIndent()
        val sb = org.json.JSONObject(
            toSingBox(xrayConfigFor(json, "lte5"), TunnelOptions(shimPort = 45678))
        )
        val outbound = sb.getJSONArray("outbounds").getJSONObject(1)
        assertEquals("127.0.0.1", outbound.getString("server"))
        assertEquals(45678, outbound.getInt("server_port"))
        assertTrue(outbound.opt("tls") == null)
        val transport = outbound.getJSONObject("transport")
        assertEquals("ws", transport.getString("type"))
        assertEquals("/some/path", transport.getString("path"))
        val headers = transport.getJSONObject("headers")
        assertEquals("1", headers.getString("X-Post-Chunks"))
        assertEquals("65536", headers.getString("X-Post-Chunk-Bytes"))
        assertEquals("25", headers.getString("X-Post-Pause-Ms"))
    }

    /** Без пометки `postChunks` узел остаётся обычным xhttp: ничего не ломаем. */
    @Test
    fun unmarkedNodeKeepsPlainXhttp() {
        val json = """
            [
              { "remarks": "lte5", "outbounds": [
                { "tag": "proxy", "protocol": "vless",
                  "settings": { "vnext": [ { "address": "abc.selcdn.net", "port": 443,
                    "users": [ { "id": "uuid-1" } ] } ] },
                  "streamSettings": { "network": "xhttp", "security": "tls",
                    "tlsSettings": { "serverName": "abc.selcdn.net" },
                    "xhttpSettings": { "path": "/some/path", "mode": "packet-up" } } },
                { "tag": "direct", "protocol": "freedom" } ] }
            ]
        """.trimIndent()
        val sb = org.json.JSONObject(
            toSingBox(xrayConfigFor(json, "lte5"), TunnelOptions(shimPort = 45678))
        )
        val outbound = sb.getJSONArray("outbounds").getJSONObject(1)
        assertTrue(outbound.getString("server") != "127.0.0.1")
        assertEquals("xhttp", outbound.getJSONObject("transport").getString("type"))
        assertTrue(outbound.opt("multiplex") == null)
    }

    /**
     * Сервер помечает узел `mode: "post-chunks"` — так включает режим подписка.
     * Mux по умолчанию выключен: `smux` sing-box несовместим с Xray за релеем.
     */
    @Test
    fun postChunksModeGoesThroughTheShimWithoutMuxByDefault() {
        val json = """
            [
              { "remarks": "lte5", "outbounds": [
                { "tag": "proxy", "protocol": "vless",
                  "settings": { "vnext": [ { "address": "abc.selcdn.net", "port": 443,
                    "users": [ { "id": "uuid-1" } ] } ] },
                  "streamSettings": { "network": "xhttp", "security": "tls",
                    "tlsSettings": { "serverName": "abc.selcdn.net" },
                    "xhttpSettings": { "path": "/p", "mode": "post-chunks",
                      "extra": { "postChunkBytes": 65536, "postParallel": 2 } } } },
                { "tag": "direct", "protocol": "freedom" } ] }
            ]
        """.trimIndent()
        val sb = org.json.JSONObject(
            toSingBox(xrayConfigFor(json, "lte5"), TunnelOptions(shimPort = 45678))
        )
        val outbound = sb.getJSONArray("outbounds").getJSONObject(1)
        assertEquals("127.0.0.1", outbound.getString("server"))
        assertTrue(outbound.opt("tls") == null)
        assertTrue(outbound.opt("multiplex") == null)
        assertEquals("65536", outbound.getJSONObject("transport").getJSONObject("headers").getString("X-Post-Chunk-Bytes"))
    }

    /** `extra.postMux = "h2mux"` — совместимо с Xray mux.cool: меньше сессий. */
    @Test
    fun postMuxFromSubscriptionEnablesH2Mux() {
        val json = """
            [
              { "remarks": "lte5", "outbounds": [
                { "tag": "proxy", "protocol": "vless",
                  "settings": { "vnext": [ { "address": "abc.selcdn.net", "port": 443,
                    "users": [ { "id": "uuid-1" } ] } ] },
                  "streamSettings": { "network": "xhttp", "security": "tls",
                    "tlsSettings": { "serverName": "abc.selcdn.net" },
                    "xhttpSettings": { "path": "/p", "mode": "post-chunks",
                      "extra": { "postMux": "h2mux" } } } },
                { "tag": "direct", "protocol": "freedom" } ] }
            ]
        """.trimIndent()
        val sb = org.json.JSONObject(
            toSingBox(xrayConfigFor(json, "lte5"), TunnelOptions(shimPort = 45678))
        )
        val mux = sb.getJSONArray("outbounds").getJSONObject(1).getJSONObject("multiplex")
        assertTrue(mux.getBoolean("enabled"))
        assertEquals("h2mux", mux.getString("protocol"))
    }

    /**
     * Панель кладёт режим внутрь `extra` (`mode`, `wingerTransport`), а размер
     * куска — в `scMaxEachPostBytes`. Клиент должен понять оба и не превысить
     * 256 КБ, даже если сервер разрешает 1 МиБ.
     */
    @Test
    fun serverExtraSetsModeAndClampsChunkSize() {
        val json = """
            [
              { "remarks": "lte5", "outbounds": [
                { "tag": "proxy", "protocol": "vless",
                  "settings": { "vnext": [ { "address": "abc.selcdn.net", "port": 443,
                    "users": [ { "id": "uuid-1" } ] } ] },
                  "streamSettings": { "network": "xhttp", "security": "tls",
                    "tlsSettings": { "serverName": "abc.selcdn.net" },
                    "xhttpSettings": { "path": "/p", "mode": "packet-up",
                      "extra": { "wingerTransport": "post-chunks", "mode": "post-chunks",
                        "scMaxEachPostBytes": "1048576", "scMinPostsIntervalMs": "15-50",
                        "sessionHeader": "X-Session-Id" } } } },
                { "tag": "direct", "protocol": "freedom" } ] }
            ]
        """.trimIndent()
        val sb = org.json.JSONObject(
            toSingBox(xrayConfigFor(json, "lte5"), TunnelOptions(shimPort = 45678))
        )
        val outbound = sb.getJSONArray("outbounds").getJSONObject(1)
        assertEquals("127.0.0.1", outbound.getString("server"))
        val headers = outbound.getJSONObject("transport").getJSONObject("headers")
        assertEquals((256 * 1024).toString(), headers.getString("X-Post-Chunk-Bytes"))
        assertEquals("15", headers.getString("X-Post-Pause-Ms"))
    }

    @Test
    fun rangeLowerBoundReadsTheLowerEnd() {
        assertEquals(15L, rangeLowerBound("15-50"))
        assertEquals(30L, rangeLowerBound(" 30-60 "))
        assertEquals(100L, rangeLowerBound("100"))
        assertEquals(null, rangeLowerBound("abc"))
    }

    /**
     * QUIC (UDP/443) через POST-транспорт не проходит: видео YouTube просто не
     * начинается, а превью грузятся. Для такого узла правило обязано отбивать
     * QUIC, чтобы приложение перешло на TCP.
     */
    @Test
    fun quicIsRejectedOnlyForThePostChunksNode() {
        val postNode = """
            [
              { "remarks": "lte5", "outbounds": [
                { "tag": "proxy", "protocol": "vless",
                  "settings": { "vnext": [ { "address": "abc.selcdn.net", "port": 443,
                    "users": [ { "id": "uuid-1" } ] } ] },
                  "streamSettings": { "network": "xhttp", "security": "tls",
                    "tlsSettings": { "serverName": "abc.selcdn.net" },
                    "xhttpSettings": { "path": "/p", "mode": "post-chunks" } } },
                { "tag": "direct", "protocol": "freedom" } ] }
            ]
        """.trimIndent()
        val sb = org.json.JSONObject(
            toSingBox(xrayConfigFor(postNode, "lte5"), TunnelOptions(shimPort = 45678))
        )
        assertTrue(hasQuicReject(sb))

        // Обычный xhttp-узел: правило не добавляется, QUIC работает как раньше.
        val plainNode = postNode.replace("\"mode\": \"post-chunks\"", "\"mode\": \"packet-up\"")
        val plain = org.json.JSONObject(
            toSingBox(xrayConfigFor(plainNode, "lte5"), TunnelOptions(shimPort = 45678))
        )
        assertTrue(!hasQuicReject(plain))
    }

    private fun hasQuicReject(config: org.json.JSONObject): Boolean {
        val rules = config.getJSONObject("route").getJSONArray("rules")
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            if (rule.optString("network") == "udp" && rule.optInt("port", 0) == 443 &&
                rule.optString("action") == "reject"
            ) {
                return true
            }
        }
        return false
    }

    @Test
    fun dataTimeoutGrowsWithChunkSize() {
        // Мелкий кусок — базовые 5 с, без надбавки за скорость.
        assertEquals(PostChunks.DATA_TIMEOUT_MS, PostChunks.dataTimeoutMs(0))
        // 64 КБ — это ровно одна секунда по нижней планке 64 КБ/с.
        assertEquals(6_000L, PostChunks.dataTimeoutMs(64 * 1024))
        // Куска 256 КБ должно хватать на 4 с передачи сверх базовых 5 с.
        assertEquals(9_000L, PostChunks.dataTimeoutMs(256 * 1024))
    }
}
