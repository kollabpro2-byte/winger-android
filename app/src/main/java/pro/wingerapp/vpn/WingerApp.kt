package pro.wingerapp.vpn

import android.app.Application

class WingerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        VpnLog.init(this)
        CrashGuard.install()
        VpnLog.add("process start pid=${android.os.Process.myPid()} v=${BuildConfig.VERSION_NAME}")
        Diag.harvestCrash(this)
        Diag.harvestCoreLog(this, rotate = true)
        Diag.logExitReasons(this)
    }
}
