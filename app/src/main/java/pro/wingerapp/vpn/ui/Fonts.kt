package pro.wingerapp.vpn.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import pro.wingerapp.vpn.R

/**
 * Inter с настоящими весами.
 *
 * Системный шрифт Android (Roboto, а на Xiaomi — MiSans) не имеет промежуточных
 * весов: 600 Compose «дорисовывает» синтетически, из-за чего жирный текст
 * выглядит размазанным и «дешёвым». В макете текст рисовал браузер на macOS,
 * то есть SF Pro — с полноценной сеткой весов и другими метриками. Inter
 * решает обе проблемы: реальные 400/500/600/700/800 и одинаковый вид на всех
 * устройствах независимо от прошивки.
 *
 * Файлы — сабсет Inter 4.0 (латиница, кириллица, цифры, знаки ₽ № → ↑ ↓ ✓).
 */
val WingerSans = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)

/**
 * Ставит Inter каскадом по умолчанию: любой `Text` без явного `fontFamily`
 * наследует его отсюда. Явные `TextStyle(...)` его не наследуют — там шрифт
 * указывается точечно через [WingerSans].
 */
/**
 * Та же тема, но со шрифтом Inter во всех стилях Material. Нужна потому, что
 * компоненты (диалоги, кнопки, поля ввода) берут шрифт из Theme.typography, а не
 * из LocalTextStyle — без этого внутри диалогов оставался бы системный шрифт.
 */
private fun Typography.withFamily(f: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = f),
    displayMedium = displayMedium.copy(fontFamily = f),
    displaySmall = displaySmall.copy(fontFamily = f),
    headlineLarge = headlineLarge.copy(fontFamily = f),
    headlineMedium = headlineMedium.copy(fontFamily = f),
    headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f),
    titleMedium = titleMedium.copy(fontFamily = f),
    titleSmall = titleSmall.copy(fontFamily = f),
    bodyLarge = bodyLarge.copy(fontFamily = f),
    bodyMedium = bodyMedium.copy(fontFamily = f),
    bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f),
    labelMedium = labelMedium.copy(fontFamily = f),
    labelSmall = labelSmall.copy(fontFamily = f),
)

@Composable
fun WingerFonts(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = WingerSans),
    ) {
        MaterialTheme(typography = MaterialTheme.typography.withFamily(WingerSans)) {
            content()
        }
    }
}
