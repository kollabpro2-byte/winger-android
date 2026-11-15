package pro.wingerapp.vpn.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import pro.wingerapp.vpn.BuildConfig
import pro.wingerapp.vpn.R
import pro.wingerapp.vpn.ServerNode
import pro.wingerapp.vpn.fmtBytes
import pro.wingerapp.vpn.net.Sub
import pro.wingerapp.vpn.tunTraffic

/**
 * Домашний экран. Раскладка зафиксирована макетом 0.1.75:
 *
 *  • «WINGER» сразу под строкой статуса (statusBarsPadding на корне HomeScreen);
 *  • строка трафика живёт в слоте фиксированной высоты — при включении ничего
 *    не сдвигается, орёл остаётся на своём месте;
 *  • тариф на главной не показывается, только «осталось N дней»;
 *  • три показателя одной строкой: осталось / устройства / трафик, слова
 *    заменены значками;
 *  • карточка локации: «Текущее местоположение», имя из подписки как есть,
 *    пинг с точкой состояния, справа — обновление и замер пинга;
 *  • круг-кнопка подписан ВКЛ / ВЫКЛ, фиолетового состояния нет: включение
 *    читается яркостью орла и циановым свечением (1.2 с), без прочих анимаций.
 */
@Composable
internal fun HomeTab(
    ctx: Context, hwid: String, sub: Sub?, servers: List<ServerNode>, pings: Map<String, Int?>,
    place: String, on: Boolean, pinging: Boolean, refreshing: Boolean, err: String?, notice: String?,
    trafficIntervalSec: Int,
    raw: String, connect: (String) -> Unit, disconnect: () -> Unit, reload: (Boolean) -> Unit,
    onSelect: (String) -> Unit, onToggle: () -> Unit
) {
    val s = rememberStrings()

    // Группировка — проход по списку со сравнением строк, а пинг обновляется
    // ежесекундно. Считаем её только при смене подписки, не на каждой перерисовке.
    val groups = remember(servers) { grouped(servers) }
    // Масштаб шрифтов списка под ширину экрана; сжимается, если самое длинное
    // имя перестало влезать целиком.
    val listScale by rememberListScale(groups)

    // «подписка обновлена» — тихой строкой в шапке списка. Только альфа: строка
    // уже занимает место, поэтому ничего не сдвигается.
    val noticeAlpha = remember { Animatable(0f) }
    LaunchedEffect(notice) {
        if (notice.isNullOrBlank()) {
            noticeAlpha.animateTo(0f, tween(600))
        } else {
            noticeAlpha.snapTo(0f)
            noticeAlpha.animateTo(1f, tween(900))
        }
    }

    // Вспышка кнопки обновления после успеха: два удара и долгое затухание.
    val flash = remember { Animatable(0f) }
    var wasRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing, err) {
        val succeeded = wasRefreshing && !refreshing && err == null
        wasRefreshing = refreshing
        if (!succeeded) return@LaunchedEffect
        flash.snapTo(0f)
        flash.animateTo(
            0f,
            keyframes {
                durationMillis = 3200
                0.78f at 480
                0.55f at 960
                0.92f at 1440
                0f at 3000
            },
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("WINGER", color = Cyan, fontWeight = FontWeight.ExtraBold, fontSize = 16.5.sp)
            Text(
                // Номер сборки виден всегда: по нему проверяют, что установлен
                // именно присланный APK, а не предыдущий.
                "v${BuildConfig.VERSION_NAME}·${BuildConfig.VERSION_CODE}",
                color = Muted, fontSize = 10.5.sp, modifier = Modifier.padding(start = 5.dp)
            )
        }
        if (sub == null) {
            Text(s[R.string.home_no_subscription], color = Color(0xFFF87171), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            Text(s[R.string.home_no_subscription_hint], color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }


        // Слот фиксированной высоты: строка проявляется, но место занято всегда.
        TrafficSlot(on = on, intervalSec = trafficIntervalSec)

        Orb(on = on, hint = s[R.string.home_orb_hint], onToggle = onToggle)

        StatsRow(
            days = daysUntil(sub?.expires)?.let { s.quantity(R.plurals.days, it) } ?: s[R.string.fmt_none],
            devices = s.format(
                R.string.home_devices_value,
                sub?.devicesUsed ?: 0,
                if ((sub?.devices ?: 0) <= 0) "∞" else sub!!.devices.toString()
            ),
            traffic = trafficShort(sub, s),
        )

        val pingMs = pings[place]
        // Подпись раздела — тем же кеглем, что заголовки групп серверов ниже.
        Text(s[R.string.home_location], color = Caption, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
        LocationCard(
            place = place.ifBlank { s[R.string.home_select_server] },
            ping = when {
                place.isBlank() || !pings.containsKey(place) -> null
                pingMs == null || pingMs == -1 -> s[R.string.home_ping_unknown]
                else -> s.format(R.string.home_ping_ms, pingMs)
            },
            live = on,
        )

        err?.let {
            Text(it, color = Color(0xFFF87171), fontSize = 12.sp, maxLines = 3, modifier = Modifier.padding(top = 6.dp))
        }

        if (servers.isEmpty()) Text(s[R.string.home_loading], color = Dim, modifier = Modifier.padding(top = 8.dp))
        groups.forEachIndexed { index, group ->
            if (index == 0) {
                // Первый заголовок группы и есть строка действий: заголовок слева,
                // «подписка обновлена» по центру, обновление и пинг справа.
                ListHeaderRow(
                    title = s[group.titleRes],
                    scale = listScale,
                    noticeText = notice.orEmpty().lowercase(),
                    noticeAlpha = noticeAlpha.value,
                    flash = flash.value,
                    refreshing = refreshing,
                    pinging = pinging,
                    onRefresh = { reload(false) },
                    onPing = { reload(true) },
                )
            } else {
                Text(
                    s[group.titleRes], color = Caption, fontSize = (11f * listScale).sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = (10f * listScale).dp, bottom = (4f * listScale).dp)
                )
            }
            group.nodes.forEach { node ->
                ServerRow(
                    node = node,
                    proto = s.format(R.string.home_server_proto, node.port),
                    selected = place == node.name,
                    pingKnown = pings.containsKey(node.name),
                    waiting = pinging && !pings.containsKey(node.name),
                    ms = pings[node.name],
                    noneLabel = s[R.string.home_ping_none],
                    unknownLabel = s[R.string.home_ping_unknown],
                    scale = listScale,
                    onClick = { onSelect(node.name) },
                )
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

/**
 * Круг-кнопка подключения. Единственная анимация — медленное «загорание»:
 * орёл проявляется и подсвечивается за 1.2 с. Цвет не меняется на фиолетовый.
 */
@Composable
private fun Orb(on: Boolean, hint: String, onToggle: () -> Unit) {
    // Медленное «ламповое» загорание: орел проявляется 2 с с задержкой 0.5 с,
    // гаснет быстрее (1.5 с) — чтобы слово успело появиться строго после него.
    val lit by animateFloatAsState(
        if (on) 1f else 0f,
        if (on) tween(2000, delayMillis = 500) else tween(1500),
        label = "lit",
    )
    val eagleScale by animateFloatAsState(
        if (on) 1f else 0.88f,
        if (on) tween(2400, delayMillis = 500) else tween(1700),
        label = "eagleScale",
    )
    // Подпись: появляется с задержкой 1.6 с (орла уже нет), исчезает за 0.45 с.
    val hintAlpha by animateFloatAsState(
        if (on) 0f else 1f,
        if (on) tween(450) else tween(1200, delayMillis = 1600),
        label = "hint",
    )
    // Ободок: в покое он виден как тонкая «каря» (1.5 dp, 25% белого), при
    // включении разгорается до 3.5 dp циановым — «пожирнее».
    val border = lerp(Color(0x40FFFFFF), Cyan.copy(alpha = .85f), lit)
    val borderWidth = 1.5f + 2f * lit
    val glow by animateFloatAsState(if (on) 1f else 0f, tween(2400), label = "glow")

    Box(Modifier.fillMaxWidth().height(190.dp).padding(top = 6.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(176.dp)
                .graphicsLayer {
                    shadowElevation = (6f + 12f * lit) * density
                    shape = CircleShape
                    clip = true
                }
                .background(
                    Brush.radialGradient(
                        if (AppTheme.p.isLight)
                            listOf(Color(0xFFFFFFFF), Color(0xFFF1F4F9), Color(0xFFE1E7EF))
                        else
                            listOf(Color(0xFF0E1B2C), Color(0xFF0A1220), Color(0xFF070C14))
                    )
                )
                .border(borderWidth.dp, border, CircleShape)
                .clickable { onToggle() },
            contentAlignment = Alignment.Center
        ) {
            // Внутреннее свечение — только при включении.
            Box(
                Modifier.matchParentSize().alpha(glow).background(
                    Brush.radialGradient(listOf(Color(0x5722D3EE), Color(0x1122D3EE), Color(0x0022D3EE)))
                )
            )
            // Орёл: только на включённом, 48 dp вместо прежних 96.
            Image(
                painterResource(R.drawable.eagle), null,
                Modifier.size(32.dp).graphicsLayer {
                    alpha = lit
                    scaleX = eagleScale
                    scaleY = eagleScale
                },
                contentScale = ContentScale.Fit,
            )
            // Подпись «включить»: почти прозрачная, гаснет сразу при нажатии.
            Text(
                hint.lowercase(),
                color = Color(0xFF9FE6F7).copy(alpha = .22f * hintAlpha),
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp,
            )
        }
    }
}

private enum class StatKind { Clock, Phone, Traffic }

/** Три показателя одной строкой: значок в плашке, подпись, значение. */
@Composable
private fun StatsRow(days: String, devices: String, traffic: String) {
    val s = rememberStrings()
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(18.dp))
            .background(Elev).border(1.dp, CardLine, RoundedCornerShape(18.dp))
            .padding(vertical = 11.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // В светлой теме неоновые сиреневый и циан на белой карточке бледнеют.
        val c1 = if (AppTheme.p.isLight) Color(0xFF0E7490) else Color(0xFF22D3EE)
        val c2 = if (AppTheme.p.isLight) Color(0xFF7C3AED) else Color(0xFFC084FC)
        val c3 = if (AppTheme.p.isLight) Color(0xFF2563EB) else Color(0xFF7FB0FF)
        Stat(Modifier.weight(1f), StatKind.Clock, c1, c1.copy(alpha = .12f), s[R.string.home_days_left], days)
        VDivider()
        Stat(Modifier.weight(1f), StatKind.Phone, c2, c2.copy(alpha = .14f), s[R.string.home_devices_label], devices)
        VDivider()
        Stat(Modifier.weight(1f), StatKind.Traffic, c3, c3.copy(alpha = .14f), s[R.string.home_traffic_label], traffic, unit = s[R.string.fmt_gb_unit])
    }
}

@Composable
private fun VDivider() {
    Box(Modifier.width(1.dp).height(26.dp).background(Line))
}

@Composable
private fun Stat(mod: Modifier, kind: StatKind, tint: Color, bg: Color, label: String, value: String, unit: String? = null) {
    // Значение делим на число и «хвост»: «436 дней» → «436» + мелкое «дней»,
    // «285 ГБ» → «285» + мелкое «ГБ». Если в хвосте есть цифры («2 из 3»),
    // не делим — иначе получилось бы «2» + «из 3» мелким.
    val parts = value.trim().split(" ", limit = 2)
    val number = parts[0]
    val tail = parts.getOrNull(1)
    val suffix = unit ?: tail?.takeIf { it.none { ch -> ch.isDigit() } }
    val whole = if (suffix == null) value.trim() else number

    Row(mod.padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(RoundedCornerShape(9.dp)).background(bg), contentAlignment = Alignment.Center) {
            StatIcon(kind, tint)
        }
        Column(Modifier.padding(start = 9.dp).weight(1f)) {
            Text(label, color = Muted, fontSize = 9.sp, letterSpacing = .6.sp, maxLines = 1, overflow = TextOverflow.Clip)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Кегль подбирается по ширине ячейки: на узких экранах и при
                // крупных числах значение уменьшается, а не обрезается.
                val size = fitValueSize(whole, suffix, maxWidth, 12.5.sp, 8.sp)
                Row {
                    Text(
                        whole, color = TextMain, fontSize = size, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                    )
                    if (suffix != null) Text(
                        suffix, color = Muted, fontSize = (size.value * .72f).sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, softWrap = false,
                        modifier = Modifier.padding(start = 2.dp, top = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * Подбирает кегль значения так, чтобы число и мелкая единица влезли в [available].
 * Идём от [max] вниз шагом 0.5 sp: «285 ГБ» на узком экране уменьшается целиком,
 * а не обрезается.
 */
@Composable
private fun fitValueSize(number: String, suffix: String?, available: Dp, max: TextUnit, min: TextUnit): TextUnit {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(number, suffix, available, max, min) {
        var size = max
        while (size.value > min.value) {
            val style = TextStyle(fontSize = size, fontWeight = FontWeight.SemiBold)
            var width = measurer.measure(AnnotatedString(number), style).size.width
            if (suffix != null) {
                val tailStyle = TextStyle(fontSize = (size.value * .72f).sp, fontWeight = FontWeight.SemiBold)
                width += measurer.measure(AnnotatedString(suffix), tailStyle).size.width
                width += with(density) { 4.dp.toPx() }.toInt()
            }
            if (with(density) { width.toDp() } <= available) break
            size = (size.value - 0.5f).sp
        }
        size
    }
}

/** Значки рисуются на Canvas: без внешних иконок и лишних зависимостей. */
@Composable
private fun StatIcon(kind: StatKind, tint: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(15.dp)) {
        val w = size.width
        val h = size.height
        val sw = 1.6.dp.toPx()
        when (kind) {
            StatKind.Clock -> {
                drawCircle(tint, radius = w * 0.42f, style = Stroke(width = sw))
                drawLine(tint, Offset(w * 0.5f, h * 0.28f), Offset(w * 0.5f, h * 0.52f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.5f, h * 0.52f), Offset(w * 0.72f, h * 0.64f), sw, StrokeCap.Round)
            }
            StatKind.Phone -> {
                drawRoundRect(
                    tint, topLeft = Offset(w * 0.3f, h * 0.1f), size = Size(w * 0.4f, h * 0.8f),
                    cornerRadius = CornerRadius(w * 0.11f), style = Stroke(width = sw)
                )
                drawCircle(tint, radius = w * 0.045f, center = Offset(w * 0.5f, h * 0.78f))
            }
            StatKind.Traffic -> {
                drawLine(tint, Offset(w * 0.34f, h * 0.78f), Offset(w * 0.34f, h * 0.2f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.34f, h * 0.2f), Offset(w * 0.2f, h * 0.36f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.34f, h * 0.2f), Offset(w * 0.48f, h * 0.36f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.66f, h * 0.22f), Offset(w * 0.66f, h * 0.8f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.66f, h * 0.8f), Offset(w * 0.52f, h * 0.64f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.66f, h * 0.8f), Offset(w * 0.8f, h * 0.64f), sw, StrokeCap.Round)
            }
        }
    }
}


/**
 * Карточка локации. Имя берётся из подписки как есть — флаги уже в названии,
 * поэтому отдельного значка страны нет. Справа на той же линии — обновление
 * подписки и замер пинга.
 */
@Composable
private fun LocationCard(place: String, ping: String?, live: Boolean) {
    // В светлой теме полупрозрачная тонировка на сером фоне почти не видна —
    // подкладываем светлую карточку, а тонировку оставляем акцентом.
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(18.dp))
            .background(if (AppTheme.p.isLight) Elev else Color.Transparent)
            .background(Brush.linearGradient(listOf(Color(0x338B5CF6), Color(0x223B82F6), Color(0x2222D3EE))))
            .border(1.dp, if (AppTheme.p.isLight) CardLine else Purple.copy(alpha = .32f), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BoxWithConstraints(Modifier.weight(1f)) {
            // Имя подгоняется по ширине: длинное название уменьшается, пока не
            // влезет целиком, вместо обрезки многоточием.
            val nameSize = fitNameSize(place, maxWidth, 19.5.sp, 10.sp)
            Text(
                place, color = TextMain, fontSize = nameSize, fontWeight = FontWeight.Bold,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip
            )
        }
        // Пинг — справа от названия, в той же строке.
        Row(
            Modifier.padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(7.dp).clip(CircleShape)
                    .background(if (live) (if (AppTheme.p.isLight) Color(0xFF0FA85A) else Color(0xFF3DFF7A)) else (if (AppTheme.p.isLight) Color(0xFF8296AA) else Color(0xFF5A6A7E)))
            )
            Text(
                ping ?: "—", color = if (AppTheme.p.isLight) Color(0xFF2F4A5E) else Color(0xFF9FE6F7), fontSize = 10.5.sp,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}

/** Тот же голубой, что у «подписка обновлена» и вспышки кнопки обновления.
 *  В светлой теме он должен быть темнее: неоновый на сером не читается. */
private val NoticeCyan: Color
    get() = if (AppTheme.p.isLight) Color(0xFF0E7490) else Color(0xFF7FE3FF)

/**
 * Строка «↑ / таймер / ↓» в собственном composable: счётчики тикают раз в секунду,
 * и раньше каждый тик перерисовывал весь экран вместе со списком нод. Теперь
 * перерисовывается только эта строка.
 */
@Composable
private fun TrafficSlot(on: Boolean, intervalSec: Int) {
    val zero = remember { "0 Б" }
    var elapsed by remember { mutableStateOf("00:00:00") }
    var up by remember { mutableStateOf(zero) }
    var down by remember { mutableStateOf(zero) }
    val alpha by animateFloatAsState(if (on) 1f else 0f, tween(350), label = "traffic")

    LaunchedEffect(on, intervalSec) {
        if (!on) {
            elapsed = "00:00:00"; up = zero; down = zero
            return@LaunchedEffect
        }
        val startAt = System.currentTimeMillis()
        val intervalMs = intervalSec.coerceIn(1, 60) * 1000L
        // TrafficStats даёт счётчики с момента загрузки устройства, поэтому
        // берём базовое значение при подключении и показываем разницу — иначе
        // в строке висел бы весь трафик за сутки.
        var base: Pair<Long, Long>? = null
        var lastTrafficAt = 0L
        while (true) {
            val now = System.currentTimeMillis()
            val sec = ((now - startAt) / 1000).toInt()
            // Таймер тикает раз в секунду — он и есть «время работы».
            elapsed = "%02d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
            // Счётчики — раз в intervalSec: чаще них меняются только шумы, а
            // каждое чтение TrafficStats будит процесс.
            if (now - lastTrafficAt >= intervalMs) {
                lastTrafficAt = now
                val tun = tunTraffic()
                if (tun != null) {
                    val b = base
                    if (b == null) base = tun else {
                        down = fmtBytes((tun.first - b.first).coerceAtLeast(0L))
                        up = fmtBytes((tun.second - b.second).coerceAtLeast(0L))
                    }
                }
            }
            delay(1000)
        }
    }

    Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.CenterStart) {
        if (on) Row(
            Modifier.fillMaxWidth().alpha(alpha),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("↑ $up", color = if (AppTheme.p.isLight) Color(0xFF0F9D6B) else Color(0xFF7EE0B0), fontSize = 13.sp)
            Text(elapsed, color = Muted, fontSize = 13.sp)
            Text("↓ $down", color = if (AppTheme.p.isLight) Color(0xFF2563EB) else Color(0xFF9ECBFF), fontSize = 13.sp)
        }
    }
}

/**
 * Шапка списка: заголовок первой группы слева, сообщение по центру и кнопки
 * обновления/пинга справа. Кнопки вынесены из карточки локации — так они
 * разделяют «что выбрано» и «из чего выбирать».
 */
@Composable
private fun ListHeaderRow(
    title: String, scale: Float, noticeText: String, noticeAlpha: Float, flash: Float,
    refreshing: Boolean, pinging: Boolean, onRefresh: () -> Unit, onPing: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = (12f * scale).dp, bottom = (12f * scale).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Caption, fontSize = (11f * scale).sp, fontWeight = FontWeight.Bold)
        Text(
            noticeText, color = NoticeCyan.copy(alpha = noticeAlpha),
            fontSize = (11f * scale).sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = .25.sp, maxLines = 1, softWrap = false,
            textAlign = TextAlign.Center, overflow = TextOverflow.Clip,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )
        // Вспышка кнопки обновления: два удара и долгое затухание.
        val baseBg = if (AppTheme.p.isLight) Color(0xFFF4F7FA) else Color.White.copy(alpha = .06f)
        val baseRing = if (AppTheme.p.isLight) Color(0xFF7E8B9C) else Color.White.copy(alpha = .10f)
        val bg = lerp(baseBg, NoticeCyan.copy(alpha = .92f), flash)
        val ring = lerp(baseRing, NoticeCyan, flash)
        val ink = lerp(if (AppTheme.p.isLight) Color(0xFF0E7490) else Color(0xFFC9D7E4), Color(0xFF052A34), flash)
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(bg)
                .border(1.dp, ring, CircleShape).clickable { onRefresh() },
            contentAlignment = Alignment.Center
        ) {
            if (refreshing) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Cyan)
            else RefreshIcon(ink, 16.dp)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Purple.copy(alpha = .22f))
                .border(1.dp, Purple.copy(alpha = .5f), CircleShape)
                .clickable(enabled = !pinging) { onPing() },
            contentAlignment = Alignment.Center
        ) {
            BoltIcon(Color(0xFFE9D5FF), 11.dp)
        }
    }
}

/**
 * Масштаб шрифтов списка под ширину экрана: 360 dp → 0.95, 480 dp → до 1.25.
 * Уменьшается шагами, если самое длинное имя перестало влезать целиком, — так
 * большой экран получает крупный текст, но ничего не обрезается.
 */
@Composable
private fun rememberListScale(groups: List<ServerGroup>): State<Float> {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widthDp = LocalConfiguration.current.screenWidthDp
    // produceState, а не remember: измерение шрифтов уходит в корутину композиции
    // и не держит первый кадр. Значение появляется сразу после раскладки.
    return produceState(1f, groups, widthDp, measurer, density) {
        val longest = groups.flatMap { it.nodes }.maxByOrNull { it.name.length }?.name.orEmpty()
        val available = (widthDp - 32f - 24f - 14f - 8f - 44f).coerceAtLeast(120f)
        // SRV_ZOOM = 1.5: секции серверов крупнее, как в утверждённом макете.
        // Если длинное имя перестаёт влезать, масштаб шагами уменьшается —
        // «крупнее, но всегда целиком».
        var scale = (widthDp / 390f).coerceIn(0.95f, 1.25f) * 1.5f
        while (scale > 1.0f) {
            val width = measurer.measure(
                text = AnnotatedString(longest),
                style = TextStyle(fontSize = (13f * scale).sp),
            ).size.width
            if (with(density) { width.toDp().value } <= available) break
            scale -= 0.05f
        }
        value = scale
    }
}

/** Строка сервера: выбранная подсвечена заметно — заливка, рамка 2 dp и галочка. */
@Composable
private fun ServerRow(
    node: ServerNode, proto: String, selected: Boolean, pingKnown: Boolean, waiting: Boolean,
    ms: Int?, noneLabel: String, unknownLabel: String, scale: Float = 1f, onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = (3f * scale).dp)
            .clip(RoundedCornerShape((14f * scale).dp))
            .background(if (selected) Purple.copy(alpha = .30f) else Elev)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Purple else Line,
                RoundedCornerShape((14f * scale).dp)
            )
            .clickable { onClick() }
            .padding(horizontal = (12f * scale).dp, vertical = (8f * scale).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (selected) "✓" else "", color = Purple, fontSize = (14f * scale).sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.width((14f * scale).dp)
        )
        Column(Modifier.weight(1f).padding(start = (4f * scale).dp)) {
            Text(
                node.name, color = if (selected) (if (AppTheme.p.isLight) Color(0xFF3B0764) else Color.White) else TextMain,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = (13f * scale).sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(proto, color = Muted, fontSize = (10f * scale).sp, maxLines = 1)
        }
        when {
            waiting -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Cyan)
            pingKnown -> {
                // Палитра пинга своя для каждой темы: мятный и жёлтый на сером
                // и белом фоне не читаются.
                val p = AppTheme.p
                val c = when {
                    ms == null || ms == -1 -> p.pingNone
                    ms < 120 -> p.pingFast
                    ms < 200 -> p.pingMid
                    else -> p.pingSlow
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(c))
                    Text(
                        when {
                            ms == null -> noneLabel
                            ms == -1 -> unknownLabel
                            else -> ms.toString()
                        },
                        color = c, fontSize = (11f * scale).sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
    }
}

/** «197,0» — единица измерения рисуется отдельно, мелкой подписью. */
private fun trafficShort(sub: Sub?, s: Strings): String {
    if (sub == null) return s[R.string.fmt_none]
    return s.number(sub.trafficUsedGb)
}



/**
 * Подбирает кегль, при котором [text] целиком влезает в [available] по ширине.
 * Идём от [max] вниз шагом 0.5 sp до [min]: длинное имя сервера уменьшается,
 * а не обрезается многоточием.
 */
@Composable
private fun fitNameSize(text: String, available: Dp, max: TextUnit, min: TextUnit): TextUnit {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(text, available, max, min) {
        var size = max
        while (size.value > min.value) {
            val width = measurer.measure(
                text = AnnotatedString(text),
                style = TextStyle(fontSize = size, fontWeight = FontWeight.Bold),
            ).size.width
            if (with(density) { width.toDp() } <= available) break
            size = (size.value - 0.5f).sp
        }
        size
    }
}

/**
 * Круговая стрелка обновления рисуется на Canvas, а не глифом «⟳»: у шрифтового
 * символа разные боковые поля, и в кружке он садится ниже и левее центра —
 * на маленьких экранах это заметно.
 */
@Composable
private fun RefreshIcon(tint: Color, size: Dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * .11f
        val r = w * .32f
        val cx = w / 2f
        val cy = h / 2f
        // Дуга с разрывом сверху справа — там будет остриё.
        drawArc(
            color = tint,
            startAngle = -55f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        // Остриё: две короткие линии от конца дуги.
        val rad = Math.toRadians(-55.0)
        val ex = cx + r * cos(rad).toFloat()
        val ey = cy + r * sin(rad).toFloat()
        val tip = w * .16f
        val a1 = rad + Math.PI / 2
        val a2 = rad - Math.PI / 2
        drawLine(tint, Offset(ex, ey), Offset(ex - tip * cos(a1).toFloat(), ey - tip * sin(a1).toFloat()), stroke, StrokeCap.Round)
        drawLine(tint, Offset(ex, ey), Offset(ex - tip * cos(a2).toFloat(), ey - tip * sin(a2).toFloat()), stroke, StrokeCap.Round)
    }
}

/** Молния: эмодзи ⚡ не слушается fontSize, поэтому рисуем её сами. */
@Composable
private fun BoltIcon(tint: Color, size: Dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.60f, 0f)
            lineTo(w * 0.14f, h * 0.56f)
            lineTo(w * 0.47f, h * 0.56f)
            lineTo(w * 0.38f, h)
            lineTo(w * 0.88f, h * 0.40f)
            lineTo(w * 0.53f, h * 0.40f)
            close()
        }
        drawPath(path, tint)
    }
}
