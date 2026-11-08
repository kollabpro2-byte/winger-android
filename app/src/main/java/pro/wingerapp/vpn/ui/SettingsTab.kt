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
internal fun SettingsTab(ctx: Context, onApply: () -> Unit) {
    val s = rememberStrings()
    var log by remember { mutableStateOf(VpnLog.text()) }
    var dns by remember { mutableStateOf(Store.dnsMode(ctx)) }
    var custom by remember { mutableStateOf(Store.dnsCustom(ctx)) }
    var hosts by remember { mutableStateOf(Store.bypass(ctx)) }
    var site by remember { mutableStateOf("") }
    var mux by remember { mutableStateOf(Store.useMux(ctx)) }
    var mtu by remember { mutableStateOf(Store.mtuLow(ctx)) }
    var logOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 96.dp)) {
        Text(s[R.string.set_title], color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp)
        Text(s[R.string.set_dns], color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 16.dp))
        Text(s[R.string.set_dns_hint], color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 8.dp)) {
            listOf("auto" to s[R.string.set_dns_auto], "cf" to "1.1.1.1", "gg" to "8.8.8.8", "own" to s[R.string.set_dns_own]).forEach { (id, label) ->
                Seg(label, dns == id, Modifier.weight(1f)) { dns = id; Store.dnsMode(ctx, id); onApply() }
            }
        }
        if (dns == "own") OutlinedTextField(custom, {
            custom = it
            Store.dnsCustom(ctx, it)
            if (it.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) onApply()
        }, placeholder = { Text("9.9.9.9") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = Elev, unfocusedContainerColor = Elev, focusedIndicatorColor = Purple, unfocusedIndicatorColor = Line))
        Text(s[R.string.set_connection], color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 16.dp))
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s[R.string.set_mux], color = Color.White, modifier = Modifier.weight(1f))
            Switch(mux, { mux = it; Store.useMux(ctx, it); onApply() })
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("MTU 1400", color = Color.White, modifier = Modifier.weight(1f))
            Switch(mtu, { mtu = it; Store.mtuLow(ctx, it); onApply() })
        }
        Text(s[R.string.set_core], color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 16.dp))
        Text("sing-box-lx 1.14.4 (XHTTP)", color = Color.White, modifier = Modifier.padding(top = 8.dp))
        var verbose by remember { mutableStateOf(BuildConfig.CORE_LOG || Store.coreLog(ctx)) }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s[R.string.set_core_log], color = Color.White)
                Text(s[R.string.set_core_log_hint], color = Muted, fontSize = 11.sp)
            }
            Switch(verbose, {
                verbose = it
                Store.coreLog(ctx, it)
                onApply()
            })
        }
        Text(s[R.string.set_bypass], color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 16.dp))
        Text(s[R.string.set_bypass_hint], color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(site, { site = it }, placeholder = { Text("bank.ru") }, modifier = Modifier.weight(1f), singleLine = true, colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = Elev, unfocusedContainerColor = Elev, focusedIndicatorColor = Purple, unfocusedIndicatorColor = Line))
            Text(s[R.string.set_add], color = Color.White, modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(12.dp)).background(Purple).clickable {
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
                Text(s[R.string.set_remove], color = Color(0xFFF87171), modifier = Modifier.clickable {
                    hosts = hosts - host
                    Store.bypass(ctx, hosts)
                    onApply()
                })
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp).clickable { logOpen = !logOpen }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(s[R.string.set_log], color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp)
            Text(if (logOpen) s[R.string.acc_collapse] else s[R.string.acc_expand], color = Cyan, fontSize = 12.sp)
        }
        if (logOpen) {
            Text(log.ifBlank { s[R.string.set_log_empty] }, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Elev).clickable { log = VpnLog.text() }.padding(10.dp)) { Text(s[R.string.set_refresh], color = Color.White) }
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Purple).clickable {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("winger", VpnLog.text()))
                }.padding(10.dp)) { Text(s[R.string.set_copy], color = Color.White) }
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF8B3A3A)).clickable {
                    VpnLog.clear()
                    log = ""
                }.padding(10.dp)) { Text(s[R.string.set_clear], color = Color.White) }
            }
        }
    }
}
