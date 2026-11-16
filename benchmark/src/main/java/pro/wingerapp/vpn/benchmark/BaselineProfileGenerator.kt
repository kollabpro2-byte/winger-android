package pro.wingerapp.vpn.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Записывает Baseline Profile для Winger.
 *
 * Сценарий — холодный старт приложения и первый экран (вход): именно это
 * происходит у пользователя при каждом запуске. Профиль описывает наш код, а не
 * устройство, поэтому запись на эмуляторе даёт тот же эффект на любом телефоне.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = "pro.wingerapp.vpn",
        includeInStartupProfile = true,
    ) {
        pressHome()
        // startActivityAndWait() здесь не подходит: APK тяжёлый (80 МБ нативной
        // библиотеки), и на эмуляторе с программным рендером macrobenchmark не
        // получает подтверждения первого кадра — тест падает с «Unable to confirm
        // activity launch completion». Запускаем тем же am start и ждём окно.
        device.executeShellCommand("am start -n pro.wingerapp.vpn/.MainActivity")
        device.waitForIdle()
        Thread.sleep(4000)
    }
}
