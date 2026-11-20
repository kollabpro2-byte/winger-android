package pro.wingerapp.vpn

import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Очередь аплинка LTE 5: номер куска обязан выдаваться **однократно**.
 *
 * Регрессия из build 151: `upSeq.getAndIncrement()` стоял внутри цикла ожидания
 * свободного места в очереди, и каждый отказ `offer` сжигал номер. В аплинке
 * появлялась дыра, релей собирает поток строго по `seq` и всё после дырки не
 * отдаёт Xray. Снаружи это выглядит так: вверх уходят мегабайты, вниз приходит
 * один кадр и `CLOSE`, загрузка в Telegram не начинается.
 */
class UplinkQueueTest {

    @Test
    fun seqIsNotBurnedWhileQueueIsFull() {
        val nextSeq = AtomicLong(0)
        val seen = mutableListOf<Long>()
        var attempts = 0

        enqueueUplinkPart(
            nextSeq = nextSeq,
            part = "chunk".toByteArray(),
            canContinue = { true },
            offer = { item ->
                attempts++
                if (attempts < 4) {
                    false // очередь полна первые три попытки
                } else {
                    seen += item.first
                    true
                }
            },
        )

        assertEquals("кусок должен уйти ровно один раз", listOf(0L), seen)
        assertEquals("счётчик не должен прыгать через номера", 1L, nextSeq.get())
        assertEquals("ожидание свободного места — 4 попытки", 4, attempts)
    }

    @Test
    fun partsGetStrictlyIncreasingNumbers() {
        val nextSeq = AtomicLong(0)
        val seen = mutableListOf<Long>()

        repeat(5) { i ->
            enqueueUplinkPart(
                nextSeq = nextSeq,
                part = ByteArray(4) { i.toByte() },
                canContinue = { true },
                offer = { item ->
                    seen += item.first
                    true
                },
            )
        }

        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), seen)
        assertEquals(5L, nextSeq.get())
    }

    @Test
    fun deadStreamDoesNotSpinForever() {
        val nextSeq = AtomicLong(7)
        var alive = true
        var offers = 0

        enqueueUplinkPart(
            nextSeq = nextSeq,
            part = "x".toByteArray(),
            canContinue = { alive },
            offer = {
                offers++
                alive = false // поток умер, пока ждали места
                false
            },
        )

        assertTrue("должны были попробовать положить кусок", offers >= 1)
        assertEquals("после смерти потока новых номеров нет", 8L, nextSeq.get())
    }

    @Test
    fun payloadIsPassedThrough() {
        val payload = ByteArray(1024) { (it % 251).toByte() }
        var got: ByteArray? = null

        enqueueUplinkPart(
            nextSeq = AtomicLong(0),
            part = payload,
            canContinue = { true },
            offer = { item ->
                got = item.second
                true
            },
        )

        assertEquals(payload.size, got?.size)
        assertTrue("payload не должен меняться по дороге", payload.contentEquals(got))
    }
}
