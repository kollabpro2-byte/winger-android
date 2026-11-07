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
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material3.Switch
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    private val vpnPerm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cfg = pendingConfig ?: return@registerForActivityResult
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            VpnLog.add("VPN-разрешение отклонено")
            TunnelState.fail("Разрешение VPN не выдано")
            return@registerForActivityResult
        }
        startForegroundService(Intent(this, WingerVpnService::class.java).putExtra(WingerVpnService.EXTRA_CONFIG, cfg))
    }
    fun connect(config: String) {
        pendingConfig = config
        TunnelState.clearError()
        val prep = VpnService.prepare(this)
        if (prep != null) vpnPerm.launch(prep)
        else startForegroundService(Intent(this, WingerVpnService::class.java).putExtra(WingerVpnService.EXTRA_CONFIG, config))
    }
    fun disconnect() {
        Store.running(this, false)
        Store.clearLastConfig(this)
        TunnelState.off()
        runCatching { startService(Intent(this, WingerVpnService::class.java).setAction(WingerVpnService.ACTION_STOP)) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VpnLog.init(this)
        CrashGuard.install()
        Diag.harvestCrash(this)
        Diag.harvestCoreLog(this, rotate = true)
        Diag.logExitReasons(this)
        VpnLog.add("app start")
        pro.wingerapp.vpn.net.Api.init(this)
        TunnelState.sync(this)
        setContent { App(this, hwid(), ::connect, ::disconnect) }
    }

    /**
     * ANDROID_ID is stable per signing key, but it can be empty on some devices.
     * A shared constant would give every such device the same HWID (and the same
     * trial), so fall back to a hash of the build fingerprint instead.
     */
    private fun hwid(): String {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        if (!id.isNullOrBlank() && id.length >= 8) return id
        val seed = "${Build.MANUFACTURER}|${Build.MODEL}|${Build.FINGERPRINT}"
        return pro.wingerapp.vpn.net.Api.sha256(seed).take(32)
    }
}

@Composable
fun App(ctx: Context, hwid: String, connect: (String) -> Unit, disconnect: () -> Unit) {
    var authed by remember { mutableStateOf(pro.wingerapp.vpn.net.Api.hasSession()) }
    DisposableEffect(Unit) {
        pro.wingerapp.vpn.net.Api.onUnauthorized = { authed = false }
        onDispose { pro.wingerapp.vpn.net.Api.onUnauthorized = null }
    }
    val onLogout: () -> Unit = {
        disconnect()
        pro.wingerapp.vpn.net.Api.logout()
        TunnelState.clearError()
        authed = false
    }
    if (!authed) Login(ctx, hwid, disconnect) { authed = true }
    else Home(hwid, connect, disconnect, onLogout)
}

@Composable
fun Login(ctx: Context, hwid: String, disconnect: () -> Unit, onOk: () -> Unit) {
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
        RegisterForm(hwid, onClose = { showRegister = false }) { login, pass ->
            Store.save(ctx, login, pass)
            onOk()
        }
        return
    }
    Box(Modifier.fillMaxSize().background(Bg)) {
        Image(
            painterResource(R.drawable.eagle), "Winger",
            Modifier.align(Alignment.TopEnd).padding(16.dp).width(42.dp).height(32.dp),
            contentScale = ContentScale.FillBounds
        )
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(
            "Winger",
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            style = TextStyle(brush = Brush.linearGradient(listOf(Color(0xFF7EE7FF), Color.White, Color(0xFFD8B4FE))))
        )
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
}

@Composable
private fun RegisterForm(hwid: String, onClose: () -> Unit, onDone: (String, String) -> Unit) {
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
                        val e = withContext(Dispatchers.IO) { runCatching { Api.register(user, pass, hwid) }.getOrElse { it.message } }
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
fun Home(hwid: String, connect: (String) -> Unit, disconnect: () -> Unit, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val on by TunnelState.running.collectAsState()
    val tunnelError by TunnelState.error.collectAsState()
    var pinging by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf("00:00:00") }
    var sub by remember { mutableStateOf<Sub?>(null) }
    var raw by remember { mutableStateOf("") }
    var servers by remember { mutableStateOf(listOf<ServerNode>()) }
    var pings by remember { mutableStateOf(mapOf<String, Int?>()) }
    var place by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var chat by remember { mutableStateOf(false) }
    var up by remember { mutableStateOf("0 Б") }
    var down by remember { mutableStateOf("0 Б") }
    val scope = rememberCoroutineScope()
    fun cfg(name: String) = xrayConfigFor(raw, name)
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
                }.onFailure {
                    val msg = it.message?.lineSequence()?.firstOrNull()?.take(140) ?: "не удалось обновить подписку"
                    err = msg
                    scope.launch {
                        kotlinx.coroutines.delay(4000)
                        if (err == msg) err = null
                    }
                }.getOrNull()
            }?.let { body ->
                err = null
                if (!ping) {
                    notice = "Подписка обновлена"
                    scope.launch {
                        kotlinx.coroutines.delay(2600)
                        if (notice == "Подписка обновлена") notice = null
                    }
                } else {
                    coroutineScope {
                        body.servers.map { node ->
                            async(Dispatchers.IO) {
                                val points = tcpEndpoints(body.raw, node.name).ifEmpty {
                                    listOf(Endpoint(node.host, if (node.port in 1..65535) node.port else 443))
                                }.filter { it.host.isNotBlank() && it.port in 1..65535 }
                                val ms = if (points.isEmpty()) -1
                                else points.map { point -> tcpPing(point.host, point.port) }.filterNotNull().minOrNull()
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
            val rx = tun?.first ?: 0L
            val tx = tun?.second ?: 0L
            down = fmtBytes(rx)
            up = fmtBytes(tx)
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        when (tab) {
            0 -> HomeTab(
                ctx = ctx, hwid = hwid, sub = sub, servers = servers, pings = pings, place = place,
                on = on, elapsed = elapsed, up = up, down = down, pinging = pinging, refreshing = refreshing,
                err = err ?: tunnelError, notice = notice,
                raw = raw, connect = connect, disconnect = disconnect, reload = reload,
                onSelect = { name ->
                    place = name
                    connect(cfg(name))
                },
                onToggle = {
                    if (on) {
                        disconnect()
                    } else if (raw.isNotBlank() && place.isNotBlank()) {
                        connect(cfg(place))
                    } else {
                        err = "Сначала выберите сервер"
                    }
                }
            )
            1 -> AccountTab(ctx, onLogout = onLogout) { chat = true }
            2 -> SettingsTab(ctx) {
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
    raw: String, connect: (String) -> Unit, disconnect: () -> Unit, reload: (Boolean) -> Unit,
    onSelect: (String) -> Unit, onToggle: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 12.dp)) {
            Text("WINGER", color = Cyan, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
            Text(" v${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
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
        if (servers.isEmpty()) Text("Загружаем подписку…", color = Dim, modifier = Modifier.padding(top = 8.dp))
        Column {
            grouped(servers).forEach { (title, nodes) ->
                Text(title, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                nodes.forEach { node ->
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
                            Text("VLESS · ${node.port}", color = Muted, fontSize = 10.sp)
                        }
                        if (waiting) {
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Cyan)
                        } else if (pings.containsKey(node.name)) {
                            val c = when { ms == null || ms == -1 -> Color(0xFF7C8A9B); ms < 120 -> Color(0xFF7EE0B0); ms < 200 -> Color(0xFFF0C14B); else -> Color(0xFFF07171) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(c))
                                Text(when {
                                    ms == null -> "нет"
                                    ms == -1 -> "н/д"
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

@Composable
private fun AccountTab(ctx: Context, onLogout: () -> Unit, onSupport: () -> Unit) {
    var cab by remember { mutableStateOf<pro.wingerapp.vpn.net.Cabinet?>(null) }
    var note by remember { mutableStateOf("") }
    var paying by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var ways by remember { mutableStateOf(listOf<pro.wingerapp.vpn.net.PayWay>()) }
    var picked by remember { mutableStateOf(30) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf("") }
    var pickedWay by remember { mutableIntStateOf(0) }
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
                    load()
                    toast = "Бонус +3 дня начислен"
                    return@launch
                }
            }
        }
    }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(toast) {
        if (toast.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(2000)
        toast = ""
    }
    val c = cab
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 96.dp)) {
        if (c == null) {
            SubLoading()
        } else {
        Section {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("АККАУНТ", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp)
                Text(c.login, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (note.isNotBlank()) Text(note, color = Color(0xFFF87171), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            if (c.telegram == null) {
                Section {
                    Text("TELEGRAM", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp)
                    Text("Привяжите Telegram и заберите бонус", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    Box(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(24.dp)).background(Purple).clickable { bindTelegram() }.padding(13.dp), contentAlignment = Alignment.Center) {
                        Text("Привязать Telegram +3 дня", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Section {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("TELEGRAM", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp)
                        Text(c.telegram ?: "привязан", color = Purple, fontSize = 16.sp, fontWeight = FontWeight.Normal)
                    }
                }
            }
            SubCard(c)
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
                                pickedWay = 0
                                choosing = true
                            }
                        }.padding(13.dp), contentAlignment = Alignment.Center) { Text("Продлить", color = Color.White, fontWeight = FontWeight.SemiBold) }
                    } else {
                        Text("СПОСОБ ОПЛАТЫ", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 14.dp))
                        ways.forEachIndexed { index, way ->
                            val amount = offer.amount(way.gateway)
                            val selected = index == pickedWay
                            Row(
                                Modifier.fillMaxWidth().padding(top = 10.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(if (selected) Purple.copy(.14f) else Color(0xFF0A1424))
                                    .border(1.dp, if (selected) Purple else Purple.copy(.28f), RoundedCornerShape(18.dp))
                                    .clickable { pickedWay = index }
                                    .padding(horizontal = 14.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PayMarks(way.gateway)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(way.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Normal)
                                    val sub = listOf("$amount ₽", way.hint).filter { it.isNotBlank() }.joinToString(" · ")
                                    if (sub.isNotBlank()) Text(sub, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                                }
                                Box(
                                    Modifier.size(18.dp).clip(CircleShape).border(1.5.dp, if (selected) Purple else Color.White.copy(.28f), CircleShape).background(if (selected) Purple else Color.Transparent)
                                )
                            }
                        }
                        Box(Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(24.dp)).background(Purple).clickable(enabled = !paying && ways.isNotEmpty()) {
                            val way = ways.getOrNull(pickedWay) ?: return@clickable
                            val amount = offer.amount(way.gateway)
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
                        }.padding(13.dp), contentAlignment = Alignment.Center) {
                            Text(if (paying) "Открываем…" else "Перейти к оплате", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Section {
                var devicesOpen by remember { mutableStateOf(true) }
                Row(Modifier.fillMaxWidth().clickable { devicesOpen = !devicesOpen }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("УСТРОЙСТВА", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp)
                    Text(if (devicesOpen) "свернуть" else "развернуть", color = Cyan, fontSize = 12.sp)
                }
                if (devicesOpen) {
                    c.devices.forEach { d ->
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.title, color = Color.White, fontSize = 14.sp)
                                Text(d.seen, color = Muted, fontSize = 12.sp)
                            }
                            Text("удалить", color = Color(0xFFF87171), fontSize = 13.sp, modifier = Modifier.clickable { confirm = d.hwid }.padding(8.dp))
                        }
                    }
                }
                if (confirm != null) {
                    val target = c.devices.find { it.hwid == confirm }
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(.6f)).clickable { confirm = null }, contentAlignment = Alignment.Center) {
                        Column(Modifier.padding(24.dp).clip(RoundedCornerShape(20.dp)).background(Elev).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Удалить устройство?", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(target?.title ?: "", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Нет", color = Color.White, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF1A2436)).clickable { confirm = null }.padding(horizontal = 20.dp, vertical = 10.dp))
                                Text("Да", color = Color.White, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFDC2626)).clickable {
                                    val hwid = confirm
                                    confirm = null
                                    if (hwid != null) scope.launch {
                                        val ok = withContext(Dispatchers.IO) { Api.removeDevice(hwid) }
                                        if (ok) load() else note = "не удалось удалить устройство"
                                    }
                                }.padding(horizontal = 20.dp, vertical = 10.dp))
                            }
                        }
                    }
                }
            }
            Section {
                Row(Modifier.fillMaxWidth().clickable { onLogout() }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("СЕССИЯ", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp)
                    Text(
                        "выйти",
                        color = Color(0xFFF87171),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF1F1626))
                            .border(1.dp, Color(0xFFF87171).copy(.5f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
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
    if (toast.isNotBlank()) {
        Text(toast, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF121C2E)).border(1.dp, Purple.copy(.75f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp))
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
private fun SubLoading() {
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
    Text("Подписка в пути", color = Color(0xFFC5D7E6), modifier = Modifier.fillMaxWidth().padding(top = 14.dp), textAlign = TextAlign.Center)
}

@Composable
private fun SubCard(c: pro.wingerapp.vpn.net.Cabinet) {
    val active = c.status == "ACTIVE"
    val days = runCatching {
        val d = java.time.LocalDate.parse(c.expires.take(10))
        java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), d)
    }.getOrNull()
    val trafficValue = c.traffic.substringAfter("из ", c.traffic).trim()
    val trafficSize = if (trafficValue.length > 8) 22.sp else 34.sp
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFA78BFA), Color(0xFF9578F6), Color(0xFF7C5AEE), Color(0xFF6236C9), Color(0xFF4C1D95))))
    ) {
        val narrow = maxWidth < 360.dp
        Flakes()
        Canvas(Modifier.matchParentSize()) {
            val r = 84.dp.toPx()
            drawCircle(Color.White.copy(.16f), r, Offset(size.width - 20.dp.toPx(), 40.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
            drawLine(Color.White.copy(.16f), Offset(size.width - 20.dp.toPx() - r, 40.dp.toPx()), Offset(size.width - 20.dp.toPx() + r, 40.dp.toPx()), 1.5.dp.toPx())
        }
        Column(Modifier.padding(if (narrow) 12.dp else 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BirdMark()
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text("Тариф", color = Color.White.copy(.85f), fontSize = 12.sp)
                    Text(c.plan, color = Color.White, fontSize = if (narrow) 15.sp else 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Row(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x8C24124E)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (active) Color(0xFF3DFF7A) else Color(0xFFFF4D4D)))
                    Text(if (active) "АКТИВНА" else "НЕАКТИВНА", color = Color.White, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("ТРАФИК", color = Color.White, fontSize = if (narrow) 14.sp else 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, maxLines = 1)
                    Text(trafficValue, color = Color.White, fontSize = trafficSize, fontWeight = FontWeight.Normal, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text("ОСТАЛОСЬ", color = Color.White.copy(.9f), fontSize = 11.sp, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 8.dp), maxLines = 1)
                    Text(if (days == null) "—" else "$days ${dayWord(days)}", color = Color.White, fontSize = if (narrow) 22.sp else 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Column(Modifier.widthIn(min = 96.dp), horizontalAlignment = Alignment.End) {
                    Text("УСТРОЙСТВА", color = Color.White.copy(.9f), fontSize = 11.sp, letterSpacing = 0.4.sp, maxLines = 1)
                    Text("${c.devicesUsed} из ${c.deviceLimit}", color = Color.White, fontSize = if (narrow) 18.sp else 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun BirdMark() {
    val painter = painterResource(R.drawable.eagle)
    Box(Modifier.width(22.dp).height(17.dp)) {
        listOf(-0.4f to 0f, 0.4f to 0f, 0f to -0.4f, 0f to 0.4f).forEach { (x, y) ->
            Image(painter, null, Modifier.fillMaxSize().offset(x.dp, y.dp), contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.tint(Color.Black, BlendMode.SrcIn))
        }
        Image(painter, "птица", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    }
}

@Composable
private fun BoxScope.Flakes() {
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
private fun PayMarks(gateway: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Image(painterResource(R.drawable.sbp), "СБП", Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)))
        if (gateway == "PLATEGA") {
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(Brush.linearGradient(listOf(Color(0xFF21A35A), Color(0xFF0D6A38)))), contentAlignment = Alignment.Center) {
                Text("МИР", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Section(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(22.dp)).background(Elev).border(1.dp, Line, RoundedCornerShape(22.dp)).padding(16.dp), content = content)
}

@Composable
private fun SettingsTab(ctx: Context, onApply: () -> Unit) {
    var log by remember { mutableStateOf(VpnLog.text()) }
    var dns by remember { mutableStateOf(Store.dnsMode(ctx)) }
    var custom by remember { mutableStateOf(Store.dnsCustom(ctx)) }
    var hosts by remember { mutableStateOf(Store.bypass(ctx)) }
    var site by remember { mutableStateOf("") }
    var mux by remember { mutableStateOf(Store.useMux(ctx)) }
    var mtu by remember { mutableStateOf(Store.mtuLow(ctx)) }
    var logOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 96.dp)) {
        Text("НАСТРОЙКИ", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp)
        Text("DNS", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 16.dp))
        Text("Запросы уходят через туннель", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
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
        Text("СОЕДИНЕНИЕ", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 16.dp))
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Mux (не для vision)", color = Color.White, modifier = Modifier.weight(1f))
            Switch(mux, { mux = it; Store.useMux(ctx, it); onApply() })
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("MTU 1400", color = Color.White, modifier = Modifier.weight(1f))
            Switch(mtu, { mtu = it; Store.mtuLow(ctx, it); onApply() })
        }
        Text("ЯДРО", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 16.dp))
        Text("sing-box-lx 1.14.4 (XHTTP)", color = Color.White, modifier = Modifier.padding(top = 8.dp))
        Text(
            if (BuildConfig.CORE_LOG) "лог ядра включён (debug)" else "лог ядра выключен (release)",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text("БЕЗ ТУННЕЛЯ", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 16.dp))
        Text("Эти домены идут напрямую, мимо VPN", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(site, { site = it }, placeholder = { Text("bank.ru") }, modifier = Modifier.weight(1f), singleLine = true, colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = Elev, unfocusedContainerColor = Elev, focusedIndicatorColor = Purple, unfocusedIndicatorColor = Line))
            Text("добавить", color = Color.White, modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(12.dp)).background(Purple).clickable {
                val host = site.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore("/")
                if (host.isNotBlank() && host !in hosts) {
                    hosts = hosts + host
                    Store.bypass(ctx, hosts)
                    site = ""
                    onApply()
                }
            }.padding(horizontal = 12.dp, vertical = 12.dp))
        }
        hosts.forEach { host ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(host, color = Color.White)
                Text("убрать", color = Color(0xFFF87171), modifier = Modifier.clickable {
                    hosts = hosts - host
                    Store.bypass(ctx, hosts)
                    onApply()
                })
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp).clickable { logOpen = !logOpen }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("ЛОГ", color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp)
            Text(if (logOpen) "свернуть" else "развернуть", color = Cyan, fontSize = 12.sp)
        }
        if (logOpen) {
            Text(log.ifBlank { "пока пусто" }, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Elev).clickable { log = VpnLog.text() }.padding(10.dp)) { Text("Обновить", color = Color.White) }
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Purple).clickable {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("winger", VpnLog.text()))
                }.padding(10.dp)) { Text("Скопировать", color = Color.White) }
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF8B3A3A)).clickable {
                    VpnLog.clear()
                    log = ""
                }.padding(10.dp)) { Text("Очистить", color = Color.White) }
            }
        }
    }
}

@Composable
private fun Seg(label: String, on: Boolean, modifier: Modifier, click: () -> Unit) {
    Box(modifier.padding(horizontal = 2.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Purple.copy(.28f) else Elev).clickable { click() }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontSize = 12.sp)
    }
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

private fun dayWord(n: Long) = when {
    n % 10L == 1L && n % 100L != 11L -> "день"
    n % 10L in 2L..4L && n % 100L !in 12L..14L -> "дня"
    else -> "дней"
}

private fun daysLeft(expires: String?): String {
    if (expires.isNullOrBlank()) return "—"
    return runCatching {
        val d = java.time.LocalDate.parse(expires.take(10))
        val n = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), d)
        "$n ${dayWord(n)}"
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
