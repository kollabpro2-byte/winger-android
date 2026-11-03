package pro.wingerapp.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
    fun disconnect() { startService(Intent(this, WingerVpnService::class.java).setAction("STOP")) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App(hwid(), ::connect) }
    }
    private fun hwid() = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        ?.padEnd(16, '0')?.take(32) ?: "winger-android-device"
}

@Composable
fun App(hwid: String, connect: (String) -> Unit) {
    var authed by remember { mutableStateOf(false) }
    if (!authed) Login { authed = true } else Home(hwid, connect)
}

@Composable
fun Login(onOk: () -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
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
                        if (e == null) onOk() else err = e
                    }
                }.padding(14.dp),
            contentAlignment = Alignment.Center
        ) { Text(if (busy) "Входим…" else "Войти", color = Color.White, fontWeight = FontWeight.SemiBold) }
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
fun Home(hwid: String, connect: (String) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var on by remember { mutableStateOf(false) }
    var sub by remember { mutableStateOf<Sub?>(null) }
    var raw by remember { mutableStateOf("") }
    var servers by remember { mutableStateOf(listOf<String>()) }
    var place by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                val s = Api.subscription()
                sub = s
                if (s != null && s.url.isNotBlank()) {
                    val cfg = readSubConfig(Api.fetchConfig(s.url, hwid))
                    raw = cfg.raw
                    servers = cfg.servers
                    place = cfg.servers.first()
                }
            }.onFailure { err = it.message }
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        if (tab == 0) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Text(
                    if (on) place else "Нажми на орла",
                    color = if (on) Color(0xFFE9D5FF) else Muted,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 16.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 28.dp)
                )
                Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                    Image(
                        painterResource(R.drawable.eagle), "Winger",
                        modifier = Modifier.size(240.dp).alpha(if (on) 1f else 0.45f).clickable {
                            if (raw.isBlank() || place.isBlank()) return@clickable
                            if (on) { on = false; return@clickable }
                            connect(configFor(raw, place))
                            on = true
                        },
                        contentScale = ContentScale.Fit
                    )
                }
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Color(0xFFC4B5FD))) { append("Тариф ") }
                        withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) {
                            append(sub?.name ?: "…")
                        }
                        withStyle(SpanStyle(color = Muted)) {
                            append(" · " + (sub?.expires?.take(10) ?: ""))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 13.sp
                )
                err?.let { Text(it, color = Color(0xFFF87171), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat(if (on) "авто" else "—", "пинг")
                    Stat(if (on) "VPN" else "—", "статус")
                    Stat((sub?.devices ?: 4).toString(), "устройства")
                }
                Text("СЕРВЕРЫ", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
                if (servers.isEmpty()) Text("Загружаем подписку…", color = Dim, modifier = Modifier.padding(top = 8.dp))
                servers.forEach { name ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(16.dp)).background(Elev)
                            .border(1.dp, if (place == name) Purple.copy(.55f) else Line, RoundedCornerShape(16.dp))
                            .clickable {
                                place = name
                                connect(configFor(raw, name))
                                on = true
                            }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(name, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(96.dp))
            }
        } else {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text("Аккаунт", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp))
                Text(sub?.name ?: "Подписка", color = Muted)
                Text("до ${sub?.expires?.take(10) ?: "—"}", color = Muted)
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
