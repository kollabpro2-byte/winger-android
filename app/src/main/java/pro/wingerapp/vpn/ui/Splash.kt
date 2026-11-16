package pro.wingerapp.vpn.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pro.wingerapp.vpn.R

/**
 * Заставка на время фоновой инициализации.
 *
 * Пока читается сессия из зашифрованного файла (Keystore, 100–300 мс), показываем
 * фон темы, «WINGER» и орла: пользователь видит бренд, а не пустой экран и не
 * мигающий экран входа.
 */
@Composable
internal fun Splash() {
    Box(Modifier.fillMaxSize().background(BgBrush), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                bitmap = rememberEagle(),
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
            )
            Text(
                "WINGER",
                color = Cyan,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.height(30.dp),
            )
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = Cyan.copy(alpha = .7f),
                strokeWidth = 1.8.dp,
            )
        }
    }
}
