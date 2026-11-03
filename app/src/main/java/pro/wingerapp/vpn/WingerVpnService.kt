package pro.wingerapp.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification as BoxNotification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.io.File

class WingerVpnService : VpnService(), PlatformInterface, CommandServerHandler {
    private var tun: ParcelFileDescriptor? = null
    private var server: CommandServer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, note())
        val link = intent?.getStringExtra(EXTRA_LINK) ?: return stop()
        Thread {
            try {
                Libbox.setup(SetupOptions().apply {
                    basePath = filesDir.absolutePath
                    workingPath = filesDir.absolutePath
                    tempPath = cacheDir.absolutePath
                })
                val cmd = CommandServer(this, this)
                cmd.start()
                server = cmd
                cmd.startOrReloadService(singBoxConfig(link), OverrideOptions())
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                stop()
            }
        }.start()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { server?.closeService() }
        runCatching { server?.close() }
        tun?.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun stop(): Int { stopSelf(); return START_NOT_STICKY }

    private fun note(): Notification {
        val ch = "winger"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "Winger", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, ch)
            .setContentTitle("Winger VPN")
            .setContentText("Канал активен")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
    }

    override fun usePlatformAutoDetectInterfaceControl() = true
    override fun autoDetectInterfaceControl(fd: Int) { protect(fd) }
    override fun underNetworkExtension() = false
    override fun includeAllNetworks() = false
    override fun usePlatformShell() = false
    override fun usePlatformBridge() = false
    override fun usePlatformAutoRedirect() = false
    override fun clearDNSCache() {}
    override fun readWIFIState(): WIFIState? = null
    override fun localDNSTransport() = null
    override fun getInterfaces(): NetworkInterfaceIterator? = null
    override fun tailscaleHostname() = ""
    override fun lookupSFTPServer() = ""
    override fun readSystemSSHHostKey() = ""

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("нет разрешения VPN")
        val b = Builder().setSession("Winger").setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        val v4 = options.inet4Address
        while (v4.hasNext()) {
            val a = v4.next()
            b.addAddress(a.address(), a.prefix())
        }
        if (!options.inet4Address.hasNext()) b.addAddress("172.19.0.1", 30)
        b.addRoute("0.0.0.0", 0)
        b.addDnsServer("1.1.1.1")
        b.addDisallowedApplication(packageName)
        tun?.close()
        tun = b.establish() ?: error("tun не поднялся")
        return tun!!.fd
    }

    override fun serviceReload() {}
    override fun serviceStop() { stopSelf() }
    override fun getSystemProxyStatus() = null
    override fun setSystemProxyEnabled(enabled: Boolean) {}
    override fun writeDebugMessage(message: String?) { Log.d(TAG, message.orEmpty()) }

    override fun sendNotification(notification: BoxNotification?) {}
    override fun cancelNotification(identifier: String?, typeID: Int) {}
    override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String, sourcePort: Int, destinationAddress: String, destinationPort: Int) =
        throw UnsupportedOperationException()
    override fun startDefaultInterfaceMonitor(listener: io.nekohasekai.libbox.InterfaceUpdateListener?) {}
    override fun closeDefaultInterfaceMonitor(listener: io.nekohasekai.libbox.InterfaceUpdateListener?) {}
    override fun startNeighborMonitor(listener: io.nekohasekai.libbox.NeighborUpdateListener?) {}
    override fun closeNeighborMonitor(listener: io.nekohasekai.libbox.NeighborUpdateListener?) {}
    override fun registerMyInterface(name: String?) {}
    override fun checkPlatformShell() {}
    override fun openShellSession(user: io.nekohasekai.libbox.PlatformUser?, command: String?, environ: StringIterator?, term: String?, rows: Int, cols: Int) =
        throw UnsupportedOperationException()
    override fun lookupUser(username: String?) = throw UnsupportedOperationException()
    override fun createBridge(options: io.nekohasekai.libbox.BridgeOptions?) = throw UnsupportedOperationException()
    override fun createAutoRedirect(options: ByteArray?, handler: io.nekohasekai.libbox.AutoRedirectHandler?) =
        throw UnsupportedOperationException()

    companion object {
        const val EXTRA_LINK = "link"
        private const val TAG = "WingerVPN"
    }
}
