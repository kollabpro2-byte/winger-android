package pro.wingerapp.vpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.wingerapp.vpn.BuildConfig
import pro.wingerapp.vpn.Lte4
import pro.wingerapp.vpn.R
import pro.wingerapp.vpn.Store
import pro.wingerapp.vpn.VpnLog

/**
 * Настройки: каждый раздел — карточка с тёмной подложкой, разделы сворачиваются.
 * По умолчанию открыт только «Отображение»: там выбор темы, его ищут сразу.
 */
@Composable
internal fun SettingsTab(ctx: Context, onApply: () -> Unit) {
    val s = rememberStrings()
    val scope = rememberCoroutineScope()
    // Лог читаем в фоне: файл до 64 КБ, а раньше readText() вызывался прямо в
    // композиции — при открытии настроек это давало заметный рывок.
    var log by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { log = withContext(Dispatchers.IO) { VpnLog.text() } }
    var dns by remember { mutableStateOf(Store.dnsMode(ctx)) }
    var custom by remember { mutableStateOf(Store.dnsCustom(ctx)) }
    var hosts by remember { mutableStateOf(Store.bypass(ctx)) }
    var site by remember { mutableStateOf("") }
    var mux by remember { mutableStateOf(Store.useMux(ctx)) }
    var mtu by remember { mutableStateOf(Store.mtuLow(ctx)) }
    var v6 by remember { mutableStateOf(Store.ipv6Proxy(ctx)) }
    var rate by remember { mutableStateOf(Store.trafficIntervalSec(ctx)) }
    var clearMin by remember { mutableStateOf(Store.logClearMinutes(ctx)) }
    // Берём то, что пользователь оставил в прошлый раз; если ещё не трогал —
    // раскрыт только «Отображение».
    var open by remember { mutableStateOf(Store.openSections(ctx) ?: setOf("theme")) }

    fun toggle(id: String) {
        open = if (id in open) open - id else open + id
        Store.openSections(ctx, open)
    }

    // «ЛОГ» — тоже раздел: его состояние живёт в том же наборе, поэтому
    // раскрытый/свёрнутый лог запоминается между запусками.
    val logOpen = "log" in open

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 96.dp)
    ) {
        Text(
            s[R.string.set_title], color = Caption, fontSize = 12.sp,
            letterSpacing = 1.sp, fontWeight = FontWeight.Bold
        )

        SettingsSection(s[R.string.set_dns], "dns" in open, first = true, onToggle = { toggle("dns") }) {
            Text(s[R.string.set_dns_hint], color = Muted, fontSize = 11.sp)
            Row(Modifier.padding(top = 8.dp)) {
                listOf(
                    "auto" to s[R.string.set_dns_auto],
                    "cf" to "1.1.1.1",
                    "gg" to "8.8.8.8",
                    "own" to s[R.string.set_dns_own],
                ).forEach { (id, label) ->
                    Seg(label, dns == id, Modifier.weight(1f)) { dns = id; Store.dnsMode(ctx, id); onApply() }
                }
            }
            if (dns == "own") OutlinedTextField(
                custom, {
                    custom = it
                    Store.dnsCustom(ctx, it)
                    if (it.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) onApply()
                },
                placeholder = { Text("9.9.9.9") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
                colors = fieldColors(),
            )
        }

        SettingsSection(s[R.string.set_connection], "conn" in open, onToggle = { toggle("conn") }) {
            SwitchRow(s[R.string.set_mux], null, mux) { mux = it; Store.useMux(ctx, it); onApply() }
            SwitchRow("MTU 1400", null, mtu, divider = true) { mtu = it; Store.mtuLow(ctx, it); onApply() }
            SwitchRow(s[R.string.set_ipv6], s[R.string.set_ipv6_hint], v6, divider = true) {
                v6 = it; Store.ipv6Proxy(ctx, it); onApply()
            }
        }

        SettingsSection(s[R.string.set_display], "theme" in open, onToggle = { toggle("theme") }) {
            Text(s[R.string.set_theme], color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                s[R.string.set_theme_hint], color = Muted, fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
            Row(Modifier.padding(top = 8.dp)) {
                val labels = listOf(
                    ThemeMode.DARK to s[R.string.set_theme_dark],
                    ThemeMode.MID to s[R.string.set_theme_mid],
                    ThemeMode.LIGHT to s[R.string.set_theme_light],
                )
                labels.forEach { (mode, label) ->
                    Seg(label, AppTheme.mode == mode, Modifier.weight(1f)) {
                        AppTheme.mode = mode
                        Store.themeMode(ctx, AppTheme.toStored(mode))
                    }
                }
            }
            // Разделяем два блока внутри раздела: тема и частота трафика — разные
            // настройки, без черты они читаются как одна группа.
            Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(1.dp).background(Line))
            Text(
                s[R.string.set_traffic_rate], color = TextMain, fontSize = 14.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp)
            )
            Text(
                s[R.string.set_traffic_rate_hint], color = Muted, fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
            Row(Modifier.padding(top = 8.dp)) {
                listOf(1, 5, 10, 30).forEach { sec ->
                    Seg(s.format(R.string.set_sec, sec), rate == sec, Modifier.weight(1f)) {
                        rate = sec
                        Store.trafficIntervalSec(ctx, sec)
                    }
                }
            }
        }

        SettingsSection(s[R.string.set_core], "core" in open, onToggle = { toggle("core") }) {
            Text("sing-box-lx 1.14.4 (XHTTP)", color = TextMain, fontSize = 14.sp)
            var lte4 by remember { mutableIntStateOf(Store.lte4Mode(ctx)) }
            Text(
                s[R.string.set_lte4], color = OnCard, fontSize = 13.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                s[R.string.set_lte4_hint], color = Muted, fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(Modifier.padding(top = 8.dp)) {
                listOf(
                    Lte4.MODE_OFF to s[R.string.set_lte4_off],
                    Lte4.MODE_FLUSH to s[R.string.set_lte4_flush],
                    Lte4.MODE_FRAMED to s[R.string.set_lte4_framed],
                ).forEach { (mode, label) ->
                    Seg(label, lte4 == mode, Modifier.weight(1f)) {
                        lte4 = mode
                        Store.lte4Mode(ctx, mode)
                        onApply()
                    }
                }
            }
            var verbose by remember { mutableStateOf(BuildConfig.CORE_LOG || Store.coreLog(ctx)) }
            SwitchRow(s[R.string.set_core_log], s[R.string.set_core_log_hint], verbose, divider = true) {
                verbose = it
                Store.coreLog(ctx, it)
                onApply()
            }
        }

        SettingsSection(s[R.string.set_bg], "bg" in open, onToggle = { toggle("bg") }) {
            Text(s[R.string.set_bg_hint], color = Muted, fontSize = 11.sp)
            val power = ctx.getSystemService(android.os.PowerManager::class.java)
            val exempt = power?.isIgnoringBatteryOptimizations(ctx.packageName) == true
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (exempt) s[R.string.set_bg_done] else "",
                    color = if (exempt) Purple else Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                if (!exempt) {
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(Purple.copy(alpha = .22f))
                            .clickable {
                                runCatching {
                                    ctx.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                            android.net.Uri.parse("package:${ctx.packageName}"),
                                        )
                                    )
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                    ) { Text(s[R.string.set_bg_action], color = OnAccent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }

        SettingsSection(s[R.string.set_bypass], "bypass" in open, onToggle = { toggle("bypass") }) {
            Text(s[R.string.set_bypass_hint], color = Muted, fontSize = 11.sp)
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    site, { site = it }, placeholder = { Text("bank.ru") },
                    modifier = Modifier.weight(1f), singleLine = true, colors = fieldColors(),
                )
                Text(
                    s[R.string.set_add], color = OnAccent, fontSize = 14.sp,
                    modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(12.dp))
                        .background(Purple).clickable {
                            val host = site.trim().lowercase().removePrefix("https://")
                                .removePrefix("http://").substringBefore("/")
                            if (host.isNotBlank() && host !in hosts) {
                                hosts = hosts + host
                                Store.bypass(ctx, hosts)
                                site = ""
                                onApply()
                            }
                        }.padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
            hosts.forEach { host ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(host, color = TextMain, fontSize = 14.sp)
                    Text(
                        s[R.string.set_remove], color = Color(0xFFF87171), fontSize = 14.sp,
                        modifier = Modifier.clickable {
                            hosts = hosts - host
                            Store.bypass(ctx, hosts)
                            onApply()
                        },
                    )
                }
            }
        }


        // ЛОГ: строка-раздел, раскрывается вниз вместе с настройкой очистки.
        Box(Modifier.fillMaxWidth().padding(top = 14.dp).height(1.dp).background(Line))
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp).clickable { toggle("log") },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                s[R.string.set_log], color = Caption, fontSize = 12.sp,
                letterSpacing = 1.sp, fontWeight = FontWeight.Bold
            )
            Text(
                if (logOpen) s[R.string.acc_collapse] else s[R.string.acc_expand],
                color = Cyan, fontSize = 12.sp
            )
        }
        if (logOpen) {
            Column(
                Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(16.dp))
                    .background(Elev).border(1.dp, CardLine, RoundedCornerShape(16.dp)).padding(12.dp)
            ) {
                Text(
                    s[R.string.set_log_clear], color = TextMain, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Row(Modifier.padding(top = 8.dp)) {
                    listOf(
                        30 to s[R.string.set_min_30],
                        60 to s[R.string.set_hour_1],
                        120 to s[R.string.set_hour_2],
                    ).forEach { (min, label) ->
                        Seg(label, clearMin == min, Modifier.weight(1f)) {
                            clearMin = min
                            Store.logClearMinutes(ctx, min)
                            VpnLog.setPeriodMinutes(min)
                        }
                    }
                }
                Text(
                    s[R.string.set_log_clear_hint], color = Muted, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    log.ifBlank { s[R.string.set_log_empty] }, color = Muted, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
                // Кнопки делят ширину поровну и ужимают текст: на узких экранах
                // «Скопировать» больше не выталкивает третью кнопку за край.
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LogButton(s[R.string.set_refresh], CardSoft, OnCard, Modifier.weight(1f)) {
                        scope.launch { log = withContext(Dispatchers.IO) { VpnLog.text() } }
                    }
                    LogButton(s[R.string.set_copy], Purple, OnAccent, Modifier.weight(1f)) {
                        scope.launch {
                            val text = withContext(Dispatchers.IO) { VpnLog.text() }
                            ctx.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("winger", text))
                        }
                    }
                    LogButton(s[R.string.set_clear], Color(0xFF8B3A3A), OnAccent, Modifier.weight(1f)) {
                        VpnLog.clear()
                        log = ""
                    }
                }
            }
        }
    }
}


/** Раздел настроек: заголовок с шевроном и сворачиваемое содержимое. */
@Composable
private fun SettingsSection(
    title: String,
    expanded: Boolean,
    first: Boolean = false,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Линия между разделами: в свёрнутом виде заголовки иначе сливаются в список.
    if (!first) Box(Modifier.fillMaxWidth().padding(top = 14.dp).height(1.dp).background(Line))
    Row(
        Modifier.fillMaxWidth().padding(top = if (first) 14.dp else 12.dp).clickable { onToggle() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Caption, fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
        Text(if (expanded) "⌄" else "›", color = Caption, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
    if (expanded) {
        Column(
            Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(16.dp))
                .background(Elev).border(1.dp, CardLine, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            content = content,
        )
    }
}

/** Строка с переключателем; `divider` рисует черту сверху — между пунктами. */
@Composable
private fun SwitchRow(
    title: String,
    hint: String?,
    checked: Boolean,
    divider: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    if (divider) Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(1.dp).background(Line))
    Row(
        Modifier.fillMaxWidth().padding(top = if (divider) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (hint != null) {
                Text(hint, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Switch(checked, onChange)
    }
}

/** Цвета поля ввода под текущую тему: в светлой — почти белый фон и тёмный текст. */
@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedTextColor = TextMain,
    unfocusedTextColor = TextMain,
    focusedContainerColor = if (AppTheme.p.isLight) Color(0xFFF7F9FC) else Elev,
    unfocusedContainerColor = if (AppTheme.p.isLight) Color(0xFFF7F9FC) else Elev,
    focusedIndicatorColor = Purple,
    unfocusedIndicatorColor = Line,
)


/**
 * Кнопка лога: занимает отведённую долю ширины, текст по центру и уменьшается,
 * если не влезает. Раньше кнопки были по содержимому и на узких экранах
 * «Скопировать» вылезала за край карточки.
 */
@Composable
private fun LogButton(label: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(bg).clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints {
            val size = fitLabelSize(label, maxWidth, 13.sp, 9.sp)
            Text(
                label, color = fg, fontSize = size, maxLines = 1,
                softWrap = false, overflow = TextOverflow.Clip, textAlign = TextAlign.Center,
            )
        }
    }
}

/** Подбирает кегль подписи кнопки под её ширину. */
@Composable
private fun fitLabelSize(text: String, available: Dp, max: TextUnit, min: TextUnit): TextUnit {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    return remember(text, available, max, min) {
        var size = max
        while (size.value > min.value) {
            val width = measurer.measure(
                text = androidx.compose.ui.text.AnnotatedString(text),
                style = androidx.compose.ui.text.TextStyle(fontFamily = WingerSans, fontSize = size),
            ).size.width
            if (with(density) { width.toDp() } <= available) break
            size = (size.value - 0.5f).sp
        }
        size
    }
}

