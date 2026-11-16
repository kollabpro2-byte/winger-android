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

@Composable
fun Field(v: String, label: String, password: Boolean = false, on: (String) -> Unit) {
    OutlinedTextField(
        v, on, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = TextFieldDefaults.colors(
            focusedTextColor = TextMain, unfocusedTextColor = TextMain,
            focusedContainerColor = if (AppTheme.p.isLight) Color(0xFFF7F9FC) else Elev,
            unfocusedContainerColor = if (AppTheme.p.isLight) Color(0xFFF7F9FC) else Elev,
            focusedIndicatorColor = Purple, unfocusedIndicatorColor = Line
        )
    )
}
@Composable
internal fun Section(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(22.dp)).background(Elev).border(1.dp, Line, RoundedCornerShape(22.dp)).padding(16.dp), content = content)
}
@Composable
internal fun Seg(label: String, on: Boolean, modifier: Modifier, click: () -> Unit) {
    Box(modifier.padding(horizontal = 2.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Purple.copy(.28f) else Elev).clickable { click() }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (on) OnAccent else OnCard, fontSize = 12.sp)
    }
}
/**
 * Карточка подписки, пока она грузится.
 *
 * Раньше это была синяя плашка, а подпись «Подписка в пути» стояла СНАРУЖИ, бледно-голубым
 * на фоне экрана — в светлой теме она почти сливалась с фоном. Теперь карточка сразу в том же
 * фиолетовом градиенте, что и загруженная (AccentBrush), а надпись — белая и внутри карточки:
 * контраст не зависит от темы. Плюс спиннер и две «полоски-заготовки», чтобы было видно,
 * что карточка наполняется.
 */
@Composable
internal fun SubLoading() {
    val s = rememberStrings()
    val t by rememberInfiniteTransition(label = "subLoad").animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "subLoadShift",
    )
    Box(
        Modifier.fillMaxWidth().height(168.dp).clip(RoundedCornerShape(22.dp))
            .background(AccentBrush)
    ) {
        // Мягкие блики, плывущие по карточке: движение есть, резких деталей нет.
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White.copy(alpha = .10f), 46.dp.toPx(),
                Offset(size.width * t, size.height * 0.30f))
            drawCircle(Color.White.copy(alpha = .07f), 26.dp.toPx(),
                Offset(size.width * ((t + 0.4f) % 1f), size.height * 0.72f))
        }
        Column(
            Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    s[R.string.sub_plan], color = Color.White.copy(alpha = .8f),
                    fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold,
                )
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = Color.White.copy(alpha = .9f),
                    strokeWidth = 1.8.dp,
                )
            }
            Text(
                s[R.string.sub_loading], color = Color.White,
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            )
            Column {
                LoadBar(0.60f, 0.22f)
                Spacer(Modifier.height(8.dp))
                LoadBar(0.38f, 0.16f)
            }
        }
    }
}

/** Полоска-заготовка на карточке загрузки: показывает, где появятся данные. */
@Composable
private fun LoadBar(widthFraction: Float, alpha: Float) {
    Box(
        Modifier.fillMaxWidth(widthFraction).height(7.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = alpha))
    )
}

/**
 * Орёл как растр в исходном разрешении: 300×230, без масштабирования под
 * плотность экрана (`inScaled = false`).
 *
 * Зачем вручную: у `Image(painter = …)` нет параметра качества фильтрации, а
 * исходник на телефоне уменьшается в 3–5 раз. На дефолтном билинейном фильтре
 * контуры «плывут» — именно это и читается как «графика не такая качественная».
 */
@Composable
internal fun rememberEagle(): ImageBitmap {
    val res = LocalContext.current.resources
    return remember(res) {
        val opts = BitmapFactory.Options().apply { inScaled = false }
        BitmapFactory.decodeResource(res, R.drawable.eagle, opts).asImageBitmap()
    }
}

@Composable
internal fun BirdMark() {
    val eagle = rememberEagle()
    Box(Modifier.width(22.dp).height(17.dp)) {
        // Тень одним смещённым слоем вместо четырёх чёрных копий по кругу:
        // такая «обводка» давала грязный контур и выглядела дёшево.
        Image(
            bitmap = eagle, contentDescription = null,
            modifier = Modifier.fillMaxSize().offset(y = 0.7.dp),
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(Color.Black.copy(.35f), BlendMode.SrcIn),
            filterQuality = FilterQuality.High,
        )
        Image(
            bitmap = eagle, contentDescription = rememberStrings()[R.string.comp_bird],
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.High,
        )
    }
}
@Composable
internal fun BoxScope.Flakes() {
    val t by rememberInfiniteTransition(label = "flakes").animateFloat(0f, 1f, infiniteRepeatable(tween(16000, easing = LinearEasing)), label = "f")
    Canvas(Modifier.matchParentSize()) {
        listOf(0.08f to 1.5f, 0.22f to 2.5f, 0.38f to 1.5f, 0.54f to 2f, 0.68f to 1.5f, 0.8f to 3f, 0.9f to 1.5f).forEachIndexed { i, (x, r) ->
            val p = (t + i * 0.14f) % 1f
            val alpha = when {
                p < 0.1f -> p * 8f
                else -> (1f - p).coerceAtLeast(0f)
            }
            drawCircle(Color.White.copy(alpha * 0.85f), r.dp.toPx(), Offset(size.width * x + 8.dp.toPx() * p, size.height * (1f - p)))
        }
    }
}
@Composable
internal fun PayMarks(gateway: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Image(painterResource(R.drawable.sbp), rememberStrings()[R.string.comp_sbp], Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)))
        if (gateway == "PLATEGA") {
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(Brush.linearGradient(listOf(Color(0xFF21A35A), Color(0xFF0D6A38)))), contentAlignment = Alignment.Center) {
                Text(rememberStrings()[R.string.comp_mir], color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
@Composable
internal fun SiteParticles() {
    val t by rememberInfiniteTransition(label = "p").animateFloat(
        0f, 6.28f, infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "a"
    )
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(size.width / 2, size.height / 2)
        val colors = listOf(Cyan, Color(0xFFE040FB), Purple)
        val pts = (0 until 24).map { i ->
            val a = i * 6.28f / 24 + t * 0.4f
            val r = 50f + (i % 7) * 16f
            Offset(c.x + cos(a) * r, c.y + sin(a) * r * 0.72f)
        }
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            if (hypot(pts[i].x - pts[j].x, pts[i].y - pts[j].y) < 90f) {
                drawLine(colors[i % 3].copy(alpha = .18f), pts[i], pts[j], strokeWidth = 1f)
            }
        }
        pts.forEachIndexed { i, pt -> drawCircle(colors[i % 3].copy(alpha = .7f), 3f, pt) }
    }
}
internal fun readAttachment(ctx: Context, uri: Uri): pro.wingerapp.vpn.net.Attached? = runCatching {
    val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (c.moveToFirst() && i >= 0) c.getString(i) else null
    } ?: "file"
    val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    if (bytes.size > 8 * 1024 * 1024) return null
    pro.wingerapp.vpn.net.Attached(name, mime, bytes)
}.getOrNull()
