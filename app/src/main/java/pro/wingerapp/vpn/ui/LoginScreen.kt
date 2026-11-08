package pro.wingerapp.vpn.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.wingerapp.vpn.AppViewModel
import pro.wingerapp.vpn.R
import pro.wingerapp.vpn.Store
import pro.wingerapp.vpn.net.Api

@Composable
fun LoginScreen(
    vm: AppViewModel,
    ctx: Context,
    hwid: String,
    disconnect: () -> Unit,
    onOk: () -> Unit,
) {
    val s = rememberStrings()
    var user by remember { mutableStateOf(Store.login(ctx)) }
    var pass by remember { mutableStateOf(Store.password(ctx)) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showRegister by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // A stored session may still be valid: try it before asking for a password.
    LaunchedEffect(Unit) {
        if (user.isBlank() || pass.isBlank()) return@LaunchedEffect
        busy = true
        val alive = withContext(Dispatchers.IO) { runCatching { Api.subscription() != null }.getOrDefault(false) }
        if (alive) {
            busy = false
            onOk()
            return@LaunchedEffect
        }
        val failed = vm.signIn(user, pass)
        busy = false
        if (failed == null) onOk() else err = failed.ifBlank { s[R.string.login_no_network] }
    }

    if (showRegister) {
        RegisterForm(vm, onClose = { showRegister = false }) { login, password ->
            Store.save(ctx, login, password)
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
            Text(s[R.string.login_title], color = Muted, modifier = Modifier.padding(bottom = 20.dp))
            Field(user, s[R.string.login_label]) { user = it }
            Spacer(Modifier.height(10.dp))
            Field(pass, s[R.string.login_password], true) { pass = it }
            err?.let { Text(it, color = Color(0xFFF87171), modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Purple)
                    .clickable(enabled = !busy) {
                        busy = true
                        err = null
                        scope.launch {
                            val failed = vm.signIn(user, pass)
                            busy = false
                            if (failed == null) {
                                Store.save(ctx, user, pass)
                                onOk()
                            } else {
                                err = failed.ifBlank { s[R.string.login_no_network] }
                            }
                        }
                    }.padding(14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (busy) s[R.string.login_busy] else s[R.string.login_action],
                    color = Color.White, fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Elev)
                    .border(1.dp, Purple.copy(.5f), RoundedCornerShape(16.dp))
                    .clickable { showRegister = true }.padding(14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(s[R.string.login_register_prompt], color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            if (Store.running(ctx)) {
                Box(
                    Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(16.dp))
                        .background(Elev).border(1.dp, Purple.copy(.6f), RoundedCornerShape(16.dp))
                        .clickable { disconnect() }.padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(s[R.string.login_disable_vpn], color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}


@Composable
internal fun RegisterForm(vm: AppViewModel, onClose: () -> Unit, onDone: (String, String) -> Unit) {
    val s = rememberStrings()
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(Bg).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(s[R.string.reg_back], color = Cyan, modifier = Modifier.clickable { onClose() }.padding(bottom = 16.dp))
        Text(s[R.string.reg_title], color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(s[R.string.reg_subtitle], color = Muted, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
        Field(user, s[R.string.login_label]) { user = it }
        Spacer(Modifier.height(10.dp))
        Field(pass, s[R.string.login_password], true) { pass = it }
        err?.let { Text(it, color = Color(0xFFF87171), modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Purple)
                .clickable(enabled = !busy) {
                    busy = true
                    err = null
                    scope.launch {
                        val failed = vm.signUp(user, pass)
                        busy = false
                        if (failed == null) onDone(user.trim(), pass)
                        else err = failed.ifBlank { s[R.string.login_no_network] }
                    }
                }.padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (busy) s[R.string.reg_busy] else s[R.string.reg_action],
                color = Color.White, fontWeight = FontWeight.SemiBold
            )
        }
    }
}
