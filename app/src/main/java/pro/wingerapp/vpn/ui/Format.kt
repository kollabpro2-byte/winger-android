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

internal data class ServerGroup(val titleRes: Int, val nodes: List<ServerNode>)

/**
 * Groups the subscription into the sections the home screen shows. The match
 * keywords are server *names* from the subscription, not UI text, so they are
 * deliberately not translated; only the group titles are.
 */
internal fun grouped(servers: List<ServerNode>): List<ServerGroup> {
    fun ServerNode.named(vararg keys: String) = keys.any { name.contains(it, ignoreCase = true) }
    val auto = servers.filter { it.named("Автопилот", "рекоменд") }
    // Each server belongs to exactly one section: a name like "YouTube обход"
    // used to be listed twice, which looked like two different servers.
    val yt = servers.filter { it !in auto && it.named("youtube", "ютуб") }
    val lte = servers.filter { it !in auto && it !in yt && it.named("LTE", "обход") }
    val rest = servers.filter { it !in auto && it !in yt && it !in lte }
    return listOf(
        ServerGroup(R.string.group_recommended, auto),
        ServerGroup(R.string.group_countries, rest),
        ServerGroup(R.string.group_youtube, yt),
        ServerGroup(R.string.group_bypass, lte),
    ).filter { it.nodes.isNotEmpty() }
}

/** "1,2 ГБ из 50 ГБ" — unlimited plans show ∞ instead of a limit. */
internal fun trafficText(sub: Sub?, s: Strings): String {
    if (sub == null) return s[R.string.fmt_none]
    val used = s.number(sub.trafficUsedGb)
    val total = if (sub.unlimitedTraffic || sub.trafficLimitGb <= 0) "∞"
    else s.format(R.string.fmt_gb, sub.trafficLimitGb)
    return s.format(R.string.fmt_gb_of, used, total)
}

/**
 * Whole days until [expires] ("2031-05-01" or an ISO timestamp). Returns null
 * when the date is missing or unparsable. [today] is injectable so the plural
 * rules can be tested without waiting for the clock.
 */
internal fun daysUntil(
    expires: String?,
    today: java.time.LocalDate = java.time.LocalDate.now(),
): Long? {
    if (expires.isNullOrBlank()) return null
    return runCatching {
        val date = java.time.LocalDate.parse(expires.take(10))
        java.time.temporal.ChronoUnit.DAYS.between(today, date)
    }.getOrNull()
}
