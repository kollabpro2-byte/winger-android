package pro.wingerapp.vpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import pro.wingerapp.vpn.BuildConfig
import pro.wingerapp.vpn.R
import pro.wingerapp.vpn.*
import pro.wingerapp.vpn.net.*

/* Цвета — геттеры к активной палитре (см. AppTheme ниже). Так все 80+ мест,
   где они используются, автоматически следуют за темой, включая Canvas. */
internal val Bg: Color get() = AppTheme.p.bgTop
internal val Elev: Color get() = AppTheme.p.elev
internal val Cyan: Color get() = AppTheme.p.cyan
internal val Purple: Color get() = AppTheme.p.purple
internal val Muted: Color get() = AppTheme.p.muted
internal val Dim: Color get() = AppTheme.p.dim
internal val TabOn: Color get() = AppTheme.p.tabOn
internal val TabOff: Color get() = AppTheme.p.tabOff
internal val Line: Color get() = AppTheme.p.line
/** Основной текст на карточках. */
internal val TextMain: Color get() = AppTheme.p.text
/** Заголовки разделов, лежащие прямо на фоне. */
internal val Caption: Color get() = AppTheme.p.caption
/** Усиленная граница карточки (на сером и белом нужна плотнее). */
internal val CardLine: Color get() = AppTheme.p.cardLine
/** Текст на карточках и фоне: белый в тёмной/средней, почти чёрный в светлой. */
internal val OnCard: Color get() = if (AppTheme.p.isLight) Color(0xFF0B1220) else Color(0xFFFFFFFF)
/** Текст на сиреневых кнопках и карточке подписки — всегда белый. */
internal val OnAccent: Color get() = Color(0xFFFFFFFF)
/** Вторичная поверхность внутри карточки (строки оплаты, мелкие кнопки). */
internal val CardSoft: Color get() = if (AppTheme.p.isLight) Color(0xFFEDF1F6) else Color(0xFF1A2436)

/* ── ТРИ ТЕМЫ ────────────────────────────────────────────────────────
   Тёмная — исходная палитра приложения. Средняя — тот же интерфейс на
   нейтрально-сером фоне: цвета рассчитаны на почти чёрный, поэтому на сером
   их пришлось сделать плотнее (иначе текст выглядел «под плёнкой»). Светлая —
   серый фон и затемнённые акценты: неоновый циан на белом не читается.

   Все цвета ниже — обычные геттеры, а не @Composable: их читают и внутри
   Canvas/DrawScope, где композиции нет. Переключение темы идёт через
   AppTheme.mode (mutableStateOf), поэтому экран перерисовывается сам. */
enum class ThemeMode { DARK, MID, LIGHT }

internal data class Palette(
    val bgTop: Color,
    val bgBottom: Color,
    val elev: Color,
    val cyan: Color,
    val purple: Color,
    val muted: Color,
    val dim: Color,
    val tabOn: Color,
    val tabOff: Color,
    val line: Color,
    /** Основной текст на карточках. */
    val text: Color,
    /** Заголовки разделов, которые лежат прямо на фоне. */
    val caption: Color,
    /** Граница карточки: на сером и белом нужна плотнее. */
    val cardLine: Color,
    /** Пинг и состояние: на светлом фоне тёмные, на тёмном — мятные. */
    val pingFast: Color,
    val pingMid: Color,
    val pingSlow: Color,
    val pingNone: Color,
    /**
     * Верхняя точка градиента названия локации — «самый светлый» оттенок, до
     * которого доходит перелив в пик свечения орла. Первый стоп — [cyan], то
     * есть тот же цвет, что у надписи «WINGER»: название не выпадает из палитры.
     */
    val locPeak: Color,
    val isLight: Boolean,
)

private val DarkPalette = Palette(
    bgTop = Color(0xFF040D1A), bgBottom = Color(0xFF02080F),
    elev = Color(0xFF0A162A), cyan = Color(0xFF22D3EE), purple = Color(0xFFA855F7),
    muted = Color(0xFF94A3B8), dim = Color(0xFF64748B),
    tabOn = Color(0xFF7FE3FF), tabOff = Color(0x6B7FE3FF), line = Color(0x14FFFFFF),
    text = Color(0xFFE8EEF6), caption = Color(0xFF94A3B8), cardLine = Color(0x14FFFFFF),
    pingFast = Color(0xFF7EE0B0), pingMid = Color(0xFFF0C14B),
    pingSlow = Color(0xFFF07171), pingNone = Color(0xFF7C8A9B),
    locPeak = Color(0xFFE9FDFF),
    isLight = false,
)

private val MidPalette = Palette(
    // Тёмно-синее «сукно»: выбранный пользователем оттенок.
    bgTop = Color(0xFF2A3550), bgBottom = Color(0xFF1F2740),
    // Подложка та же, что в тёмной: элементы не перекрашиваем, только фон.
    elev = Color(0xFF0A162A), cyan = Color(0xFF6FE8FA), purple = Color(0xFFA855F7),
    muted = Color(0xFFC2D0E0), dim = Color(0xFFA9B8CA),
    tabOn = Color(0xFF8FEBFF), tabOff = Color(0xFFC2D0E0), line = Color(0x21FFFFFF),
    text = Color(0xFFF7FBFF), caption = Color(0xFFE1ECF7), cardLine = Color(0x21FFFFFF),
    pingFast = Color(0xFF7EE0B0), pingMid = Color(0xFFF0C14B),
    pingSlow = Color(0xFFF07171), pingNone = Color(0xFF9AA9BB),
    locPeak = Color(0xFFF4FEFF),
    isLight = false,
)

private val LightPalette = Palette(
    bgTop = Color(0xFFCBD2DB), bgBottom = Color(0xFFB6C0CD),
    elev = Color(0xFFFBFCFD), cyan = Color(0xFF0E7490), purple = Color(0xFF7C3AED),
    muted = Color(0xFF3E5A6E), dim = Color(0xFF4A6478),
    tabOn = Color(0xFF0B6B85), tabOff = Color(0xFF3C5561), line = Color(0x1F0F172A),
    text = Color(0xFF0B1220), caption = Color(0xFF2E4C63), cardLine = Color(0x1A0F172A),
    pingFast = Color(0xFF0E8A4F), pingMid = Color(0xFFB26A00),
    pingSlow = Color(0xFFC62828), pingNone = Color(0xFF6B7686),
    locPeak = Color(0xFF0F86A2),
    isLight = true,
)

internal object AppTheme {
    /** Источник правды — Store; сюда значение кладёт MainActivity при старте и при смене. */
    var mode by mutableStateOf(ThemeMode.MID)

    val p: Palette
        get() = when (mode) {
            ThemeMode.DARK -> DarkPalette
            ThemeMode.MID -> MidPalette
            ThemeMode.LIGHT -> LightPalette
        }

    fun fromStored(value: Int): ThemeMode = when (value) {
        0 -> ThemeMode.DARK
        2 -> ThemeMode.LIGHT
        else -> ThemeMode.MID
    }

    fun toStored(mode: ThemeMode): Int = when (mode) {
        ThemeMode.DARK -> 0
        ThemeMode.LIGHT -> 2
        ThemeMode.MID -> 1
    }
}

/** Фон экрана: в тёмной теме почти чёрный, в средней и светлой — серый градиент. */
internal val BgBrush: Brush
    get() = Brush.verticalGradient(listOf(AppTheme.p.bgTop, AppTheme.p.bgBottom))

/** Карточка подписки и выбранная локация остаются сиреневыми во всех темах. */
internal val AccentBrush: Brush
    get() = Brush.linearGradient(
        listOf(
            if (AppTheme.p.isLight) Color(0xFF8B5CF6) else Color(0xFFA78BFA),
            if (AppTheme.p.isLight) Color(0xFF4C1D95) else Color(0xFF4C1D95),
        )
    )
