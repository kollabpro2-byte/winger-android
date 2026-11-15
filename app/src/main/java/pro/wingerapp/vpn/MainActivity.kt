package pro.wingerapp.vpn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import pro.wingerapp.vpn.net.Api
import pro.wingerapp.vpn.ui.HomeScreen
import pro.wingerapp.vpn.ui.LoginScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var pendingConfig: String? = null
    private val vpnPerm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cfg = pendingConfig ?: return@registerForActivityResult
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            VpnLog.add("VPN-разрешение отклонено")
            TunnelState.fail("Разрешение VPN не выдано")
            return@registerForActivityResult
        }
        startForegroundService(Intent(this, WingerVpnService::class.java).putExtra(WingerVpnService.EXTRA_CONFIG, cfg))
    }
    fun connect(config: String) {
        pendingConfig = config
        TunnelState.clearError()
        val prep = VpnService.prepare(this)
        if (prep != null) vpnPerm.launch(prep)
        else startForegroundService(Intent(this, WingerVpnService::class.java).putExtra(WingerVpnService.EXTRA_CONFIG, config))
    }
    fun disconnect() {
        Store.running(this, false)
        Store.clearLastConfig(this)
        TunnelState.off()
        // Штатный путь: сервис сам закрывает ядро, снимает уведомление и
        // отпускает tun. Если погасить сервис сразу же (stopService подряд),
        // ACTION_STOP не успевал обработаться, и на HyperOS ядро иногда
        // оставалось жить вместе со своим tun — отсюда висящий значок VPN.
        val stop = Intent(this, WingerVpnService::class.java).setAction(WingerVpnService.ACTION_STOP)
        runCatching { startService(stop) }
            .onFailure { VpnLog.add("disconnect: startService(STOP) не сработал: ${it.message}") }
        // Контрольный выстрел: если сервис всё ещё существует через 1.2 с,
        // гасим принудительно — onDestroy закроет ядро и tun.
        Handler(Looper.getMainLooper()).postDelayed({
            val stopped = runCatching { stopService(Intent(this, WingerVpnService::class.java)) }
            VpnLog.add("disconnect: контрольная остановка через 1.2 с, сработала=${stopped.getOrNull()}")
        }, 1200)
        // Никакого автоматического закрытия tun из активити: список дескрипторов,
        // прочитанный «потом», может содержать уже живой tun новой сессии, и тогда
        // закроется рабочий туннель (или система останется с VPN-маршрутами без
        // интерфейса — и интернета не будет вовсе). Дескрипторы освобождает сам
        // сервис, когда его сессия уже остановлена.
        VpnLog.add("disconnect: остановка запрошена")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VpnLog.init(this)
        CrashGuard.install()
        Diag.harvestCrash(this)
        Diag.harvestCoreLog(this, rotate = true)
        Diag.logExitReasons(this)
        VpnLog.add("app start")
        pro.wingerapp.vpn.net.Api.init(this)
        TunnelState.sync(this)
        // Тема из настроек до первой композиции: иначе экран на мгновение
        // отрисуется в средней теме и «перескочит».
        pro.wingerapp.vpn.ui.AppTheme.mode =
            pro.wingerapp.vpn.ui.AppTheme.fromStored(Store.themeMode(this))
        VpnLog.setPeriodMinutes(Store.logClearMinutes(this))
        setContent {
            // Системные полосы подстраиваются под тему: в светлой нужны тёмные
            // иконки и светлый фон, иначе часы и значки вверху не видны.
            SideEffect { applySystemBars() }
            AppRoot(vm, this, hwid(), ::connect, ::disconnect)
        }
    }

    /** Статус-бар и навигация в цвет темы (Compose сам их не перекрашивает). */
    private fun applySystemBars() {
        val palette = pro.wingerapp.vpn.ui.AppTheme.p
        val bg = if (palette.isLight) 0xFFCBD2DB.toInt() else 0xFF040D1A.toInt()
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = bg
            window.navigationBarColor = bg
        }
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = palette.isLight
        controller.isAppearanceLightNavigationBars = palette.isLight
    }

    /**
     * ANDROID_ID is stable per signing key, but it can be empty on some devices.
     * A shared constant would give every such device the same HWID (and the same
     * trial), so fall back to a hash of the build fingerprint instead.
     */
    private fun hwid(): String {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        if (!id.isNullOrBlank() && id.length >= 8) return id
        val seed = "${Build.MANUFACTURER}|${Build.MODEL}|${Build.FINGERPRINT}"
        return pro.wingerapp.vpn.net.Api.sha256(seed).take(32)
    }
}
@Composable
fun AppRoot(
    vm: AppViewModel,
    ctx: Context,
    hwid: String,
    connect: (String) -> Unit,
    disconnect: () -> Unit,
) {
    val signedIn by vm.signedIn.collectAsState()
    // Registration and the subscription both go out with the device id, and the
    // login screen runs before the home screen ever starts loading.
    LaunchedEffect(hwid) { vm.attachDevice(hwid) }
    if (!signedIn) {
        LoginScreen(vm, ctx, hwid, disconnect) { vm.signedIn() }
    } else {
        HomeScreen(vm, hwid, connect, disconnect, onLogout = {
            disconnect()
            TunnelState.clearError()
        })
    }
}
