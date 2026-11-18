package pro.wingerapp.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
