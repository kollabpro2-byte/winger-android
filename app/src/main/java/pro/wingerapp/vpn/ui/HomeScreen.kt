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
import pro.wingerapp.vpn.AppViewModel
import pro.wingerapp.vpn.BuildConfig
import pro.wingerapp.vpn.R
import pro.wingerapp.vpn.*
import pro.wingerapp.vpn.net.*

@Composable
fun HomeScreen(
    vm: AppViewModel,
    hwid: String,
    connect: (String) -> Unit,
    disconnect: () -> Unit,
    onLogout: () -> Unit,
) {
    val s = rememberStrings()
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var chat by remember { mutableStateOf(false) }
    val on by TunnelState.running.collectAsState()
    val tunnelError by TunnelState.error.collectAsState()
    val st by vm.state.collectAsState()
    var elapsed by remember { mutableStateOf("00:00:00") }
    var up by remember { mutableStateOf(s[R.string.comp_zero]) }
    var down by remember { mutableStateOf(s[R.string.comp_zero]) }

    LaunchedEffect(hwid) { vm.start(hwid) }

    // A new network means a new route: re-probe latency so the list does not keep
    // the numbers measured on the previous Wi-Fi.
    DisposableEffect(Unit) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { vm.refresh(ping = true) }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        onDispose { runCatching { cm.unregisterNetworkCallback(cb) } }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60 * 60 * 1000L)
            vm.refresh(ping = false)
        }
    }

    LaunchedEffect(on) {
        if (!on) return@LaunchedEffect
        val startAt = System.currentTimeMillis()
        while (on) {
            val sec = ((System.currentTimeMillis() - startAt) / 1000).toInt()
            elapsed = "%02d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
            delay(1000)
            val tun = tunTraffic()
            down = fmtBytes(tun?.first ?: 0L)
            up = fmtBytes(tun?.second ?: 0L)
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        when (tab) {
            0 -> HomeTab(
                ctx = ctx, hwid = hwid, sub = st.plan, servers = st.servers, pings = st.pings,
                place = st.selected, on = on, elapsed = elapsed, up = up, down = down,
                pinging = st.pinging, refreshing = st.refreshing,
                err = st.error ?: tunnelError, notice = st.notice,
                raw = st.raw, connect = connect, disconnect = disconnect,
                reload = { ping -> vm.refresh(ping) },
                onSelect = { name ->
                    vm.select(name)
                    vm.config(name)?.let(connect)
                },
                onToggle = {
                    if (on) {
                        disconnect()
                    } else {
                        val config = vm.config()
                        if (config != null) connect(config)
                        else vm.fail(s[R.string.home_select_server_first])
                    }
                },
            )
            1 -> AccountTab(ctx, onLogout = { onLogout(); vm.logout() }) { chat = true }
            2 -> SettingsTab(ctx) {
                // Settings only reach the core through the config: rebuild it.
                if (on) vm.config()?.let(connect)
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth().height(60.dp)
                .clip(RoundedCornerShape(20.dp)).background(Color(0xE6081224))
                .border(1.dp, Line, RoundedCornerShape(20.dp)),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
        ) {
            NavItem(s[R.string.nav_home], tab == 0) { tab = 0 }
            NavItem(s[R.string.nav_account], tab == 1) { tab = 1 }
            NavItem(s[R.string.nav_settings], tab == 2) { tab = 2 }
        }
        if (chat) SupportChat(ctx) { chat = false }
    }
}

@Composable
internal fun NavItem(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) TabOn else TabOff,
        fontSize = 14.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clickable { onClick() }.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}
