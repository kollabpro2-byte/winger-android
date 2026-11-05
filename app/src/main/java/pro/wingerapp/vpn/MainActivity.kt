package pro.wingerapp.vpn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.viewinterop.AndroidView
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
    var showRegister by remember { mutableStateOf(false) }
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
    if (showRegister) {
        RegisterWeb(ctx) { showRegister = false }
        return
    }
    Column(Modifier.fillMaxSize().background(Bg).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Winger", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Вход в аккаунт", color = Muted, modifier = Modifier.padding(bottom = 20.dp))
        Field(user, "Логин") { user = it }
        Spacer(Modifier.height(10.dp))
        Field(pass, "Пароль", true) { pass = it }
        err?.let { Text(it, color = Color(0xFFF87171), modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Purple)
                .clickable(enabled = !busy) {
                    busy = true; err = null
                    scope.launch {
                        val e = withContext(Dispatchers.IO) { runCatching { Api.login(user, pass) }.getOrElse { it.message } }
                        busy = false
                        if (e == null) { Store.save(ctx, user, pass); onOk() } else err = e
                    }
                }.padding(14.dp),
            contentAlignment = Alignment.Center
        ) { Text(if (busy) "Входим…" else "Войти", color = Color.White, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Elev)
                .border(1.dp, Purple.copy(.5f), RoundedCornerShape(16.dp))
                .clickable { showRegister = true }.padding(14.dp),
            contentAlignment = Alignment.Center
        ) { Text("Нет аккаунта? Зарегистрироваться", color = Color.White, fontWeight = FontWeight.SemiBold) }
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
private fun RegisterWeb(ctx: Context, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("← Назад", color = Cyan, modifier = Modifier.clickable { onClose() }.padding(8.dp))
            Spacer(Modifier.weight(1f))
            Text("Регистрация", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) WingerAndroid"
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            url ?: return false
                            if (url.contains("pay") || url.contains("payment") || url.contains("checkout") || url.contains("yookassa") || url.contains("cloudpayments")) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                return true
                            }
                            return false
                        }
                    }
                    loadUrl("https://my.wingerapp.pro")
                }
            },
            modifier = Modifier.fillMaxSize()
        )
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
    var refreshing by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf("00:00:00") }
    var sub by remember { mutableStateOf<Sub?>(null) }
    var raw by remember { mutableStateOf("") }
    var servers by remember { mutableStateOf(listOf<ServerNode>()) }
    var pings by remember { mutableStateOf(mapOf<String, Int?>()) }
    var ignoreGeo by remember { mutableStateOf(false) }
    var place by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun reload(ping: Boolean) {
        scope.launch {
            if (ping) { pinging = true; pings = emptyMap() } else refreshing = true
            withContext(Dispatchers.IO) {
                runCatching {
                    val s = Api.subscription()
                    sub = s
                    if (s != null && s.url.isNotBlank()) {
                        val cfg = readHapp(Api.fetchHapp(s.url, hwid))
                        raw = cfg.raw
                        servers = cfg.servers
                        if (place.isBlank()) place = cfg.servers.first().name
                        if (ping) pings = cfg.servers.associate { it.name to tcpPing(it.host, it.port) }
                    }
                }.onFailure { err = it.message }
            }
            pinging = false
            refreshing = false
        }
    }
    LaunchedEffect(Unit) { reload(true) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60 * 60 * 1000L)
            reload(false)
        }
    }
    LaunchedEffect(on) {
        if (!on) return@LaunchedEffect
        val startAt = System.currentTimeMillis()
        while (on) {
            val sec = ((System.currentTimeMillis() - startAt) / 1000).toInt()
            elapsed = "%02d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
            kotlinx.coroutines.delay(1000)
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        when (tab) {
            0 -> HomeTab(
                ctx = ctx, hwid = hwid, sub = sub, servers = servers, pings = pings, place = place,
                on = on, elapsed = elapsed, pinging = pinging, refreshing = refreshing, err = err,
                ignoreGeo = ignoreGeo, raw = raw, connect = connect, disconnect = disconnect, reload = reload,
                onSelect = { name ->
                    place = name
                    connect(xrayConfigFor(raw, name, ignoreGeo))
                    on = true
                    Store.running(ctx, true)
                },
                onToggle = {
                    if (on) { disconnect(); on = false }
                    else if (raw.isNotBlank() && place.isNotBlank()) {
                        connect(xrayConfigFor(raw, place, ignoreGeo))
                        on = true
                        Store.running(ctx, true)
                    }
                }
            )
            1 -> AccountTab(ctx, sub)
            2 -> SettingsTab(ctx, ignoreGeo) { ignoreGeo = it }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth().height(60.dp)
                .clip(RoundedCornerShape(20.dp)).background(Color(0xE6081224))
                .border(1.dp, Line, RoundedCornerShape(20.dp)),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
        ) {
            NavItem("Главная", tab == 0) { tab = 0 }
            NavItem("Аккаунт", tab == 1) { tab = 1 }
            NavItem("Настройки", tab == 2) { tab = 2 }
        }
    }
}

@Composable
private fun NavItem(label: String, active: Boolean, onClick: () -> Unit) {
    Text(label, color = if (active) TabOn else TabOff, fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clickable { onClick() }.padding(horizontal = 16.dp, vertical = 12.dp))
}

@Composable
private fun HomeTab(
    ctx: Context, hwid: String, sub: Sub?, servers: List<ServerNode>, pings: Map<String, Int?>,
    place: String, on: Boolean, elapsed: String, pinging: Boolean, refreshing: Boolean, err: String?,
    ignoreGeo: Boolean, raw: String, connect: (String) -> Unit, disconnect: () -> Unit, reload: (Boolean) -> Unit,
    onSelect: (String) -> Unit, onToggle: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 12.dp)) {
            Text("WINGER", color = Cyan, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
            Text(" v0.1.19", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
        }
        if (sub == null) {
            Text("Нужно приобрести подписку", color = Color(0xFFF87171), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
            Text("Перейдите во вкладку Аккаунт", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (on) Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("↑ —", color = Color(0xFF7EE0B0), fontSize = 14.sp)
            Text(elapsed, color = Muted, fontSize = 14.sp)
            Text("↓ —", color = Color(0xFF9ECBFF), fontSize = 14.sp)
        } else Spacer(Modifier.height(22.dp))
        if (!on) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("нажмите на ", color = Color(0xFFC5D0DC), fontSize = 16.sp)
            Image(painterResource(R.drawable.eagle), "орёл", Modifier.size(16.dp))
            Text(" для подключения", color = Color(0xFFC5D0DC), fontSize = 16.sp)
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
                withStyle(SpanStyle(color = Color(0xFFC4B5FD))) { append("Тариф ") }
                withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) { append(sub?.name ?: "…") }
                withStyle(SpanStyle(color = Muted)) { append(" · осталось ") }
                withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) { append(daysLeft(sub?.expires)) }
            },
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 13.sp
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
            Text("Устройства ", color = Muted, fontSize = 12.sp)
            Text("${sub?.devicesUsed ?: 0} из ${if ((sub?.devices ?: 0) <= 0) "∞" else sub!!.devices}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("    Трафик ", color = Muted, fontSize = 12.sp)
            Text(trafficText(sub), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        err?.let { Text(it, color = Color(0xFFF87171), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(Elev).border(1.dp, Color(0xFF2A4A66), CircleShape).clickable { reload(false) }, contentAlignment = Alignment.Center) {
                if (refreshing) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Cyan)
                else Text("⟳", color = Color(0xFFC9D7E4), fontSize = 16.sp)
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(32.dp).clip(CircleShape).background(Elev).border(1.dp, Color(0xFF2A4A66), CircleShape).clickable(enabled = !pinging) { reload(true) }, contentAlignment = Alignment.Center) {
                Text("⚡", color = Color(0xFFC9D7E4), fontSize = 14.sp)
            }
        }
        if (servers.isEmpty()) Text("Загружаем подписку…", color = Dim, modifier = Modifier.padding(top = 8.dp))
        Column(Modifier.alpha(if (refreshing || pinging) 0.35f else 1f)) {
            grouped(servers).forEach { (title, nodes) ->
                Text(title, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                nodes.forEach { node ->
                    val selected = place == node.name
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) Purple.copy(.22f) else Elev)
                            .border(1.dp, if (selected) Purple.copy(.7f) else Line, RoundedCornerShape(14.dp))
                            .clickable { onSelect(node.name) }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(node.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("VLESS · TCP · REALITY · JSON", color = Muted, fontSize = 11.sp)
                        }
                        if (pinging) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Cyan)
                        else {
                            val ms = pings[node.name]
                            val c = when { ms == null -> Muted; ms < 120 -> Color(0xFF7EE0B0); ms < 200 -> Color(0xFFF0C14B); else -> Color(0xFFF07171) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(c))
                                Text(ms?.toString() ?: "—", color = c, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun AccountTab(ctx: Context, sub: Sub?) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Аккаунт", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Purple.copy(.3f)).border(1.dp, Purple, CircleShape)
                    .clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://my.wingerapp.pro"))) },
                contentAlignment = Alignment.Center
            ) { Text("🎁", fontSize = 16.sp) }
        }
        if (sub == null) {
            Text("Подписка не активна", color = Color(0xFFF87171), modifier = Modifier.padding(horizontal = 16.dp))
        }
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) WingerAndroid"
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            url ?: return false
                            if (url.contains("pay") || url.contains("payment") || url.contains("checkout") || url.contains("yookassa") || url.contains("cloudpayments")) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                return true
                            }
                            return false
                        }
                    }
                    injectCookies()
                    loadUrl("https://my.wingerapp.pro")
                }
            },
            modifier = Modifier.fillMaxSize().padding(bottom = 72.dp)
        )
    }
}

@Composable
private fun SettingsTab(ctx: Context, ignoreGeo: Boolean, onGeo: (Boolean) -> Unit) {
    var log by remember { mutableStateOf(VpnLog.text()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Настройки", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(14.dp))
                .background(if (ignoreGeo) Purple.copy(.35f) else Elev)
                .clickable { onGeo(!ignoreGeo) }.padding(12.dp),
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
        Spacer(Modifier.height(80.dp))
    }
}

private fun injectCookies() {
    val cm = CookieManager.getInstance()
    cm.setAcceptCookie(true)
    val raw = Store.cookies(Api.app)
    if (raw.isBlank()) return
    raw.lineSequence().filter { it.contains("|") }.forEach { line ->
        val p = line.split("|", limit = 3)
        if (p.size < 3) return@forEach
        val domain = p[0].ifBlank { "my.wingerapp.pro" }
        val cookie = "${p[1]}=${p[2]}; domain=$domain; path=/"
        cm.setCookie("https://$domain/", cookie)
    }
    cm.flush()
}

private fun grouped(servers: List<ServerNode>): List<Pair<String, List<ServerNode>>> {
    val auto = servers.filter { it.name.contains("Автопилот", true) || it.name.contains("рекоменд", true) }
    val yt = servers.filter { it.name.contains("youtube", true) || it.name.contains("ютуб", true) }
    val lte = servers.filter { it.name.contains("LTE", true) || it.name.contains("обход", true) }
    val rest = servers.filter { it !in auto && it !in yt && it !in lte }
    return listOf("Рекомендуемые" to auto, "Страны" to rest, "YouTube" to yt, "Обходы" to lte).filter { it.second.isNotEmpty() }
}

private fun trafficText(sub: Sub?): String {
    if (sub == null) return "—"
    val used = "%.1f".format(sub.trafficUsedGb).replace('.', ',')
    val total = if (sub.unlimitedTraffic || sub.trafficLimitGb <= 0) "∞" else "%.0f ГБ".format(sub.trafficLimitGb)
    return "$used ГБ из $total"
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