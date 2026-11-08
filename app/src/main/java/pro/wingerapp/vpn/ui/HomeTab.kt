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
internal fun HomeTab(
    ctx: Context, hwid: String, sub: Sub?, servers: List<ServerNode>, pings: Map<String, Int?>,
    place: String, on: Boolean, elapsed: String, up: String, down: String, pinging: Boolean, refreshing: Boolean, err: String?, notice: String?,
    raw: String, connect: (String) -> Unit, disconnect: () -> Unit, reload: (Boolean) -> Unit,
    onSelect: (String) -> Unit, onToggle: () -> Unit
) {
    val s = rememberStrings()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 12.dp)) {
            Text("WINGER", color = Cyan, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
            Text(" v${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
        }
        if (sub == null) {
            Text(s[R.string.home_no_subscription], color = Color(0xFFF87171), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
            Text(s[R.string.home_no_subscription_hint], color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (on) Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("↑ $up", color = Color(0xFF7EE0B0), fontSize = 14.sp)
            Text(elapsed, color = Muted, fontSize = 14.sp)
            Text("↓ $down", color = Color(0xFF9ECBFF), fontSize = 14.sp)
        } else Spacer(Modifier.height(22.dp))
        if (!on) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(s[R.string.home_tap_prefix], color = Color(0xFFC5D0DC), fontSize = 16.sp)
            Image(painterResource(R.drawable.eagle), s[R.string.home_eagle], Modifier.size(16.dp))
            Text(s[R.string.home_tap_suffix], color = Color(0xFFC5D0DC), fontSize = 16.sp)
        }
        val glow by rememberInfiniteTransition(label = "lamp").animateFloat(0.35f, 1f, infiniteRepeatable(tween(1600)), label = "g")
        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            if (on) SiteParticles()
            Box(
                Modifier.size(168.dp).shadow(if (on) 18.dp else 8.dp, CircleShape).clip(CircleShape)
                    .background(Brush.radialGradient(if (on) listOf(Color(0xFF2A2458), Color(0xFF101A30)) else listOf(Color(0xFF1C3D58), Color(0xFF0A1628))))
                    .border(2.dp, if (on) Purple.copy(glow) else Color(0xFF2A5A74), CircleShape)
                    .clickable { onToggle() },
                contentAlignment = Alignment.Center
            ) {
                Image(painterResource(R.drawable.eagle), "Winger", Modifier.size(112.dp).alpha(if (on) 1f else 0.5f), contentScale = ContentScale.Fit)
            }
        }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Color(0xFFC4B5FD))) { append(s[R.string.home_plan_prefix]) }
                withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) { append(sub?.name ?: "…") }
                withStyle(SpanStyle(color = Muted)) { append(s[R.string.home_plan_left_prefix]) }
                withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) {
                    append(daysUntil(sub?.expires)?.let { s.quantity(R.plurals.days, it) } ?: s[R.string.fmt_none])
                }
            },
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 13.sp
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
            Text(s[R.string.home_devices], color = Muted, fontSize = 12.sp)
            Text(
                s.format(
                    R.string.home_devices_value,
                    sub?.devicesUsed ?: 0,
                    if ((sub?.devices ?: 0) <= 0) "∞" else sub!!.devices.toString()
                ),
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
            )
            Text(s[R.string.home_traffic], color = Muted, fontSize = 12.sp)
            Text(trafficText(sub, s), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        err?.let { Text(it, color = Color(0xFFF87171), fontSize = 12.sp, maxLines = 3, modifier = Modifier.padding(top = 6.dp)) }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (notice != null) Text(notice, color = Color(0xFF86EFAC), fontSize = 13.sp, modifier = Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(Color(0x2434C759)).padding(horizontal = 12.dp, vertical = 6.dp))
            else Spacer(Modifier.weight(1f))
            Box(Modifier.size(32.dp).clip(CircleShape).background(Elev).border(1.dp, Color(0xFF2A4A66), CircleShape).clickable { reload(false) }, contentAlignment = Alignment.Center) {
                if (refreshing) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Cyan)
                else Text("⟳", color = Color(0xFFC9D7E4), fontSize = 16.sp)
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(32.dp).clip(CircleShape).background(Elev).border(1.dp, Color(0xFF2A4A66), CircleShape).clickable(enabled = !pinging) { reload(true) }, contentAlignment = Alignment.Center) {
                Text("⚡", color = Color(0xFFC9D7E4), fontSize = 14.sp)
            }
        }
        if (servers.isEmpty()) Text(s[R.string.home_loading], color = Dim, modifier = Modifier.padding(top = 8.dp))
        Column {
            grouped(servers).forEach { group ->
                Text(s[group.titleRes], color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                group.nodes.forEach { node ->
                    val selected = place == node.name
                    val ms = if (pings.containsKey(node.name)) pings[node.name] else null
                    val waiting = pinging && !pings.containsKey(node.name)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) Purple.copy(.22f) else Elev)
                            .border(1.dp, if (selected) Purple.copy(.7f) else Line, RoundedCornerShape(14.dp))
                            .clickable { onSelect(node.name) }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(node.name, color = Color(0xFFE8EEF6), fontWeight = FontWeight.Normal, fontSize = 13.sp)
                            Text(s.format(R.string.home_server_proto, node.port), color = Muted, fontSize = 10.sp)
                        }
                        if (waiting) {
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Cyan)
                        } else if (pings.containsKey(node.name)) {
                            val c = when { ms == null || ms == -1 -> Color(0xFF7C8A9B); ms < 120 -> Color(0xFF7EE0B0); ms < 200 -> Color(0xFFF0C14B); else -> Color(0xFFF07171) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(c))
                                Text(when {
                                    ms == null -> s[R.string.home_ping_none]
                                    ms == -1 -> s[R.string.home_ping_unknown]
                                    else -> ms.toString()
                                }, color = c, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}
