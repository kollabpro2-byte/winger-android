package pro.wingerapp.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Формат кадров прослойки и восстановление порядка — то, на чём держится LTE 4. */
class Lte4FramesTest {

    @Test
    fun dataFrameRoundTrip() {
        val payload = "hello".toByteArray()
        val bytes = Lte4.encode(Lte4.TYPE_DATA, 7, payload)
        assertEquals(Lte4.HEADER + payload.size, bytes.size)

        val frame = Lte4.decode(bytes)
        assertEquals(Lte4.TYPE_DATA, frame?.type)
        assertEquals(7L, frame?.seq)
        assertEquals("hello", frame?.payload?.toString(Charsets.UTF_8))
    }

    @Test
    fun pingCarriesNoPayload() {
        val frame = Lte4.decode(Lte4.encode(Lte4.TYPE_PING, 42))
        assertEquals(Lte4.TYPE_PING, frame?.type)
        assertEquals(42L, frame?.seq)
        assertEquals(0, frame?.payload?.size)
    }

    @Test
    fun seqAboveIntMaxSurvivesAsUnsigned() {
        val seq = 4_000_000_000L
        assertEquals(seq, Lte4.decode(Lte4.encode(Lte4.TYPE_DATA, seq, byteArrayOf(1)))?.seq)
    }

    @Test
    fun brokenOrForeignFramesAreIgnored() {
        assertNull(Lte4.decode(byteArrayOf(1, 2)))
        assertNull(Lte4.decode(byteArrayOf(0x02, 0x00, 0, 0, 0, 0)))
        assertNull(Lte4.decode(byteArrayOf(0x01, 0x09, 0, 0, 0, 0)))
    }

    @Test
    fun outOfOrderFramesAreDeliveredInSequence() {
        val buffer = ReorderBuffer()
        assertEquals(listOf("a"), buffer.put(0, "a".toByteArray()).map { String(it) })
        // 2 пришёл раньше 1 — отдать его нельзя.
        assertTrue(buffer.put(2, "c".toByteArray()).isEmpty())
        assertEquals(1L, buffer.reordered)
        assertEquals(1, buffer.maxDepth)
        // Пришёл 1 — отдаём 1 и сразу 2.
        assertEquals(listOf("b", "c"), buffer.put(1, "b".toByteArray()).map { String(it) })
    }

    @Test
    fun duplicatesAreDropped() {
        val buffer = ReorderBuffer()
        buffer.put(0, "a".toByteArray())
        assertTrue(buffer.put(0, "a".toByteArray()).isEmpty())
        assertEquals(1L, buffer.dropped)
    }

    @Test
    fun framedPathAddsFlagOnce() {
        assertEquals("/ws?f=1", Lte4.framedPath("/ws"))
        assertEquals("/ws?a=1&f=1", Lte4.framedPath("/ws?a=1"))
        assertEquals("/ws?f=1", Lte4.framedPath("/ws?f=1"))
        assertEquals("/ws?f=1", Lte4.framedPath(""))
        // Параллельная отправка кодируется в самом флаге: отдельный &c= шлюз режет.
        assertEquals("/ws?f=1c32", Lte4.framedPath("/ws", 32))
        assertEquals("/ws?a=1&f=1c16", Lte4.framedPath("/ws?a=1", 16))
        assertEquals("/ws?f=1", Lte4.framedPath("/ws", 1))
        // Явно заданное в подписке не переопределяем.
        assertEquals("/ws?f=1c8", Lte4.framedPath("/ws?f=1c8", 32))
    }

    /**
     * Расписание с релея: первый толкач через 15 мс тишины, повторы через
     * 50 / 150 / 400 мс. Сразу после пачки толкач залипает вместе с хвостом.
     */
    @Test
    fun tailPusherFollowsRelaySchedule() {
        val p = TailPusher(firstDelayMs = 15)
        p.onBatchSent(1_000)
        assertFalse(p.takeIfDue(1_010))
        assertTrue(p.takeIfDue(1_015))
        assertFalse(p.takeIfDue(1_060))
        assertTrue(p.takeIfDue(1_065))
        assertTrue(p.takeIfDue(1_215))
        assertTrue(p.takeIfDue(1_615))
        assertFalse(p.takeIfDue(5_000))
        assertEquals(4L, p.sent)
    }

    @Test
    fun newDataCancelsScheduledPushers() {
        val p = TailPusher()
        p.onBatchSent(1_000)
        p.onData()
        assertFalse(p.takeIfDue(2_000))
        assertEquals(0L, p.sent)
    }

    @Test
    fun waitingTimeIsMeasuredForHeldFrames() {
        val buffer = ReorderBuffer()
        buffer.put(0, "a".toByteArray(), nowMs = 1_000)
        // 2 пришёл, но ждёт «дыру» на месте 1.
        assertTrue(buffer.put(2, "c".toByteArray(), nowMs = 1_010).isEmpty())
        val out = buffer.put(1, "b".toByteArray(), nowMs = 1_050)
        assertEquals(listOf("b", "c"), out.map { String(it) })
        // «c» прождал 1050 − 1010 = 40 мс — это и есть задержка шлюза.
        assertEquals(40L, buffer.lastWaitMs)
        assertEquals(40L, buffer.maxWaitMs)
    }

    @Test
    fun gatewayIsRecognisedOnlyForYandexApigw() {
        assertTrue(Lte4.isLte4Gateway("gw.apigw.yandexcloud.net"))
        assertTrue(Lte4.isLte4Gateway("ABC.apigw.yandexcloud.net"))
        assertFalse(Lte4.isLte4Gateway("apigw.yandexcloud.net.evil.com"))
        assertFalse(Lte4.isLte4Gateway("my.wingerapp.pro"))
        assertFalse(Lte4.isLte4Gateway(""))
    }

    @Test
    fun chunkLimitRespectsFramesOf32Kb() {
        assertEquals(32 * 1024, Lte4.MAX_CHUNK)
    }

    /**
     * Режимы прослойки: «флаш» и «выкл» не трогают поток байт — иначе ядро
     * получит мусор вместо VLESS. Ломается это молча: коннект есть, трафика нет.
     */
    @Test
    fun rawAndFlushModesLeaveTheByteStreamIntact() {
        val chunk = "payload".toByteArray()
        val flush = Lte4Wire.outbound(Lte4.MODE_FLUSH, 5, chunk)
        assertEquals(2, flush.size)
        assertArrayEquals(chunk, flush[0])
        assertEquals(0, flush[1].size)

        val off = Lte4Wire.outbound(Lte4.MODE_OFF, 5, chunk)
        assertEquals(1, off.size)
        assertArrayEquals(chunk, off[0])

        assertFalse(Lte4Wire.inboundIsFramed(Lte4.MODE_FLUSH))
        assertFalse(Lte4Wire.inboundIsFramed(Lte4.MODE_OFF))
        assertTrue(Lte4Wire.inboundIsFramed(Lte4.MODE_FRAMED))
    }

    @Test
    fun framedModeWrapsChunkAndKeepAliveFollowsMode() {
        val chunk = byteArrayOf(1, 2, 3)
        val out = Lte4Wire.outbound(Lte4.MODE_FRAMED, 9, chunk)
        assertEquals(1, out.size)
        val frame = Lte4.decode(out[0])
        assertEquals(Lte4.TYPE_DATA, frame?.type)
        assertEquals(9L, frame?.seq)
        assertArrayEquals(chunk, frame?.payload)

        // «Флаш» толкает пустым сообщением, «выкл» не толкает вовсе.
        assertEquals(0, Lte4Wire.keepAlive(Lte4.MODE_FLUSH, 1)?.size)
        assertNull(Lte4Wire.keepAlive(Lte4.MODE_OFF, 1))
        assertEquals(Lte4.TYPE_PING, Lte4.decode(Lte4Wire.keepAlive(Lte4.MODE_FRAMED, 4)!!)?.type)
    }

    @Test
    fun autoUplinkBatchesBySizeAndPushesOnIdle() {
        val up = AutoUplink(maxBatch = 8, idleMs = 25)
        // Мелкий кусок копится: отправлять пока нечего.
        assertTrue(up.offer(byteArrayOf(1, 2, 3), nowMs = 1_000).isEmpty())
        // Тишины ещё нет — выталкивать рано.
        assertTrue(up.flushIfIdle(nowMs = 1_010).isEmpty())
        // Пауза прошла — отдаём ровно то, что накопили.
        val idle = up.flushIfIdle(nowMs = 1_030)
        assertEquals(1, idle.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), idle[0])
        assertEquals(1L, up.idleFlushes)
        assertEquals(0, up.pending())
    }

    @Test
    fun autoUplinkSplitsByBatchSizeAndKeepsOrder() {
        val up = AutoUplink(maxBatch = 4, idleMs = 25)
        // 10 байт при пачке 4 → 4 + 4 + 2, порядок байт сохраняется.
        val out = up.offer(ByteArray(10) { it.toByte() }, nowMs = 1_000)
        assertEquals(listOf(4, 4, 2), out.map { it.size })
        assertEquals((0..9).map { it.toByte() }, out.flatMap { it.toList() })
        assertEquals(0, up.pending())
    }

    @Test
    fun autoUplinkDrainsRemainderOnClose() {
        val up = AutoUplink(maxBatch = 64, idleMs = 1_000)
        assertTrue(up.offer("tail".toByteArray(), nowMs = 5).isEmpty())
        val rest = up.drainNow()
        assertEquals(1, rest.size)
        assertEquals("tail", String(rest[0]))
    }

    /**
     * Регрессия: буфер порядка раньше был один на всю прослойку. Вторая сессия
     * тоже начинает с seq 0, и все её кадры отбрасывались как «уже отданные».
     * Теперь буфер принадлежит соединению.
     */
    @Test
    fun everyConnectionStartsWithItsOwnOrderState() {
        val first = Lte4Shim.ConnState()
        repeat(4) { first.reorder.put(it.toLong(), byteArrayOf(it.toByte())) }
        assertTrue(first.reorder.put(4, byteArrayOf(4)).isNotEmpty())

        val second = Lte4Shim.ConnState()
        assertNotSame(first.reorder, second.reorder)
        // Свежая сессия принимает seq 0 и ничего не отбрасывает.
        assertEquals(1, second.reorder.put(0, byteArrayOf(9)).size)
        assertEquals(0L, second.reorder.dropped)
        assertEquals(0L, second.seq.get())
    }
}
