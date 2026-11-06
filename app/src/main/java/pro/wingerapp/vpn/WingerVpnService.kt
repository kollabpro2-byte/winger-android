package pro.wingerapp.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import go.Seq
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import java.io.File
import java.net.InetAddress

class WingerVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var singServer: CommandServer? = null
    private val generation = java.util.concurrent.atomic.AtomicInteger()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        VpnLog.init(this)
        if (intent?.action == "STOP") { generation.incrementAndGet(); Store.running(this, false); stopCore(); stopSelf(); return START_NOT_STICKY }
        startForeground(1, note())
        val config = intent?.getStringExtra(EXTRA_CONFIG)
        if (config.isNullOrBlank()) { VpnLog.add("нет конфига"); return stop() }
        val gen = generation.incrementAndGet()
        stopCore()
        Thread {
            try {
                VpnLog.add("старт sing-box, конфиг ${config.length} байт")
                prepareAssets()
                if (generation.get() != gen) return@Thread
                startSingBox(config, gen)
            } catch (e: Exception) {
                if (generation.get() != gen) return@Thread
                VpnLog.add("ошибка старта: ${e.javaClass.simpleName}: ${e.message}")
                Store.running(this, false)
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

    private fun stop(): Int { stopSelf(); return START_NOT_STICKY }

    private fun startSingBox(config: String, gen: Int) {
        Seq.setContext(applicationContext)
        val opts = SetupOptions()
        opts.basePath = filesDir.absolutePath
        opts.workingPath = filesDir.absolutePath
        opts.tempPath = cacheDir.absolutePath
        opts.fixAndroidStack = true
        Libbox.setup(opts)
        val sbConfig = toSingBox(config)
        VpnLog.add("sing-box конфиг ${sbConfig.length} байт")
        val server = Libbox.newCommandServer(BoxHandler(), BoxPlatform())
        singServer = server
        server.start()
        server.startOrReloadService(sbConfig, OverrideOptions())
        if (generation.get() != gen) return
        Store.running(this, true)
        VpnLog.add("ядро запущено")
    }

    private fun stopCore() {
        runCatching { singServer?.closeService() }
        runCatching { singServer?.close() }
        singServer = null
        tun?.close()
        tun = null
    }

    private fun openTun(): Int {
        if (prepare(this) != null) error("нет разрешения VPN")
        val b = Builder().setSession("Winger").setMtu(1500)
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        b.addAddress("172.19.0.1", 30)
        b.addRoute("0.0.0.0", 0)
        b.addDnsServer(Store.dnsServer(this) ?: "1.1.1.1")
        b.addDisallowedApplication(packageName)
        val cm = getSystemService(ConnectivityManager::class.java)
        val physical = cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
        }
        if (physical != null) b.setUnderlyingNetworks(arrayOf(physical))
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

    private inner class BoxPlatform : PlatformInterface {
        override fun openTun(options: TunOptions): Int = openTun()
        override fun autoDetectInterfaceControl(fd: Int) { protect(fd) }
        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
        override fun includeAllNetworks(): Boolean = false
        override fun underNetworkExtension(): Boolean = false
        override fun usePlatformBridge(): Boolean = false
        override fun usePlatformShell(): Boolean = false
        override fun tailscaleHostname(): String = ""
        override fun clearDNSCache() {}
        override fun registerMyInterface(name: String) {}
        override fun cancelNotification(identifier: String, typeId: Int) {}
        override fun checkPlatformShell() {}
        override fun closeDefaultInterfaceMonitor(listener: io.nekohasekai.libbox.InterfaceUpdateListener) {}
        override fun closeNeighborMonitor(listener: io.nekohasekai.libbox.NeighborUpdateListener) {}
        override fun startDefaultInterfaceMonitor(listener: io.nekohasekai.libbox.InterfaceUpdateListener) {}
        override fun startNeighborMonitor(listener: io.nekohasekai.libbox.NeighborUpdateListener) {}
        override fun sendNotification(notification: io.nekohasekai.libbox.Notification) {}
        override fun readWIFIState(): io.nekohasekai.libbox.WIFIState? = null
        override fun readSystemSSHHostKey(): String = ""
        override fun lookupSFTPServer(): String = ""
        override fun lookupUser(name: String): io.nekohasekai.libbox.PlatformUser? = null
        override fun createBridge(options: io.nekohasekai.libbox.BridgeOptions): io.nekohasekai.libbox.BridgeSession =
            throw UnsupportedOperationException("bridge")
        override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String, sourcePort: Int, destinationAddress: String, destinationPort: Int): io.nekohasekai.libbox.ConnectionOwner? = null
        override fun openShellSession(user: io.nekohasekai.libbox.PlatformUser, path: String, args: io.nekohasekai.libbox.StringIterator, dir: String, uid: Int, gid: Int): io.nekohasekai.libbox.ShellSession =
            throw UnsupportedOperationException("shell")
        override fun getInterfaces(): NetworkInterfaceIterator = object : NetworkInterfaceIterator {
            override fun hasNext(): Boolean = false
            override fun next(): io.nekohasekai.libbox.NetworkInterface = throw NoSuchElementException()
        }
        override fun localDNSTransport(): LocalDNSTransport = object : LocalDNSTransport {
            override fun raw(): Boolean = false
            override fun exchange(ctx: ExchangeContext, message: ByteArray) { ctx.errorCode(1) }
            override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
                val host = domain.trim().trimEnd('.')
                val ip = runCatching { InetAddress.getAllByName(host).firstOrNull()?.hostAddress }.getOrNull()
                if (ip.isNullOrBlank()) ctx.errorCode(1) else ctx.success(ip)
            }
        }
    }

    private inner class BoxHandler : CommandServerHandler {
        override fun writeDebugMessage(message: String?) { if (!message.isNullOrBlank()) VpnLog.add("sing-box: $message") }
        override fun serviceReload() {}
        override fun serviceStop() { VpnLog.add("sing-box: service stop") }
        override fun setSystemProxyEnabled(enabled: Boolean) {}
        override fun connectSSHAgent(): Int = throw UnsupportedOperationException("ssh")
        override fun triggerNativeCrash() {}
        override fun getSystemProxyStatus(): SystemProxyStatus {
            val status = SystemProxyStatus()
            status.setAvailable(false)
            status.setEnabled(false)
            return status
        }
    }

    companion object { const val EXTRA_CONFIG = "config" }
}
