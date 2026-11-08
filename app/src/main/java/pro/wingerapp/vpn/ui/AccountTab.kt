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
internal fun AccountTab(ctx: Context, onLogout: () -> Unit, onSupport: () -> Unit) {
    val s = rememberStrings()
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
            val url = withContext(Dispatchers.IO) { runCatching { Api.telegramLink() }.getOrElse { it.message ?: s[R.string.acc_telegram_failed] } }
            if (!url.startsWith("http")) { note = url; return@launch }
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            repeat(20) {
                kotlinx.coroutines.delay(3000)
                val fresh = withContext(Dispatchers.IO) { runCatching { Api.cabinet() }.getOrNull() } ?: return@repeat
                cab = fresh
                if (fresh.telegram != null) {
                    load()
                    toast = s[R.string.acc_telegram_bonus]
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
                Text(s[R.string.acc_account], color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp)
                Text(c.login, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (note.isNotBlank()) Text(note, color = Color(0xFFF87171), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            if (c.telegram == null) {
                Section {
                    Text(s[R.string.acc_telegram], color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp)
                    Text(s[R.string.acc_telegram_hint], color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    Box(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(24.dp)).background(Purple).clickable { bindTelegram() }.padding(13.dp), contentAlignment = Alignment.Center) {
                        Text(s[R.string.acc_telegram_bind], color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Section {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(s[R.string.acc_telegram], color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp)
                        Text(c.telegram ?: s[R.string.acc_telegram_linked], color = Purple, fontSize = 16.sp, fontWeight = FontWeight.Normal)
                    }
                }
            }
            SubCard(c)
            if (c.offers.isNotEmpty()) {
                val offer = c.offers.firstOrNull { it.days == picked } ?: c.offers.first()
                Section {
                    Text(if (c.subscriptionId.isBlank()) s[R.string.acc_payment] else s[R.string.acc_renewal], color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp)
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s[R.string.acc_days], color = Color(0xFF7C8A9B), fontSize = 12.sp, modifier = Modifier.width(42.dp))
                        c.offers.forEach { item ->
                            val on = item.days == picked
                            Box(Modifier.weight(1f).padding(horizontal = 3.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Purple.copy(.22f) else Color.White.copy(.03f)).border(1.dp, if (on) Purple.copy(.6f) else Line, RoundedCornerShape(12.dp)).clickable { picked = item.days; choosing = false }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                                Text("${item.days}", color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s[R.string.acc_price], color = Color(0xFF7C8A9B), fontSize = 12.sp, modifier = Modifier.width(42.dp))
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
                        }.padding(13.dp), contentAlignment = Alignment.Center) { Text(s[R.string.acc_renew], color = Color.White, fontWeight = FontWeight.SemiBold) }
                    } else {
                        Text(s[R.string.acc_pay_method], color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 14.dp))
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
                                val result = withContext(Dispatchers.IO) { runCatching { Api.pay(c.subscriptionId, c.planId, offer.days, amount, way.gateway, way.autopay) }.getOrElse { it.message ?: s[R.string.acc_error] } }
                                paying = false
                                if (result.startsWith("http")) {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    choosing = false
                                } else note = result
                            }
                        }.padding(13.dp), contentAlignment = Alignment.Center) {
                            Text(if (paying) s[R.string.acc_opening] else s[R.string.acc_go_pay], color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Section {
                var devicesOpen by remember { mutableStateOf(true) }
                Row(Modifier.fillMaxWidth().clickable { devicesOpen = !devicesOpen }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(s[R.string.acc_devices], color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp)
                    Text(if (devicesOpen) s[R.string.acc_collapse] else s[R.string.acc_expand], color = Cyan, fontSize = 12.sp)
                }
                if (devicesOpen) {
                    c.devices.forEach { d ->
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.title, color = Color.White, fontSize = 14.sp)
                                Text(d.seen, color = Muted, fontSize = 12.sp)
                            }
                            Text(s[R.string.acc_remove], color = Color(0xFFF87171), fontSize = 13.sp, modifier = Modifier.clickable { confirm = d.hwid }.padding(8.dp))
                        }
                    }
                }
                if (confirm != null) {
                    val target = c.devices.find { it.hwid == confirm }
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(.6f)).clickable { confirm = null }, contentAlignment = Alignment.Center) {
                        Column(Modifier.padding(24.dp).clip(RoundedCornerShape(20.dp)).background(Elev).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(s[R.string.acc_remove_title], color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(target?.title ?: "", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(s[R.string.acc_remove_no], color = Color.White, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF1A2436)).clickable { confirm = null }.padding(horizontal = 20.dp, vertical = 10.dp))
                                Text(s[R.string.acc_remove_yes], color = Color.White, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFDC2626)).clickable {
                                    val hwid = confirm
                                    confirm = null
                                    if (hwid != null) scope.launch {
                                        val ok = withContext(Dispatchers.IO) { Api.removeDevice(hwid) }
                                        if (ok) load() else note = s[R.string.acc_remove_failed]
                                    }
                                }.padding(horizontal = 20.dp, vertical = 10.dp))
                            }
                        }
                    }
                }
            }
            Section {
                Row(Modifier.fillMaxWidth().clickable { onLogout() }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(s[R.string.acc_session], color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp)
                    Text(
                        s[R.string.acc_logout],
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
                    Text(s[R.string.acc_support], color = Color(0xFF7C8A9B), fontSize = 12.sp, letterSpacing = 1.sp)
                    Text(s[R.string.acc_support_open], color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Purple).padding(horizontal = 14.dp, vertical = 8.dp))
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
internal fun SupportChat(ctx: Context, onClose: () -> Unit) {
    val s = rememberStrings()
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
            Text(s[R.string.chat_title], color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(s[R.string.chat_close], color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Purple).clickable { onClose() }.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        if (note.isNotBlank()) Text(note, color = Color(0xFFF87171), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp)) {
            val lines = thread?.lines.orEmpty()
            if (lines.isEmpty()) Text(s[R.string.chat_hint], color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
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
                OutlinedTextField(draft, { draft = it }, placeholder = { Text(s[R.string.chat_placeholder]) }, modifier = Modifier.weight(1f), colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = Elev, unfocusedContainerColor = Elev, focusedIndicatorColor = Purple, unfocusedIndicatorColor = Line))
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
internal fun SubCard(c: pro.wingerapp.vpn.net.Cabinet) {
    val s = rememberStrings()
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
                    Text(s[R.string.sub_plan], color = Color.White.copy(.85f), fontSize = 12.sp)
                    Text(c.plan, color = Color.White, fontSize = if (narrow) 15.sp else 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Row(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x8C24124E)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (active) Color(0xFF3DFF7A) else Color(0xFFFF4D4D)))
                    Text(if (active) s[R.string.sub_active] else s[R.string.sub_inactive], color = Color.White, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(s[R.string.sub_traffic], color = Color.White, fontSize = if (narrow) 14.sp else 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, maxLines = 1)
                    Text(trafficValue, color = Color.White, fontSize = trafficSize, fontWeight = FontWeight.Normal, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(s[R.string.sub_left], color = Color.White.copy(.9f), fontSize = 11.sp, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 8.dp), maxLines = 1)
                    Text(if (days == null) s[R.string.fmt_none] else s.quantity(R.plurals.days, days), color = Color.White, fontSize = if (narrow) 22.sp else 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Column(Modifier.widthIn(min = 96.dp), horizontalAlignment = Alignment.End) {
                    Text(s[R.string.acc_devices], color = Color.White.copy(.9f), fontSize = 11.sp, letterSpacing = 0.4.sp, maxLines = 1)
                    Text(s.format(R.string.acc_devices_value, c.devicesUsed, c.deviceLimit), color = Color.White, fontSize = if (narrow) 18.sp else 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}
