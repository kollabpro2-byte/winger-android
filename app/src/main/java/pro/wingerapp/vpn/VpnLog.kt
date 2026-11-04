package pro.wingerapp.vpn

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VpnLog {
    private lateinit var file: File
    fun init(ctx: Context) { file = File(ctx.filesDir, "vpn.log") }
    fun add(line: String) {
        if (!::file.isInitialized) return
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        file.appendText("$ts $line\n")
    }
    fun text(): String = if (::file.isInitialized && file.exists()) file.readText().takeLast(4000) else "лога нет"
    fun clear() { if (::file.isInitialized) file.writeText("") }
}
