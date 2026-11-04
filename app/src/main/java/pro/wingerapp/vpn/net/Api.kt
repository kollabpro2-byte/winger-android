package pro.wingerapp.vpn.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.json.JSONObject
import pro.wingerapp.vpn.Store
import java.net.CookieManager
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

object Api {
    const val BASE = "https://my.wingerapp.pro/api/v1"
    val cookies = CookieManager()
    lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (cookies.cookieStore.cookies.isNotEmpty()) return
        Store.cookies(app).lineSequence().filter { it.contains("|") }.forEach { line ->
            val p = line.split("|", limit = 3)
            if (p.size < 3) return@forEach
            val c = HttpCookie(p[1], p[2]).apply { domain = p[0]; path = "/" }
            cookies.cookieStore.add(URI("https://${p[0]}/"), c)
        }
    }

    fun sha256(text: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    fun login(username: String, password: String): String? {
        val body = JSONObject()
            .put("username", username.trim())
            .put("passwordHash", sha256(password))
        val (code, text) = post("$BASE/auth/login", body.toString())
        if (code != 200) {
            return runCatching { JSONObject(text).optString("message") }.getOrNull()
                ?.ifBlank { null } ?: "Неверный логин или пароль ($code)"
        }
        persist()
        return null
    }

    fun subscription(): Sub? {
        val (code, text) = get("$BASE/subscription")
        if (code != 200 || text.isBlank() || text == "null") return null
        val o = JSONObject(text)
        return Sub(
            name = o.optJSONObject("plan")?.optString("name") ?: "Подписка",
            expires = o.optString("expiresAt", ""),
            url = o.optString("url", ""),
            devices = o.optInt("deviceLimit", 0)
        )
    }

    fun fetchConfig(url: String, hwid: String): String = getSub(url, hwid, "sfa/1.11.0 Winger/1.0")
    fun fetchHapp(url: String, hwid: String): String = getSub(url, hwid, "Happ/4.8.0/Android")

    private fun getSub(url: String, hwid: String, ua: String): String {
        val c = open(url).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", ua)
            setRequestProperty("x-hwid", hwid)
            setRequestProperty("x-device-os", "Android")
            connectTimeout = 15000
            readTimeout = 20000
        }
        return c.inputStream.bufferedReader().readText()
    }

    private fun post(url: String, body: String) = call("POST", url, body)
    private fun get(url: String) = call("GET", url, null)

    private fun call(method: String, url: String, body: String?): Pair<Int, String> {
        val c = open(url).apply {
            requestMethod = method
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Origin", "https://my.wingerapp.pro")
            setRequestProperty("Referer", "https://my.wingerapp.pro/")
            setRequestProperty("User-Agent", "Mozilla/5.0 WingerAndroid")
            instanceFollowRedirects = false
            connectTimeout = 15000
            readTimeout = 20000
            val cookie = cookies.cookieStore.get(URI(url)).joinToString("; ") { "${it.name}=${it.value}" }
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        c.headerFields["Set-Cookie"]?.forEach { raw ->
            HttpCookie.parse(raw).forEach { cookies.cookieStore.add(URI(url), it) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        return code to text
    }

    private fun open(url: String): HttpURLConnection {
        val u = URL(url)
        val n = underlying()
        return ((n?.openConnection(u) ?: u.openConnection()) as HttpURLConnection)
    }

    private fun underlying(): Network? {
        if (!::app.isInitialized) return null
        val cm = app.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.firstOrNull {
            val caps = cm.getNetworkCapabilities(it) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private fun persist() {
        if (!::app.isInitialized) return
        val raw = cookies.cookieStore.cookies.joinToString("\n") { "${it.domain}|${it.name}|${it.value}" }
        Store.cookies(app, raw)
    }
}

data class Sub(val name: String, val expires: String, val url: String, val devices: Int)
