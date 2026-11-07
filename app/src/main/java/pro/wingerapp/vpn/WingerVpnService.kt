package pro.wingerapp.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import go.Seq
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface as JavaNetworkInterface

class WingerVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var singServer: CommandServer? = null
    private val generation = java.util.concurrent.atomic.AtomicInteger()
    private var ifaceListener: InterfaceUpdateListener? = null

    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { VpnLog.add("CRASH ${t.name}: ${e.javaClass.simpleName}: ${e.message}") }
            prev?.uncaughtException(t, e)
        }
    }

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
            } catch (e: Throwable) {
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
        VpnLog.add("command server started")
        try {
            server.startOrReloadService(sbConfig, OverrideOptions())
        } catch (e: Throwable) {
            VpnLog.add("startOrReload ошибка: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        if (generation.get() != gen) return
        Store.running(this, true)
        VpnLog.add("ядро запущено")
        Thread {
            for (i in 1..8) {
                Thread.sleep(1000)
                if (generation.get() != gen) return@Thread
                VpnLog.add("alive ${i}s")
            }
        }.start()
    }

    private fun stopCore() {
        runCatching { singServer?.closeService() }
        runCatching { singServer?.close() }
        singServer = null
        tun?.close()
        tun = null
    }

    private fun openTun(): Int {
        VpnLog.add("openTun вызван")
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
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
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

    private fun validPrefix(host: String, prefix: Short): String? {
        val h = host.substringBefore('%')
        if (h.isBlank() || prefix !in 0..128) return null
        return runCatching {
            val addr = InetAddress.getByName(h)
            val bits = if (addr is java.net.Inet4Address) 32 else 128
            if (prefix > bits) return null
            "$h/$prefix"
        }.getOrNull()
    }

    private fun collectInterfaces(): List<io.nekohasekai.libbox.NetworkInterface> {
        val cm = getSystemService(ConnectivityManager::class.java)
        val javaIfs = runCatching { JavaNetworkInterface.getNetworkInterfaces().toList() }.getOrNull().orEmpty()
        val result = mutableListOf<io.nekohasekai.libbox.NetworkInterface>()
        for (network in cm.allNetworks) {
            val lp = cm.getLinkProperties(network) ?: continue
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val name = lp.interfaceName ?: continue
            val jni = javaIfs.find { it.name == name }
            val box = io.nekohasekai.libbox.NetworkInterface()
            box.name = name
            box.index = jni?.index ?: (result.size + 1)
            box.mtu = runCatching { jni?.mtu }.getOrNull() ?: 1500
            val addrs = jni?.interfaceAddresses?.mapNotNull { ia ->
                val host = ia.address.hostAddress ?: return@mapNotNull null
                validPrefix(host, ia.networkPrefixLength)
            }.orEmpty()
            box.addresses = StrIter(addrs)
            box.dnsServer = StrIter(lp.dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') })
            box.gateway = StrIter(emptyList())
            box.type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                else -> Libbox.InterfaceTypeOther
            }
            var flags = 0
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) flags = flags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            box.flags = flags
            box.metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            result.add(box)
            VpnLog.add("iface $name idx=${box.index} addrs=${addrs.size}")
        }
        return result
    }

    private fun notifyDefault() {
        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java)
            val network = cm.activeNetwork ?: return
            val lp = cm.getLinkProperties(network) ?: return
            val name = lp.interfaceName ?: return
            val jni = runCatching { JavaNetworkInterface.getByName(name) }.getOrNull()
            val idx = jni?.index ?: 1
            ifaceListener?.updateDefaultInterface(name, idx, false, false)
        }
    }

    private inner class BoxPlatform : PlatformInterface {
        override fun openTun(options: TunOptions): Int = runCatching { openTun() }.getOrElse { -1 }
        override fun autoDetectInterfaceControl(fd: Int) { runCatching { protect(fd) } }
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
        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) { ifaceListener = null }
        override fun closeNeighborMonitor(listener: io.nekohasekai.libbox.NeighborUpdateListener) {}
        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
            ifaceListener = listener
            notifyDefault()
        }
        override fun startNeighborMonitor(listener: io.nekohasekai.libbox.NeighborUpdateListener) {}
        override fun sendNotification(notification: io.nekohasekai.libbox.Notification) {}
        override fun readWIFIState(): WIFIState = WIFIState("", "")
        override fun readSystemSSHHostKey(): String = ""
        override fun lookupSFTPServer(): String = ""
        override fun lookupUser(name: String): io.nekohasekai.libbox.PlatformUser? = null
        override fun createBridge(options: io.nekohasekai.libbox.BridgeOptions): io.nekohasekai.libbox.BridgeSession =
            throw UnsupportedOperationException("bridge")
        override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String, sourcePort: Int, destinationAddress: String, destinationPort: Int): io.nekohasekai.libbox.ConnectionOwner? = null
        override fun openShellSession(user: io.nekohasekai.libbox.PlatformUser, path: String, args: StringIterator, dir: String, uid: Int, gid: Int): io.nekohasekai.libbox.ShellSession =
            throw UnsupportedOperationException("shell")
        override fun getInterfaces(): NetworkInterfaceIterator {
            val list = runCatching { collectInterfaces() }.getOrElse { emptyList() }
            VpnLog.add("interfaces: ${list.size}")
            return object : NetworkInterfaceIterator {
                private var i = 0
                override fun hasNext(): Boolean = i < list.size
                override fun next(): io.nekohasekai.libbox.NetworkInterface = if (i < list.size) list[i++] else io.nekohasekai.libbox.NetworkInterface()
            }
        }
        override fun localDNSTransport(): LocalDNSTransport = object : LocalDNSTransport {
            override fun raw(): Boolean = false
            override fun exchange(ctx: ExchangeContext, message: ByteArray) { runCatching { ctx.errorCode(1) } }
            override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
                runCatching {
                    val host = domain.trim().trimEnd('.')
                    val ip = InetAddress.getAllByName(host).firstOrNull()?.hostAddress
                    if (ip.isNullOrBlank()) ctx.errorCode(1) else ctx.success(ip)
                }
            }
        }
    }

    private inner class BoxHandler : CommandServerHandler {
        override fun writeDebugMessage(message: String?) {
            // completely swallow to avoid native panics in TUN mode
        }
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

    private class StrIter(private val items: List<String>) : StringIterator {
        private var i = 0
        override fun hasNext(): Boolean = i < items.size
        override fun next(): String = if (i < items.size) items[i++] else ""
        override fun len(): Int = items.size
    }

    companion object { const val EXTRA_CONFIG = "config" }
}
