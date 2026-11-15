package pro.wingerapp.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Автозапуск после входа выбирает «Автопилот», а если такого узла в подписке
 * нет — первый сервер. Пустая подписка даёт пустую строку: тогда автовключения
 * не будет, экран покажет выключенную кнопку без орла.
 */
class AutoServerPickTest {

    @Test
    fun autopilotWinsWhenPresent() {
        val names = listOf("Нидерланды · Амстердам", "Автопилот", "Германия · Франкфурт")
        assertEquals("Автопилот", pickAutoServer(names))
    }

    @Test
    fun autopilotIsFoundCaseInsensitive() {
        assertEquals("АВТОПИЛОТ", pickAutoServer(listOf("США · Нью-Йорк", "АВТОПИЛОТ")))
    }

    @Test
    fun autopilotMatchedInsideLongerName() {
        assertEquals("Автопилот (быстрый)", pickAutoServer(listOf("Турция · Стамбул", "Автопилот (быстрый)")))
    }

    @Test
    fun fallsBackToFirstServer() {
        val names = listOf("Финляндия · Хельсинки", "США · Нью-Йорк")
        assertEquals("Финляндия · Хельсинки", pickAutoServer(names))
    }

    @Test
    fun emptySubscriptionGivesEmptyChoice() {
        assertEquals("", pickAutoServer(emptyList()))
    }
}
