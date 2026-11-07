package pro.wingerapp.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.OsConstants
import go.Seq
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
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
import java.net.InetSocketAddress
import java.net.NetworkInterface as JavaNetworkInterface

class WingerVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var singServer: CommandServer? = null
    private val generation = java.util.concurrent.atomic.AtomicInteger()
    private var ifaceListener: InterfaceUpdateListener? = null
    private val rate = LogRate()

    override fun onCreate() {
        super.onCreate()
        VpnLog.init(this)
        CrashGuard.install()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        VpnLog.init(this)
        CrashGuard.install()
        Diag.harvestCrash(this)
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
                VpnLog.add("ошибка старта: ${err(e)}\n${e.stackTraceToString().take(1500)}")
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
        Diag.harvestCrash(this)
        Diag.harvestCoreLog(this, rotate = true)
        val opts = SetupOptions()
        opts.basePath = filesDir.absolutePath
        opts.workingPath = filesDir.absolutePath
        opts.tempPath = cacheDir.absolutePath
        opts.fixAndroidStack = true
        opts.crashReportSource = "winger"
        opts.appVersion = BuildConfig.VERSION_NAME
        opts.debug = false
        val ver = runCatching { Libbox.version() }.getOrElse { "?" }
        VpnLog.add("libbox $ver redirectStderr: нет в Java API, Setup пишет CrashReport-winger.log, debug=false")
        VpnLog.add("sing-box file log ${if (CORE_FILE_LOG) "warn -> singbox.log" else "выключен (CORE_FILE_LOG=false)"}")
        Libbox.setup(opts)
        val logFile = File(filesDir, "singbox.log").absolutePath
        val sbConfig = toSingBox(config, logFile, Store.dnsServer(this) ?: "1.1.1.1")
        VpnLog.add(summarizeSingBox(sbConfig))
        VpnLog.add("sing-box конфиг ${sbConfig.length} байт")
        val server = Libbox.newCommandServer(BoxHandler(), BoxPlatform())
        singServer = server
        server.start()
        VpnLog.add("command server started")
        // Watchdog: if openTun not reached in 12s, dump all thread stacks
        val watchdog = Thread {
            try {
                Thread.sleep(12_000)
                if (generation.get() == gen && !coreRunning) {
                    VpnLog.add("WATCHDOG: openTun не достигнут за 12с")
                    Thread.getAllStackTraces().forEach { (t, st) ->
                        VpnLog.add("  thread ${t.name} (${t.state}): ${st.take(8).joinToString(" | ") { it.toString().substringAfterLast('.') }}")
                    }
                }
            } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; name = "vpn-watchdog"; start() }
        try {
            VpnLog.add("вызов startOrReloadService...")
            server.startOrReloadService(sbConfig, OverrideOptions())
            VpnLog.add("startOrReloadService вернулся")
        } catch (e: Throwable) {
            VpnLog.add("startOrReload ошибка: ${err(e)}\n${e.stackTraceToString().take(1500)}")
            throw e
        } finally {
            watchdog.interrupt()
        }
        if (generation.get() != gen) return
        Store.running(this, true)
        coreRunning = true
        VpnLog.add("ядро запущено")
        startHeartbeat(gen)
    }

    private fun startHeartbeat(gen: Int) {
        Thread {
            val start = SystemClock.elapsedRealtime()
            var delay = 3_000L
            while (generation.get() == gen) {
                try {
                    Thread.sleep(delay)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (generation.get() != gen) return@Thread
                val sec = ((SystemClock.elapsedRealtime() - start) / 1000).toInt()
                val heap = Debug.getNativeHeapAllocatedSize()
                val threads = Thread.activeCount()
                val traffic = runCatching { tunTraffic() }.getOrNull()
                val rx = traffic?.first?.toString() ?: "?"
                val tx = traffic?.second?.toString() ?: "?"
                VpnLog.add("alive ${sec}s heap=$heap threads=$threads rx=$rx tx=$tx")
                delay = if (sec < 60) 3_000L else 15_000L
            }
        }.apply {
            name = "vpn-heartbeat"
            isDaemon = true
        }.start()
    }

    private fun stopCore() {
        coreRunning = false
        runCatching { singServer?.closeService() }.onFailure { VpnLog.add("closeService: ${err(it)}") }
        runCatching { singServer?.close() }.onFailure { VpnLog.add("server.close: ${err(it)}") }
        singServer = null
        runCatching { tun?.close() }.onFailure { VpnLog.add("tun.close: ${err(it)}") }
        tun = null
    }

    private fun openTun(@Suppress("UNUSED_PARAMETER") options: TunOptions): Int {
        VpnLog.add("openTun start")
        val prep = prepare(this)
        if (prep != null) {
            VpnLog.add("openTun: нет разрешения VPN, prepare вернул intent")
            error("нет разрешения VPN")
        }
        val mtu = 1500
        VpnLog.add("openTun mtu=$mtu")
        val b = Builder().setSession("Winger").setMtu(mtu)
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        b.addAddress("172.19.0.1", 30)
        VpnLog.add("openTun address 172.19.0.1/30")
        b.addRoute("0.0.0.0", 0)
        VpnLog.add("openTun route 0.0.0.0/0")
        b.addDnsServer("172.19.0.2")
        VpnLog.add("openTun dns 172.19.0.2")
        try {
            b.addDisallowedApplication(packageName)
            VpnLog.add("openTun exclude $packageName")
        } catch (e: Throwable) {
            VpnLog.add("openTun exclude fail: ${err(e)}")
        }
        val cm = getSystemService(ConnectivityManager::class.java)
        val physical = cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        if (physical != null) {
            b.setUnderlyingNetworks(arrayOf(physical))
            VpnLog.add("openTun underlying set")
        } else {
            VpnLog.add("openTun: нет physical network")
        }
        val pfd = try {
            b.establish()
        } catch (e: Throwable) {
            VpnLog.add("openTun establish exception: ${err(e)}")
            null
        }
        if (pfd == null) {
            VpnLog.add("openTun establish вернул null")
            error("tun не поднялся (establish null)")
        }
        val fd = pfd.fd
        if (fd < 0) {
            VpnLog.add("openTun bad fd=$fd")
            pfd.close()
            error("tun bad fd=$fd")
        }
        tun?.close()
        tun = pfd
        VpnLog.add("tun fd=$fd held mtu=$mtu")
        return fd
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
        return try {
            val addr = InetAddress.getByName(h)
            val bits = if (addr is java.net.Inet4Address) 32 else 128
            if (prefix > bits) return null
            "$h/$prefix"
        } catch (e: Throwable) {
            if (rate.allow("prefix", 10_000)) VpnLog.add("prefix $h/$prefix: ${err(e)}")
            null
        }
    }

    private fun collectInterfaces(): List<io.nekohasekai.libbox.NetworkInterface> {
        val cm = getSystemService(ConnectivityManager::class.java)
        val javaIfs = try {
            JavaNetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: Throwable) {
            VpnLog.add("java ifaces: ${err(e)}")
            emptyList()
        }
        val result = mutableListOf<io.nekohasekai.libbox.NetworkInterface>()
        for (network in cm.allNetworks) {
            val lp = cm.getLinkProperties(network) ?: continue
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val name = lp.interfaceName ?: continue
            val jni = javaIfs.find { it.name == name }
            if (jni == null) {
                if (rate.allow("iface-skip-$name", 8_000)) VpnLog.add("iface $name пропущен: нет java index")
                continue
            }
            val box = io.nekohasekai.libbox.NetworkInterface()
            box.name = name
            box.index = jni.index
            box.mtu = try {
                jni.mtu
            } catch (e: Throwable) {
                if (rate.allow("mtu-$name", 10_000)) VpnLog.add("mtu $name: ${err(e)}")
                1500
            }
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
            if (rate.allow("iface-$name", 8_000)) VpnLog.add("iface $name idx=${box.index} addrs=${addrs.size} type=${box.type}")
        }
        return result
    }

    private fun notifyDefault() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val network = cm.activeNetwork
            if (network == null) {
                if (rate.allow("def-none", 5_000)) VpnLog.add("notifyDefault: нет activeNetwork")
                return
            }
            val lp = cm.getLinkProperties(network)
            if (lp == null) {
                VpnLog.add("notifyDefault: нет linkProperties")
                return
            }
            val name = lp.interfaceName
            if (name.isNullOrBlank()) {
                VpnLog.add("notifyDefault: нет interfaceName")
                return
            }
            val jni = try {
                JavaNetworkInterface.getByName(name)
            } catch (e: Throwable) {
                VpnLog.add("notifyDefault getByName($name): ${err(e)}")
                null
            }
            val idx = jni?.index ?: 1
            val listener = ifaceListener
            if (listener == null) {
                VpnLog.add("notifyDefault: нет listener ($name idx=$idx)")
                return
            }
            listener.updateDefaultInterface(name, idx, false, false)
            if (rate.allow("def-ok", 5_000)) VpnLog.add("notifyDefault $name idx=$idx")
        } catch (e: Throwable) {
            VpnLog.add("notifyDefault FAIL: ${err(e)}")
        }
    }

    private fun err(t: Throwable) = "${t.javaClass.simpleName}: ${t.message}"

    private inner class BoxPlatform : PlatformInterface {
        override fun openTun(options: TunOptions): Int {
            return try {
                val fd = openTun(options)
                VpnLog.add("openTun ok fd=$fd")
                fd
            } catch (e: Throwable) {
                VpnLog.add("openTun FAIL возвращаю -1: ${err(e)}\n${e.stackTraceToString().take(800)}")
                -1
            }
        }
        override fun autoDetectInterfaceControl(fd: Int) {
            try {
                val ok = protect(fd)
                if (!ok) VpnLog.add("protect($fd)=false")
                else if (rate.allow("protect-ok", 5_000)) VpnLog.add("protect($fd)=true")
            } catch (e: Throwable) {
                VpnLog.add("protect($fd) exception: ${err(e)}")
            }
        }
        override fun usePlatformAutoDetectInterfaceControl(): Boolean {
            if (rate.allow("cap-protect", 60_000)) VpnLog.add("usePlatformAutoDetectInterfaceControl=true")
            return true
        }
        override fun includeAllNetworks(): Boolean {
            if (rate.allow("cap-all", 60_000)) VpnLog.add("includeAllNetworks=false")
            return false
        }
        override fun underNetworkExtension(): Boolean {
            if (rate.allow("cap-ext", 60_000)) VpnLog.add("underNetworkExtension=false")
            return false
        }
        override fun usePlatformBridge(): Boolean {
            if (rate.allow("cap-bridge", 60_000)) VpnLog.add("usePlatformBridge=false")
            return false
        }
        override fun usePlatformShell(): Boolean {
            if (rate.allow("cap-shell", 60_000)) VpnLog.add("usePlatformShell=false")
            return false
        }
        override fun tailscaleHostname(): String = ""
        override fun clearDNSCache() { VpnLog.add("clearDNSCache") }
        override fun registerMyInterface(name: String) { VpnLog.add("registerMyInterface $name") }
        override fun cancelNotification(identifier: String, typeId: Int) {
            VpnLog.add("cancelNotification $identifier type=$typeId")
        }
        override fun checkPlatformShell() { VpnLog.add("checkPlatformShell") }
        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
            VpnLog.add("closeDefaultInterfaceMonitor")
            ifaceListener = null
        }
        override fun closeNeighborMonitor(listener: io.nekohasekai.libbox.NeighborUpdateListener) {
            if (rate.allow("neigh-close", 30_000)) VpnLog.add("closeNeighborMonitor")
        }
        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
            VpnLog.add("startDefaultInterfaceMonitor")
            ifaceListener = listener
            try {
                notifyDefault()
            } catch (e: Throwable) {
                VpnLog.add("startDefaultInterfaceMonitor notify: ${err(e)}")
            }
        }
        override fun startNeighborMonitor(listener: io.nekohasekai.libbox.NeighborUpdateListener) {
            if (rate.allow("neigh-start", 30_000)) VpnLog.add("startNeighborMonitor")
        }
        override fun sendNotification(notification: io.nekohasekai.libbox.Notification) {
            val title = notification?.title?.take(80) ?: "-"
            VpnLog.add("sendNotification $title")
        }
        override fun readWIFIState(): WIFIState {
            if (rate.allow("wifi", 30_000)) VpnLog.add("readWIFIState empty")
            return WIFIState("", "")
        }
        override fun readSystemSSHHostKey(): String = ""
        override fun lookupSFTPServer(): String = ""
        override fun lookupUser(name: String): io.nekohasekai.libbox.PlatformUser? {
            if (rate.allow("user", 30_000)) VpnLog.add("lookupUser $name не найден")
            throw UnsupportedOperationException("lookupUser")
        }
        override fun createBridge(options: io.nekohasekai.libbox.BridgeOptions): io.nekohasekai.libbox.BridgeSession {
            VpnLog.add("createBridge не поддерживается")
            throw UnsupportedOperationException("bridge")
        }
        override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String, sourcePort: Int, destinationAddress: String, destinationPort: Int): ConnectionOwner {
            // A null return is a nil deref inside libbox. SFA throws when the owner is unknown.
            try {
                val owner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val uid = getSystemService(ConnectivityManager::class.java).getConnectionOwnerUid(
                        ipProtocol,
                        InetSocketAddress(sourceAddress, sourcePort),
                        InetSocketAddress(destinationAddress, destinationPort),
                    )
                    if (uid == Process.INVALID_UID) error("connection owner not found")
                    ConnectionOwner().also { it.userId = uid }
                } else {
                    Libbox.findConnectionOwner(ipProtocol, sourceAddress, sourcePort, destinationAddress, destinationPort)
                }
                val packages = packageManager.getPackagesForUid(owner.userId)
                owner.userName = packages?.firstOrNull().orEmpty()
                owner.setAndroidPackageNames(StrIter(packages?.toList() ?: emptyList()))
                if (rate.allow("owner-ok", 5_000)) VpnLog.add("findConnectionOwner uid=${owner.userId} pkg=${owner.userName}")
                return owner
            } catch (e: Throwable) {
                if (rate.allow("owner-miss", 3_000)) {
                    VpnLog.add("findConnectionOwner miss $ipProtocol $sourceAddress:$sourcePort->$destinationAddress:$destinationPort: ${err(e)}")
                }
                throw e
            }
        }
        override fun openShellSession(user: io.nekohasekai.libbox.PlatformUser, path: String, args: StringIterator, dir: String, uid: Int, gid: Int): io.nekohasekai.libbox.ShellSession {
            VpnLog.add("openShellSession не поддерживается")
            throw UnsupportedOperationException("shell")
        }
        override fun getInterfaces(): NetworkInterfaceIterator {
            val list = try {
                collectInterfaces()
            } catch (e: Throwable) {
                VpnLog.add("getInterfaces FAIL: ${err(e)}\n${e.stackTraceToString().take(800)}")
                emptyList()
            }
            if (list.isEmpty() || rate.allow("ifaces", 5_000)) VpnLog.add("interfaces: ${list.size}")
            return object : NetworkInterfaceIterator {
                private var i = 0
                override fun hasNext(): Boolean = i < list.size
                override fun next(): io.nekohasekai.libbox.NetworkInterface = if (i < list.size) list[i++] else io.nekohasekai.libbox.NetworkInterface()
            }
        }
        override fun localDNSTransport(): LocalDNSTransport = object : LocalDNSTransport {
            override fun raw(): Boolean = false
            override fun exchange(ctx: ExchangeContext, message: ByteArray) {
                try {
                    ctx.errorCode(1)
                    if (rate.allow("dns-ex", 3_000)) VpnLog.add("dns exchange -> error 1 (${message.size} байт)")
                } catch (e: Throwable) {
                    if (rate.allow("dns-ex-err", 3_000)) VpnLog.add("dns exchange FAIL: ${err(e)}")
                }
            }
            override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
                try {
                    val host = domain.trim().trimEnd('.')
                    val ip = InetAddress.getAllByName(host).firstOrNull()?.hostAddress
                    if (ip.isNullOrBlank()) {
                        ctx.errorCode(1)
                        if (rate.allow("dns-miss", 3_000)) VpnLog.add("dns lookup miss $network $host")
                    } else ctx.success(ip)
                } catch (e: Throwable) {
                    if (rate.allow("dns-lookup", 3_000)) VpnLog.add("dns lookup FAIL $network $domain: ${err(e)}")
                }
            }
        }
    }

    private inner class BoxHandler : CommandServerHandler {
        override fun writeDebugMessage(message: String?) {
            // swallow: Java callback from Go panicked in TUN mode. debug stays false so this is not called.
        }
        override fun serviceReload() { VpnLog.add("sing-box: serviceReload") }
        override fun serviceStop() { VpnLog.add("sing-box: serviceStop") }
        override fun setSystemProxyEnabled(enabled: Boolean) { VpnLog.add("setSystemProxyEnabled=$enabled") }
        override fun connectSSHAgent(): Int {
            VpnLog.add("connectSSHAgent не поддерживается")
            throw UnsupportedOperationException("ssh")
        }
        override fun triggerNativeCrash() { VpnLog.add("triggerNativeCrash вызван") }
        override fun getSystemProxyStatus(): SystemProxyStatus {
            if (rate.allow("proxy-status", 30_000)) VpnLog.add("getSystemProxyStatus available=false enabled=false")
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

    companion object {
        const val EXTRA_CONFIG = "config"
        @Volatile var coreRunning: Boolean = false
    }
}
