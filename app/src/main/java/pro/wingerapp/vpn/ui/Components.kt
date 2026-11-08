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
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedContainerColor = Elev, unfocusedContainerColor = Elev,
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
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}
@Composable
internal fun SubLoading() {
    Box(
        Modifier.fillMaxWidth().height(168.dp).clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Color(0xE65CBAFF), Color(0xB82E96E6))))
    ) {
        val t by rememberInfiniteTransition(label = "sky").animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "d")
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White.copy(.55f), 18.dp.toPx(), Offset(size.width * t, 46.dp.toPx()))
            drawCircle(Color(0xFFF4F7FB), 8.dp.toPx(), Offset(size.width * ((t + 0.2f) % 1f), 70.dp.toPx()))
        }
    }
    Text(rememberStrings()[R.string.sub_loading], color = Color(0xFFC5D7E6), modifier = Modifier.fillMaxWidth().padding(top = 14.dp), textAlign = TextAlign.Center)
}
@Composable
internal fun BirdMark() {
    val painter = painterResource(R.drawable.eagle)
    Box(Modifier.width(22.dp).height(17.dp)) {
        listOf(-0.4f to 0f, 0.4f to 0f, 0f to -0.4f, 0f to 0.4f).forEach { (x, y) ->
            Image(painter, null, Modifier.fillMaxSize().offset(x.dp, y.dp), contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.tint(Color.Black, BlendMode.SrcIn))
        }
        Image(painter, rememberStrings()[R.string.comp_bird], Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
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
