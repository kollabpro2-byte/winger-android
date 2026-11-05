package pro.wingerapp.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File

class WingerVpnService : VpnService(), CoreCallbackHandler {
    private var tun: ParcelFileDescriptor? = null
    private var core: CoreController? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        VpnLog.init(this)
        if (intent?.action == "STOP") { Store.running(this, false); stopCore(); stopSelf(); return START_NOT_STICKY }
        startForeground(1, note())
        val config = intent?.getStringExtra(EXTRA_CONFIG)
        if (config.isNullOrBlank()) { VpnLog.add("нет конфига"); return stop() }
        Thread {
            try {
                VpnLog.add("старт Xray, конфиг ${config.length} байт")
                val fd = openTun()
                prepareAssets()
                Libv2ray.initCoreEnv(filesDir.absolutePath, "")
                val ctl = Libv2ray.newCoreController(this)
                core = ctl
                val alive = java.util.concurrent.atomic.AtomicBoolean(true)
                Thread {
                    while (alive.get()) {
                        if (ctl.isRunning) {
                            val raw = runCatching { ctl.queryAllOutboundTrafficStats() }.getOrNull().orEmpty()
                            val (down, up) = parseCoreTraffic(raw)
                            if (down + up > 0) Store.coreTraffic(this, down, up)
                        }
                        Thread.sleep(1000)
                    }
                }.start()
                ctl.startLoop(config, fd)
                alive.set(false)
                Store.running(this, true)
                VpnLog.add("ядро запущено")
            } catch (e: Exception) {
                VpnLog.add("ошибка старта: ${e.javaClass.simpleName}: ${e.message}")
                stopCore()
            }
        }.start()
        return START_STICKY
    }

    override fun onRevoke() {
        Store.running(this, false)
        VpnLog.add("систему забрал другой VPN")
        stopCore()
        stopSelf()
    }
    override fun onDestroy() { stopCore(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
    override fun startup(): Long = 0
    override fun shutdown(): Long = 0
    override fun onEmitStatus(code: Long, msg: String?): Long { VpnLog.add("xray: $msg"); return 0 }

    private fun stop(): Int { stopSelf(); return START_NOT_STICKY }
    private fun stopCore() { runCatching { core?.stopLoop() }; tun?.close(); tun = null }

    private fun openTun(): Int {
        if (prepare(this) != null) error("нет разрешения VPN")
        val b = Builder().setSession("Winger").setMtu(1500)
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        b.addAddress("172.19.0.1", 30)
        b.addRoute("0.0.0.0", 0)
        b.addDnsServer(Store.dnsServer(this) ?: "1.1.1.1")
        b.addDisallowedApplication(packageName)
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.activeNetwork?.let { if (Build.VERSION.SDK_INT >= 22) b.setUnderlyingNetworks(arrayOf(it)) }
        tun?.close()
        tun = b.establish() ?: error("tun не поднялся")
        VpnLog.add("tun fd=${tun!!.fd}")
        return tun!!.fd
    }

    private fun prepareAssets() {
        val stamp = File(filesDir, "geo.version")
        val version = "3"
        val fresh = stamp.takeIf { it.exists() }?.readText() != version
        for (name in listOf("geoip.dat", "geosite.dat")) {
            val out = File(filesDir, name)
            if (out.exists() && !fresh) continue
            assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
            VpnLog.add("$name ${out.length()} байт")
        }
        stamp.writeText(version)
    }

    private fun note(): Notification {
        val ch = "winger"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "Winger", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, ch).setContentTitle("Winger VPN").setContentText("Канал активен")
            .setSmallIcon(android.R.drawable.ic_lock_lock).build()
    }

    companion object { const val EXTRA_CONFIG = "config" }
}
