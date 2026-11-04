package pro.wingerapp.vpn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import pro.wingerapp.vpn.net.Api
import pro.wingerapp.vpn.net.Sub

private val Bg = Color(0xFF040D1A)
private val Elev = Color(0xFF0A162A)
private val Cyan = Color(0xFF22D3EE)
private val Purple = Color(0xFFA855F7)
private val Muted = Color(0xFF94A3B8)
private val Dim = Color(0xFF64748B)
private val TabOn = Color(0xFFC084FC)
private val TabOff = Color(0xFF7C6A9A)
private val Line = Color(0x14FFFFFF)

class MainActivity : ComponentActivity() {
    private var pendingConfig: String? = null
    private val vpnPerm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val cfg = pendingConfig ?: return@registerForActivityResult
        startForegroundService(Intent(this, WingerVpnService::class.java).putExtra(WingerVpnService.EXTRA_CONFIG, cfg))
    }
    fun connect(config: String) {
        pendingConfig = config
        val prep = VpnService.prepare(this)
        if (prep != null) vpnPerm.launch(prep)
        else startForegroundService(Intent(this, WingerVpnService::class.java).putExtra(WingerVpnService.EXTRA_CONFIG, config))
    }
    fun disconnect() {
        Store.running(this, false)
        startService(Intent(this, WingerVpnService::class.java).setAction("STOP"))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pro.wingerapp.vpn.net.Api.init(this)
        setContent { App(this, hwid(), ::connect, ::disconnect) }
    }
    private fun hwid() = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        ?.padEnd(16, '0')?.take(32) ?: "winger-android-device"
}

@Composable
fun App(ctx: Context, hwid: String, connect: (String) -> Unit, disconnect: () -> Unit) {
    var authed by remember { mutableStateOf(false) }
    if (!authed) Login(ctx, disconnect) { authed = true } else Home(hwid, connect, disconnect)
}

@Composable
fun Login(ctx: Context, disconnect: () -> Unit, onOk: () -> Unit) {
    var user by remember { mutableStateOf(Store.login(ctx)) }
    var pass by remember { mutableStateOf(Store.password(ctx)) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (user.isBlank() || pass.isBlank()) return@LaunchedEffect
        busy = true
        val e = withContext(Dispatchers.IO) {
            runCatching {
                if (Api.subscription() != null) return@runCatching null
                Api.login(user, pass)
            }.getOrElse { it.message ?: "нет сети" }
        }
        busy = false
        if (e == null) onOk() else err = e
    }
    Column(
        Modifier.fillMaxSize().background(Bg).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Winger", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Вход в аккаунт", color = Muted, modifier = Modifier.padding(bottom = 20.dp))
        Field(user, "Логин") { user = it }
        Spacer(Modifier.height(10.dp))
        Field(pass, "Пароль", true) { pass = it }
        err?.let { Text(it, color = Color(0xFFF87171), modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(Purple).clickable(enabled = !busy) {
                    busy = true; err = null
                    scope.launch {
                        val e = withContext(Dispatchers.IO) { runCatching { Api.login(user, pass) }.getOrElse { it.message } }
                        busy = false
                        if (e == null) { Store.save(ctx, user, pass); onOk() } else err = e
                    }
                }.padding(14.dp),
            contentAlignment = Alignment.Center
        ) { Text(if (busy) "Входим…" else "Войти", color = Color.White, fontWeight = FontWeight.SemiBold) }
        if (Store.running(ctx)) {
            Box(
                Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(16.dp))
                    .background(Elev).border(1.dp, Purple.copy(.6f), RoundedCornerShape(16.dp))
                    .clickable { disconnect() }.padding(14.dp),
                contentAlignment = Alignment.Center
            ) { Text("Выключить VPN", color = Color.White, fontWeight = FontWeight.SemiBold) }
        }
    }
}

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
fun Home(hwid: String, connect: (String) -> Unit, disconnect: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var on by remember { mutableStateOf(Store.running(ctx)) }
    var pinging by remember { mutableStateOf(false) }
    var sub by remember { mutableStateOf<Sub?>(null) }
    var raw by remember { mutableStateOf("") }
    var servers by remember { mutableStateOf(listOf<ServerNode>()) }
    var pings by remember { mutableStateOf(mapOf<String, Int?>()) }
    var ignoreGeo by remember { mutableStateOf(false) }
    var place by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                val s = Api.subscription()
                sub = s
                if (s != null && s.url.isNotBlank()) {
                    val cfg = readHapp(Api.fetchHapp(s.url, hwid))
                    raw = cfg.raw
                    servers = cfg.servers
                    place = cfg.servers.first().name
                    pings = cfg.servers.associate { it.name to tcpPing(it.host, it.port) }
                }
            }.onFailure { err = it.message }
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        if (tab == 0) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Box(Modifier.fillMaxWidth().height(210.dp), contentAlignment = Alignment.Center) {
                    if (on) SiteParticles()
                    Box(
                        Modifier.size(168.dp).shadow(10.dp, CircleShape).clip(CircleShape)
                            .background(Brush.radialGradient(if (on) listOf(Color(0xFF2A2458), Color(0xFF101A30)) else listOf(Color(0xFF1C3D58), Color(0xFF0A1628))))
                            .border(1.dp, if (on) Purple.copy(.7f) else Color(0xFF2A5A74), CircleShape)
                            .clickable {
                                if (on) { disconnect(); on = false; return@clickable }
                                if (raw.isBlank() || place.isBlank()) return@clickable
                                connect(xrayConfigFor(raw, place, ignoreGeo))
                                on = true
                                Store.running(ctx, true)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painterResource(R.drawable.eagle), "Winger",
                            modifier = Modifier.size(112.dp).alpha(if (on) 1f else 0.5f),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Color(0xFFC4B5FD))) { append("Тариф ") }
                        withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) {
                            append(sub?.name ?: "…")
                        }
                        withStyle(SpanStyle(color = Muted)) { append(" · осталось ") }
                        withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) { append(daysLeft(sub?.expires)) }
                    },
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 13.sp
                )
                err?.let { Text(it, color = Color(0xFFF87171), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat(if ((sub?.devices ?: 0) <= 0) "безлимит" else sub!!.devices.toString(), "устройства")
                    Stat(if (on) "VPN" else "—", "статус")
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .background(Brush.verticalGradient(listOf(Color(0xFF3A6D8C), Color(0xFF1B3D56))))
                            .border(1.dp, Color(0xFF2A4A66), RoundedCornerShape(16.dp))
                            .clickable(enabled = servers.isNotEmpty() && !pinging) {
                                pinging = true
                                scope.launch {
                                    pings = withContext(Dispatchers.IO) { servers.associate { it.name to tcpPing(it.host, it.port) } }
                                    pinging = false
                                }
                            }.padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("пинг", color = Color(0xFFD7F3FB), fontSize = 11.sp)
                        if (pinging) CircularProgressIndicator(Modifier.padding(top = 6.dp).size(14.dp), strokeWidth = 2.dp, color = Cyan)
                        else Spacer(Modifier.padding(top = 6.dp).size(14.dp).clip(CircleShape).border(2.dp, Cyan.copy(.5f), CircleShape))
                    }
                }
                Text("СЕРВЕРЫ", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
                if (servers.isEmpty()) Text("Загружаем подписку…", color = Dim, modifier = Modifier.padding(top = 8.dp))
                servers.forEach { node ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(16.dp)).background(Elev)
                            .border(1.dp, if (place == node.name) Purple.copy(.55f) else Line, RoundedCornerShape(16.dp))
                            .clickable {
                                place = node.name
                                connect(xrayConfigFor(raw, node.name, ignoreGeo))
                                on = true
                                Store.running(ctx, true)
                            }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(node.name, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (pinging) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Cyan)
                        else Text(pings[node.name]?.let { "$it мс" } ?: "…", color = Cyan, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(96.dp))
            }
        } else {
            val ctx = LocalContext.current
            var log by remember { mutableStateOf(VpnLog.text()) }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text("Аккаунт", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp))
                Text(sub?.name ?: "Подписка", color = Muted)
                Text("до ${sub?.expires?.take(10) ?: "—"}", color = Muted)
                Row(
                    Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (ignoreGeo) Purple.copy(.35f) else Elev)
                        .clickable { ignoreGeo = !ignoreGeo }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (ignoreGeo) "Гео-базы игнорируются" else "Гео-базы включены", color = Color.White, modifier = Modifier.weight(1f))
                    Text(if (ignoreGeo) "вкл" else "выкл", color = Cyan)
                }
                Text("Лог подключения", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp))
                Text(log.ifBlank { "пока пусто" }, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Elev).clickable { log = VpnLog.text() }.padding(10.dp)) {
                        Text("Обновить", color = Color.White)
                    }
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Purple).clickable {
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("winger", VpnLog.text()))
                    }.padding(10.dp)) { Text("Скопировать", color = Color.White) }
                }
                Spacer(Modifier.height(96.dp))
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth().height(64.dp)
                .clip(RoundedCornerShape(22.dp)).background(Color(0xE6081224))
                .border(1.dp, Line, RoundedCornerShape(22.dp)),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Главная", color = if (tab == 0) TabOn else TabOff, modifier = Modifier.clickable { tab = 0 }.padding(12.dp))
            Text("Аккаунт", color = if (tab == 1) TabOn else TabOff, modifier = Modifier.clickable { tab = 1 }.padding(12.dp))
        }
    }
}

private fun daysLeft(expires: String?): String {
    if (expires.isNullOrBlank()) return "—"
    return runCatching {
        val d = java.time.LocalDate.parse(expires.take(10))
        val n = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), d)
        "$n дней"
    }.getOrDefault("—")
}

@Composable
private fun RowScope.Stat(v: String, l: String) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Elev)
            .border(1.dp, Line, RoundedCornerShape(16.dp)).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(v, color = Cyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(l, color = Dim, fontSize = 11.sp)
    }
}

@Composable
private fun SiteParticles() {
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
