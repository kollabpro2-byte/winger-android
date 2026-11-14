package pro.wingerapp.vpn.ui

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pro.wingerapp.vpn.BuildConfig
import pro.wingerapp.vpn.R
import pro.wingerapp.vpn.ServerNode
import pro.wingerapp.vpn.net.Sub

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
    place: String, on: Boolean, elapsed: String, up: String, down: String, pinging: Boolean, refreshing: Boolean, err: String?, notice: String?,
    raw: String, connect: (String) -> Unit, disconnect: () -> Unit, reload: (Boolean) -> Unit,
    onSelect: (String) -> Unit, onToggle: () -> Unit
) {
    val s = rememberStrings()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("WINGER", color = Cyan, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
            Text(
                if (BuildConfig.DEBUG) "v${BuildConfig.VERSION_NAME}·${BuildConfig.VERSION_CODE}"
                else "v${BuildConfig.VERSION_NAME}",
                color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp)
            )
        }
        if (sub == null) {
            Text(s[R.string.home_no_subscription], color = Color(0xFFF87171), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            Text(s[R.string.home_no_subscription_hint], color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }


        // Слот фиксированной высоты: строка проявляется, но место занято всегда.
        val trafficAlpha by animateFloatAsState(if (on) 1f else 0f, tween(350), label = "traffic")
        Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.CenterStart) {
            if (on) Row(
                Modifier.fillMaxWidth().alpha(trafficAlpha),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("↑ $up", color = Color(0xFF7EE0B0), fontSize = 13.sp)
                Text(elapsed, color = Muted, fontSize = 13.sp)
                Text("↓ $down", color = Color(0xFF9ECBFF), fontSize = 13.sp)
            }
        }

        Orb(on = on, labelOn = s[R.string.home_orb_off], labelOff = s[R.string.home_orb_on], onToggle = onToggle)

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
        LocationCard(
            title = s[R.string.home_location],
            place = place.ifBlank { s[R.string.home_select_server] },
            ping = when {
                place.isBlank() || !pings.containsKey(place) -> null
                pingMs == null || pingMs == -1 -> s[R.string.home_ping_unknown]
                else -> s.format(R.string.home_ping_ms, pingMs)
            },
            live = on,
            refreshing = refreshing,
            pinging = pinging,
            onRefresh = { reload(false) },
            onPing = { reload(true) },
        )

        err?.let {
            Text(it, color = Color(0xFFF87171), fontSize = 12.sp, maxLines = 3, modifier = Modifier.padding(top = 6.dp))
        }
        if (notice != null) Text(
            notice, color = Color(0xFF86EFAC), fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(20.dp))
                .background(Color(0x2434C759)).padding(horizontal = 12.dp, vertical = 6.dp)
        )

        if (servers.isEmpty()) Text(s[R.string.home_loading], color = Dim, modifier = Modifier.padding(top = 8.dp))
        Column {
            grouped(servers).forEach { group ->
                Text(s[group.titleRes], color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
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
                        onClick = { onSelect(node.name) },
                    )
                }
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
private fun Orb(on: Boolean, labelOn: String, labelOff: String, onToggle: () -> Unit) {
    val lit by animateFloatAsState(if (on) 1f else 0f, tween(1200), label = "lit")
    Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(176.dp)
                .shadow(if (on) 18.dp else 10.dp, CircleShape)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF24506F), Color(0xFF122B42), Color(0xFF0A1628))))
                .border(2.dp, if (on) Cyan.copy(alpha = .85f) else Color(0xFF3A7CA0), CircleShape)
                .clickable { onToggle() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.matchParentSize().alpha(lit * .55f).background(
                    Brush.radialGradient(listOf(Color(0x5722D3EE), Color(0x1122D3EE), Color(0x0022D3EE)))
                )
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painterResource(R.drawable.eagle), "Winger",
                    Modifier.size(96.dp).alpha(0.5f + 0.5f * lit), contentScale = ContentScale.Fit
                )
                Text(
                    if (on) labelOn else labelOff,
                    color = if (on) Color(0xFFC7F5FF) else Color(0xFF9FE6F7),
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
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
            .background(Color(0x08FFFFFF)).border(1.dp, Color(0x0DFFFFFF), RoundedCornerShape(18.dp))
            .padding(vertical = 11.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Stat(Modifier.weight(1f), StatKind.Clock, Color(0xFF22D3EE), Color(0x1F22D3EE), s[R.string.home_days_left], days)
        VDivider()
        Stat(Modifier.weight(1f), StatKind.Phone, Color(0xFFC084FC), Color(0x24C084FC), s[R.string.home_devices_label], devices)
        VDivider()
        Stat(Modifier.weight(1f), StatKind.Traffic, Color(0xFF7FB0FF), Color(0x247FB0FF), s[R.string.home_traffic_label], traffic, unit = s[R.string.fmt_gb_unit])
    }
}

@Composable
private fun VDivider() {
    Box(Modifier.width(1.dp).height(26.dp).background(Color(0x12FFFFFF)))
}

@Composable
private fun Stat(mod: Modifier, kind: StatKind, tint: Color, bg: Color, label: String, value: String, unit: String? = null) {
    Row(mod.padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(RoundedCornerShape(9.dp)).background(bg), contentAlignment = Alignment.Center) {
            StatIcon(kind, tint)
        }
        Column(Modifier.padding(start = 9.dp)) {
            Text(label, color = Color(0xFF8794A6), fontSize = 9.sp, letterSpacing = .6.sp, maxLines = 1)
            Row {
                Text(
                    value, color = Color(0xFFEDF3FA), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Clip
                )
                // Единица измерения — мелкой подписью рядом: «197,0» + «ГБ»
                // укладывается там, где «197,0 ГБ» пришлось бы обрезать.
                if (unit != null) Text(
                    unit, color = Color(0xFF8794A6), fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(start = 2.dp, top = 3.dp)
                )
            }
        }
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
private fun LocationCard(
    title: String, place: String, ping: String?, live: Boolean,
    refreshing: Boolean, pinging: Boolean, onRefresh: () -> Unit, onPing: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(listOf(Color(0x338B5CF6), Color(0x223B82F6), Color(0x2222D3EE)))
            )
            .border(1.dp, Purple.copy(alpha = .32f), RoundedCornerShape(20.dp))
            .padding(start = 13.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BoxWithConstraints(Modifier.weight(1f)) {
            // Имя сервера подгоняется по ширине: длинное название (например
            // «Автопилот · Топ-пинг | LTE») уменьшается, пока не влезет целиком,
            // вместо обрезки многоточием.
            val nameSize = fitNameSize(place, maxWidth, 13.sp, 7.sp)
            Column {
                Text(title, color = Color(0xFFA6B4C6), fontSize = 9.sp, letterSpacing = .6.sp, maxLines = 1)
                Text(
                    place, color = Color.White, fontSize = nameSize, fontWeight = FontWeight.Bold,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Clip
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(7.dp).clip(CircleShape)
                            .background(if (live) Color(0xFF3DFF7A) else Color(0xFF5A6A7E))
                    )
                    Text(
                        ping ?: "—", color = Color(0xFF9FE6F7), fontSize = 10.5.sp,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = .06f))
                .border(1.dp, Color.White.copy(alpha = .10f), CircleShape)
                .clickable { onRefresh() },
            contentAlignment = Alignment.Center
        ) {
            if (refreshing) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Cyan)
            else Text("⟳", color = Color(0xFFC9D7E4), fontSize = 15.sp)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Purple.copy(alpha = .22f))
                .border(1.dp, Purple.copy(alpha = .5f), CircleShape)
                .clickable(enabled = !pinging) { onPing() },
            contentAlignment = Alignment.Center
        ) {
            // Рисованная молния вместо эмодзи: глиф ⚡ игнорирует fontSize и
            // всегда рисуется крупным, поэтому размер задаётся здесь.
            BoltIcon(Color(0xFFE9D5FF), 11.dp)
        }
    }
}

/** Строка сервера: выбранная подсвечена заметно — заливка, рамка 2 dp и галочка. */
@Composable
private fun ServerRow(
    node: ServerNode, proto: String, selected: Boolean, pingKnown: Boolean, waiting: Boolean,
    ms: Int?, noneLabel: String, unknownLabel: String, onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) Purple.copy(alpha = .30f) else Elev)
            .border(if (selected) 2.dp else 1.dp, if (selected) Purple else Line, RoundedCornerShape(14.dp))
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (selected) "✓" else "", color = Purple, fontSize = 14.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.width(14.dp)
        )
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                node.name, color = if (selected) Color.White else Color(0xFFE8EEF6),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(proto, color = Muted, fontSize = 10.sp, maxLines = 1)
        }
        when {
            waiting -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Cyan)
            pingKnown -> {
                val c = when {
                    ms == null || ms == -1 -> Color(0xFF7C8A9B)
                    ms < 120 -> Color(0xFF7EE0B0)
                    ms < 200 -> Color(0xFFF0C14B)
                    else -> Color(0xFFF07171)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(c))
                    Text(
                        when {
                            ms == null -> noneLabel
                            ms == -1 -> unknownLabel
                            else -> ms.toString()
                        },
                        color = c, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp)
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
