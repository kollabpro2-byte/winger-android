package pro.wingerapp.vpn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import libv2ray.Libv2ray
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
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        VpnLog.init(this)
        VpnLog.add("app start")
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
        RegisterForm(onClose = { showRegister = false }) { login, pass ->
            Store.save(ctx, login, pass)
            onOk()
        }
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

private const val CUSTOM_TABS = "android.support.customtabs.action.CustomTabsService"

private fun browserPackage(ctx: Context): String? {
    val pm = ctx.packageManager
    val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://my.wingerapp.pro"))
    val handlers = pm.queryIntentActivities(view, 0)
        .map { it.activityInfo.packageName }
        .distinct()
    return handlers.firstOrNull { hasCustomTabs(ctx, it) } ?: handlers.firstOrNull()
}

private fun hasCustomTabs(ctx: Context, pkg: String): Boolean {
    val svc = Intent(CUSTOM_TABS).setPackage(pkg)
    return ctx.packageManager.resolveService(svc, 0) != null
}

private fun hasBrowser(ctx: Context) = browserPackage(ctx) != null

private fun openCabinet(ctx: Context, url: String = "https://my.wingerapp.pro") {
    val pkg = browserPackage(ctx)
    val uri = Uri.parse(url)
    VpnLog.add("openCabinet: $url browser=$pkg")
    if (pkg != null && hasCustomTabs(ctx, pkg)) {
        try {
            val intent = CustomTabsIntent.Builder().setShowTitle(true).build().intent
            intent.setPackage(pkg)
            intent.data = uri
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            VpnLog.add("CustomTabs launched: $pkg")
            return
        } catch (e: Exception) {
            VpnLog.add("CustomTabs failed: ${e.message}")
        }
    }
    try {
        val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) view.setPackage(pkg)
        ctx.startActivity(view)
        VpnLog.add("External browser launched: $pkg")
    } catch (e: Exception) {
        VpnLog.add("External browser failed: ${e.message}")
    }
}

@Composable
private fun RegisterForm(onClose: () -> Unit, onDone: (String, String) -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(Bg).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("← Назад", color = Cyan, modifier = Modifier.clickable { onClose() }.padding(bottom = 16.dp))
        Text("Регистрация", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Аккаунт создаётся сразу, без браузера", color = Muted, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
        Field(user, "Логин") { user = it }
        Spacer(Modifier.height(10.dp))
        Field(pass, "Пароль", true) { pass = it }
        err?.let { Text(it, color = Color(0xFFF87171), modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Purple)
                .clickable(enabled = !busy) {
                    busy = true
                    err = null
                    scope.launch {
                        val e = withContext(Dispatchers.IO) { runCatching { Api.register(user, pass) }.getOrElse { it.message } }
                        busy = false
                        if (e == null) onDone(user.trim(), pass) else err = e
                    }
                }.padding(14.dp),
            contentAlignment = Alignment.Center
        ) { Text(if (busy) "Создаю…" else "Создать аккаунт", color = Color.White, fontWeight = FontWeight.SemiBold) }
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
    var notice by remember { mutableStateOf<String?>(null) }
    var chat by remember { mutableStateOf(false) }
    var up by remember { mutableStateOf("0 Б") }
    var down by remember { mutableStateOf("0 Б") }
    val scope = rememberCoroutineScope()
    fun cfg(name: String) = xrayConfigFor(raw, name, ignoreGeo, Store.dnsServer(ctx), Store.bypass(ctx))
    val reload: (Boolean) -> Unit = { ping ->
        scope.launch {
            if (ping) { pinging = true; pings = emptyMap() } else refreshing = true
            withContext(Dispatchers.IO) {
                runCatching {
                    val s = Api.subscription()
                    sub = s
                    if (s != null && s.url.isNotBlank()) {
                        val body = readHapp(Api.fetchHapp(s.url, hwid))
                        raw = body.raw
                        servers = body.servers
                        if (place.isBlank()) place = body.servers.first().name
                        body
                    } else null
                }.onFailure { err = it.message }.getOrNull()
            }?.let { body ->
                if (!ping) {
                    notice = "Подписка обновлена"
                    scope.launch {
                        kotlinx.coroutines.delay(2600)
                        if (notice == "Подписка обновлена") notice = null
                    }
                } else {
                    Libv2ray.initCoreEnv(ctx.filesDir.absolutePath, "")
                    val http = Store.pingHttp(ctx)
                    val url = Store.testUrl(ctx)
                    coroutineScope {
                        body.servers.map { node ->
                            async(Dispatchers.IO) {
                                val ms = if (http) {
                                    val configs = delayConfigs(body.raw, node.name)
                                    if (configs.isEmpty()) null
                                    else coroutineScope { configs.map { cfg -> async { httpPing(cfg, url) } }.awaitAll().filterNotNull().minOrNull() }
                                } else {
                                    val points = endpointsOf(body.raw, node.name)
                                    if (points.isEmpty()) null
                                    else coroutineScope { points.map { point -> async { tcpPing(point.host, point.port) } }.awaitAll().filterNotNull().minOrNull() }
                                }
                                withContext(Dispatchers.Main) { pings = pings + (node.name to ms) }
                            }
                        }.awaitAll()
                    }
                }
            }
            pinging = false
            refreshing = false
        }
    }
    LaunchedEffect(Unit) { reload(true) }
    DisposableEffect(Unit) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { reload(true) }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        onDispose { runCatching { cm.unregisterNetworkCallback(cb) } }
    }
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
            val tun = tunTraffic()
            val rx = tun?.first ?: Store.coreDown(ctx)
            val tx = tun?.second ?: Store.coreUp(ctx)
            down = fmtBytes(rx)
            up = fmtBytes(tx)
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        when (tab) {
            0 -> HomeTab(
                ctx = ctx, hwid = hwid, sub = sub, servers = servers, pings = pings, place = place,
                on = on, elapsed = elapsed, up = up, down = down, pinging = pinging, refreshing = refreshing, err = err, notice = notice,
                ignoreGeo = ignoreGeo, raw = raw, connect = connect, disconnect = disconnect, reload = reload,
                onSelect = { name ->
                    place = name
                    connect(cfg(name))
                    on = true
                    Store.running(ctx, true)
                },
                onToggle = {
                    if (on) { disconnect(); on = false }
                    else if (raw.isNotBlank() && place.isNotBlank()) {
                        connect(cfg(place))
                        on = true
                        Store.running(ctx, true)
                    }
                }
            )
            1 -> AccountTab(ctx, sub) { chat = true }
            2 -> SettingsTab(ctx, ignoreGeo, { ignoreGeo = it }) {
                if (on && raw.isNotBlank() && place.isNotBlank()) connect(cfg(place))
            }
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
        if (chat) SupportChat(ctx) { chat = false }
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
    place: String, on: Boolean, elapsed: String, up: String, down: String, pinging: Boolean, refreshing: Boolean, err: String?, notice: String?,
    ignoreGeo: Boolean, raw: String, connect: (String) -> Unit, disconnect: () -> Unit, reload: (Boolean) -> Unit,
    onSelect: (String) -> Unit, onToggle: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 12.dp)) {
            Text("WINGER", color = Cyan, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
            Text(" v0.1.31", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
        }
        if (sub == null) {
            Text("Нужно приобрести подписку", color = Color(0xFFF87171), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
            Text("Перейдите во вкладку Аккаунт", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (on) Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("↑ $up", color = Color(0xFF7EE0B0), fontSize = 14.sp)
            Text(elapsed, color = Muted, fontSize = 14.sp)
            Text("↓ $down", color = Color(0xFF9ECBFF), fontSize = 14.sp)
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
        if (servers.isEmpty()) Text("Загружаем подписку…", color = Dim, modifier = Modifier.padding(top = 8.dp))
        Column {
            grouped(servers).forEach { (title, nodes) ->
                Text(title, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                nodes.forEach { node ->
                    val selected = place == node.name
                    val own = if (pings.containsKey(node.name)) pings[node.name] else null
                    val bestOther = pings.filterKeys { !it.contains("Автопилот", true) }.values.filterNotNull().minOrNull()
                    val ms = if (node.name.contains("Автопилот", true)) listOfNotNull(own, bestOther).minOrNull() else own
                    val waiting = ms == null && pinging && (node.name.contains("Автопилот", true) || !pings.containsKey(node.name))
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
                            Text("VLESS · TCP · REALITY · JSON", color = Muted, fontSize = 10.sp)
                        }
                        if (waiting || (ms == null && !pings.containsKey(node.name) && !node.name.contains("Автопилот", true))) {
                            if (waiting) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Cyan)
                        } else {
                            val ms = pings[node.name]
                            val c = when { ms == null -> Color(0xFFF07171); ms < 120 -> Color(0xFF7EE0B0); ms < 200 -> Color(0xFFF0C14B); else -> Color(0xFFF07171) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(c))
                                Text(if (ms == null) "нет" else ms.toString(), color = c, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
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
private fun AccountTab(ctx: Context, sub: Sub?, onSupport: () -> Unit) {
    var cab by remember { mutableStateOf<pro.wingerapp.vpn.net.Cabinet?>(null) }
    var note by remember { mutableStateOf("") }
    var paying by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var ways by remember { mutableStateOf(listOf<pro.wingerapp.vpn.net.PayWay>()) }
    var picked by remember { mutableStateOf(30) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var thread by remember { mutableStateOf<pro.wingerapp.vpn.net.SupportThread?>(null) }
    var draft by remember { mutableStateOf("") }
    var files by remember { mutableStateOf(listOf<pro.wingerapp.vpn.net.Attached>()) }
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            val added = withContext(Dispatchers.IO) { uris.mapNotNull { readAttachment(ctx, it) } }
            files = files + added
        }
    }
    fun load() {
        scope.launch {
            val c = withContext(Dispatchers.IO) { runCatching { Api.cabinet() }.getOrNull() }
            cab = c
            if (c?.telegram != null) withContext(Dispatchers.IO) { runCatching { Api.claimTelegramGift() } }
            if (c != null && c.offers.none { it.days == picked }) picked = c.offers.firstOrNull()?.days ?: picked
        }
    }
    fun bindTelegram() {
        scope.launch {
            note = ""
            val url = withContext(Dispatchers.IO) { runCatching { Api.telegramLink() }.getOrElse { it.message ?: "Telegram не открылся" } }
            if (!url.startsWith("http")) { note = url; return@launch }
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            repeat(20) {
                kotlinx.coroutines.delay(3000)
                val fresh = withContext(Dispatchers.IO) { runCatching { Api.cabinet() }.getOrNull() } ?: return@repeat
                cab = fresh
                if (fresh.telegram != null) {
                    withContext(Dispatchers.IO) { runCatching { Api.claimTelegramGift() } }
                    load()
                    note = "Telegram привязан, +3 дня начислены"
                    return@launch
                }
            }
        }
    }
    LaunchedEffect(Unit) { load() }
    val c = cab
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 96.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("АККАУНТ", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp)
            Text(c?.login ?: if (sub == null) "нет входа" else "…", color = Color.White, fontSize = 13.sp)
        }
        if (note.isNotBlank()) Text(note, color = Color(0xFFF87171), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            if (c != null) {
            if (c.telegram == null) {
                Section {
                    Text("TELEGRAM", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp)
                    Text("+3 дня к подписке", color = Color(0xFF86EFAC), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                    Text("Привяжите Telegram и заберите бонус", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    Box(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(24.dp)).background(Purple).clickable { bindTelegram() }.padding(13.dp), contentAlignment = Alignment.Center) {
                        Text("Привязать Telegram", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Section {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("TELEGRAM", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp)
                        Text("привязан", color = Color(0xFF86EFAC), fontSize = 13.sp)
                    }
                }
            }
            Section {
                Text("ПОДПИСКА", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp)
                Text(if (c.expires.isBlank()) "нет" else daysLeft(c.expires).substringBefore(" "), color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Text(c.plan, color = Color(0xFFC4B5FD), fontSize = 14.sp)
                val active = c.status == "ACTIVE"
                Text(if (active) "активна" else if (c.status == "NONE") "нет подписки" else c.status.lowercase(), color = if (active) Color(0xFF86EFAC) else Color(0xFFF87171), fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(20.dp)).background(if (active) Color(0x2434C759) else Color(0x22F87171)).padding(horizontal = 10.dp, vertical = 4.dp))
            }
            Section {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Column { Text("УСТРОЙСТВА", color = Color(0xFF7C8A9B), fontSize = 11.sp); Text("${c.devicesUsed} из ${c.deviceLimit}", color = Color.White, modifier = Modifier.padding(top = 4.dp)) }
                    Column { Text("ТРАФИК", color = Color(0xFF7C8A9B), fontSize = 11.sp); Text(c.traffic, color = Color.White, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
            if (c.offers.isNotEmpty()) {
                val offer = c.offers.firstOrNull { it.days == picked } ?: c.offers.first()
                Section {
                    Text(if (c.subscriptionId.isBlank()) "ОПЛАТА" else "ПРОДЛЕНИЕ", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp)
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("дни", color = Color(0xFF7C8A9B), fontSize = 12.sp, modifier = Modifier.width(42.dp))
                        c.offers.forEach { item ->
                            val on = item.days == picked
                            Box(Modifier.weight(1f).padding(horizontal = 3.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Purple.copy(.22f) else Color.White.copy(.03f)).border(1.dp, if (on) Purple.copy(.6f) else Line, RoundedCornerShape(12.dp)).clickable { picked = item.days; choosing = false }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                                Text("${item.days}", color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("цена", color = Color(0xFF7C8A9B), fontSize = 12.sp, modifier = Modifier.width(42.dp))
                        c.offers.forEach { item ->
                            val on = item.days == picked
                            Box(Modifier.weight(1f).padding(horizontal = 3.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Purple.copy(.22f) else Color.White.copy(.03f)).border(1.dp, if (on) Purple.copy(.6f) else Line, RoundedCornerShape(12.dp)).clickable { picked = item.days; choosing = false }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                                Text("${item.price} ₽", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                    if (!choosing) {
                        Box(Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(24.dp)).background(Purple).clickable {
                            scope.launch {
                                ways = withContext(Dispatchers.IO) { runCatching { Api.payWays() }.getOrDefault(emptyList()) }
                                choosing = true
                            }
                        }.padding(13.dp), contentAlignment = Alignment.Center) { Text("Продлить", color = Color.White, fontWeight = FontWeight.SemiBold) }
                    } else ways.forEach { way ->
                        val amount = offer.amount(way.gateway)
                        Column(Modifier.fillMaxWidth().padding(top = 10.dp).clickable(enabled = !paying) {
                            paying = true
                            note = ""
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { runCatching { Api.pay(c.subscriptionId, c.planId, offer.days, amount, way.gateway, way.autopay) }.getOrElse { it.message ?: "ошибка" } }
                                paying = false
                                if (result.startsWith("http")) {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    choosing = false
                                } else note = result
                            }
                        }.padding(vertical = 8.dp)) {
                            Text(way.title, color = Color.White)
                            Text("$amount ₽ · ${way.hint}", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
            Section {
                Text("УСТРОЙСТВА", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp)
                c.devices.forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.title, color = Color.White, fontSize = 14.sp)
                            Text(d.seen, color = Muted, fontSize = 12.sp)
                        }
                        Text(if (confirm == d.hwid) "убрать" else "✕", color = Color(0xFFF87171), modifier = Modifier.clickable {
                            if (confirm == d.hwid) {
                                confirm = null
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { Api.removeDevice(d.hwid) }
                                    if (ok) load() else note = "не удалось удалить устройство"
                                }
                            } else confirm = d.hwid
                        }.padding(8.dp))
                    }
                }
            }
            Section {
                Row(Modifier.fillMaxWidth().clickable { onSupport() }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("ПОДДЕРЖКА", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp)
                    Text("открыть", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Purple).padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
    }
}


@Composable
private fun SupportChat(ctx: Context, onClose: () -> Unit) {
    var thread by remember { mutableStateOf<pro.wingerapp.vpn.net.SupportThread?>(null) }
    var draft by remember { mutableStateOf("") }
    var files by remember { mutableStateOf(listOf<pro.wingerapp.vpn.net.Attached>()) }
    var images by remember { mutableStateOf(mapOf<String, android.graphics.Bitmap>()) }
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            val added = withContext(Dispatchers.IO) { uris.mapNotNull { readAttachment(ctx, it) } }
            files = files + added
        }
    }
    fun load() {
        scope.launch {
            val next = withContext(Dispatchers.IO) { runCatching { Api.supportThread() }.getOrNull() }
            thread = next
            val id = next?.id ?: return@launch
            val missing = next.lines.flatMap { it.files }.filter { file -> file.id !in images && (file.mime.startsWith("image/") || file.name.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif")) }
            if (missing.isEmpty()) return@launch
            val loaded = withContext(Dispatchers.IO) {
                missing.mapNotNull { file ->
                    val bytes = Api.attachmentBytes(id, file) ?: return@mapNotNull null
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@mapNotNull null
                    file.id to bmp
                }
            }
            images = images + loaded
        }
    }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(thread?.id) {
        while (true) {
            kotlinx.coroutines.delay(4000)
            load()
        }
    }
    LaunchedEffect(thread?.lines?.size, images.size) { scroll.scrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Поддержка", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("Закрыть", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Purple).clickable { onClose() }.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        if (note.isNotBlank()) Text(note, color = Color(0xFFF87171), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp)) {
            val lines = thread?.lines.orEmpty()
            if (lines.isEmpty()) Text("Напишите сообщение, и откроется новый тикет.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            lines.forEach { line ->
                val mine = line.author == "Вы"
                Column(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                    Text(line.author, color = Muted, fontSize = 11.sp)
                    if (line.text.isNotBlank()) {
                        Text(line.text, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp).widthIn(max = 280.dp).clip(RoundedCornerShape(16.dp)).background(if (mine) Purple.copy(.45f) else Elev).padding(12.dp))
                    }
                    line.files.forEach { file ->
                        val bmp = images[file.id]
                        if (bmp != null) Image(bmp.asImageBitmap(), file.name, Modifier.padding(top = 6.dp).widthIn(max = 240.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.FillWidth)
                        else Text(file.name, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(if (mine) Purple.copy(.45f) else Elev).padding(horizontal = 10.dp, vertical = 8.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Column(Modifier.fillMaxWidth().background(Color(0xFF06101C)).padding(12.dp)) {
            if (files.isNotEmpty()) Text(files.joinToString { it.name }, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📎", fontSize = 22.sp, modifier = Modifier.clickable { pick.launch(arrayOf("image/png", "image/jpeg", "image/webp", "application/pdf")) }.padding(6.dp))
                OutlinedTextField(draft, { draft = it }, placeholder = { Text("Сообщение") }, modifier = Modifier.weight(1f), colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = Elev, unfocusedContainerColor = Elev, focusedIndicatorColor = Purple, unfocusedIndicatorColor = Line))
                Text("➤", color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp).clip(CircleShape).background(Purple).clickable(enabled = !sending) {
                    if (draft.isBlank() && files.isEmpty()) return@clickable
                    sending = true
                    scope.launch {
                        val err = withContext(Dispatchers.IO) { runCatching { Api.sendSupport(thread?.id, draft.trim(), files) }.getOrElse { it.message } }
                        sending = false
                        if (err == null) { draft = ""; files = emptyList(); note = ""; load() } else note = err
                    }
                }.padding(10.dp))
            }
        }
    }
}

@Composable
private fun Section(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(22.dp)).background(Elev).border(1.dp, Line, RoundedCornerShape(22.dp)).padding(16.dp), content = content)
}

@Composable
private fun SettingsTab(ctx: Context, ignoreGeo: Boolean, onGeo: (Boolean) -> Unit, onApply: () -> Unit) {
    var log by remember { mutableStateOf(VpnLog.text()) }
    var http by remember { mutableStateOf(Store.pingHttp(ctx)) }
    var url by remember { mutableStateOf(Store.testUrl(ctx)) }
    var dns by remember { mutableStateOf(Store.dnsMode(ctx)) }
    var custom by remember { mutableStateOf(Store.dnsCustom(ctx)) }
    var hosts by remember { mutableStateOf(Store.bypass(ctx)) }
    var site by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 96.dp)) {
        Text("НАСТРОЙКИ", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp)
        Text("ПРОВЕРКА ПИНГА", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 18.dp))
        Row(Modifier.padding(top = 8.dp)) {
            Seg("TCP", !http, Modifier.weight(1f)) { http = false; Store.pingHttp(ctx, false) }
            Seg("HTTP GET", http, Modifier.weight(1f)) { http = true; Store.pingHttp(ctx, true) }
        }
        Text("URL-ТЕСТ", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 16.dp))
        OutlinedTextField(url, { url = it; Store.testUrl(ctx, it) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = Elev, unfocusedContainerColor = Elev, focusedIndicatorColor = Purple, unfocusedIndicatorColor = Line))
        Text("DNS", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 16.dp))
        Row(Modifier.padding(top = 8.dp)) {
            listOf("auto" to "Авто", "cf" to "1.1.1.1", "gg" to "8.8.8.8", "own" to "Свой").forEach { (id, label) ->
                Seg(label, dns == id, Modifier.weight(1f)) { dns = id; Store.dnsMode(ctx, id); onApply() }
            }
        }
        if (dns == "own") OutlinedTextField(custom, {
            custom = it
            Store.dnsCustom(ctx, it)
            if (it.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) onApply()
        }, placeholder = { Text("9.9.9.9") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = Elev, unfocusedContainerColor = Elev, focusedIndicatorColor = Purple, unfocusedIndicatorColor = Line))
        Text("БЕЗ ТУННЕЛЯ", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 16.dp))
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(site, { site = it }, placeholder = { Text("bank.ru") }, modifier = Modifier.weight(1f), singleLine = true, colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = Elev, unfocusedContainerColor = Elev, focusedIndicatorColor = Purple, unfocusedIndicatorColor = Line))
            Text("добавить", color = Color.White, modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(12.dp)).background(Purple).clickable {
                val host = site.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore("/")
                if (host.isNotBlank() && host !in hosts) { hosts = hosts + host; Store.bypass(ctx, hosts); site = ""; onApply() }
            }.padding(horizontal = 12.dp, vertical = 12.dp))
        }
        hosts.forEach { host ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(host, color = Color.White)
                Text("убрать", color = Color(0xFFF87171), modifier = Modifier.clickable { hosts = hosts - host; Store.bypass(ctx, hosts); onApply() })
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 18.dp).clip(RoundedCornerShape(14.dp)).background(if (ignoreGeo) Purple.copy(.35f) else Elev).clickable { onGeo(!ignoreGeo) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (ignoreGeo) "Гео-базы игнорируются" else "Гео-базы включены", color = Color.White, modifier = Modifier.weight(1f))
            Text(if (ignoreGeo) "вкл" else "выкл", color = Cyan)
        }
        Text("ЛОГ", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 16.dp))
        Text(log.ifBlank { "пока пусто" }, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Elev).clickable { log = VpnLog.text() }.padding(10.dp)) { Text("Обновить", color = Color.White) }
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Purple).clickable {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("winger", VpnLog.text()))
            }.padding(10.dp)) { Text("Скопировать", color = Color.White) }
        }
    }
}

@Composable
private fun Seg(label: String, on: Boolean, modifier: Modifier, click: () -> Unit) {
    Box(modifier.padding(horizontal = 2.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Purple.copy(.28f) else Elev).clickable { click() }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

private fun cabinetWebView(context: Context, tag: String): WebView {
    return WebView(context).apply {
        setBackgroundColor(0xFF040D1A.toInt())
        setLayerType(android.view.View.LAYER_TYPE_NONE, null)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                if (msg != null) VpnLog.add("$tag js: ${msg.message()} @${msg.lineNumber()}")
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                VpnLog.add("$tag start: $url")
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                VpnLog.add("$tag html loaded: $url")
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) VpnLog.add("$tag error ${error.errorCode}: ${error.description} (${request.url})")
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                VpnLog.add("$tag navigate: $url")
                if (url.contains("pay") || url.contains("payment") || url.contains("checkout") || url.contains("yookassa") || url.contains("cloudpayments")) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    return true
                }
                return false
            }
        }
        VpnLog.add("$tag loadUrl")
        injectCookies(this)
        loadUrl("https://my.wingerapp.pro")
    }
}

private fun injectCookies(web: WebView) {
    val cm = CookieManager.getInstance()
    cm.setAcceptCookie(true)
    cm.setAcceptThirdPartyCookies(web, true)
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
private fun readAttachment(ctx: Context, uri: Uri): pro.wingerapp.vpn.net.Attached? = runCatching {
    val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (c.moveToFirst() && i >= 0) c.getString(i) else null
    } ?: "file"
    val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    if (bytes.size > 8 * 1024 * 1024) return null
    pro.wingerapp.vpn.net.Attached(name, mime, bytes)
}.getOrNull()
