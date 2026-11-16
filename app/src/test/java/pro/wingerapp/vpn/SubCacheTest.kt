package pro.wingerapp.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import pro.wingerapp.vpn.net.Sub

/**
 * Кэш подписки: главное — пережить сериализацию без потерь и не «ожить» на
 * мусоре. Эти правила важны, потому что кэш подставляется в интерфейс до того,
 * как придёт сеть.
 */
class SubCacheTest {

    private val plan = Sub(
        name = "Pro · 1 месяц",
        expires = "2026-12-31T23:59:59Z",
        url = "https://panel/sub/abc",
        devices = 3,
        devicesUsed = 2,
        trafficUsedGb = 47.0,
        trafficLimitGb = 300.0,
        unlimitedTraffic = false,
    )

    @Test
    fun roundTripKeepsEverything() {
        val encoded = SubCache.encode(plan, raw = """{"outbounds":[]}""", selected = "Автопилот", savedAt = 123L)
        val decoded = SubCache.decode(encoded)
        assertNotNull(decoded)
        assertEquals(plan, decoded!!.plan)
        assertEquals("""{"outbounds":[]}""", decoded.raw)
        assertEquals("Автопилот", decoded.selected)
        assertEquals(123L, decoded.savedAt)
    }

    @Test
    fun blankRawIsNotACachedSubscription() {
        val encoded = SubCache.encode(plan, raw = "   ", selected = "Автопилот")
        assertNull(SubCache.decode(encoded))
    }

    @Test
    fun brokenJsonGivesNothing() {
        assertNull(SubCache.decode("{это не json"))
        assertNull(SubCache.decode(""))
    }

    @Test
    fun missingFieldsFallBackToDefaults() {
        val decoded = SubCache.decode("""{"raw":"x"}""")
        assertNotNull(decoded)
        assertEquals("x", decoded!!.raw)
        assertEquals("", decoded.plan.name)
        assertEquals(0, decoded.plan.devices)
        assertEquals(true, decoded.plan.unlimitedTraffic)
    }
}
