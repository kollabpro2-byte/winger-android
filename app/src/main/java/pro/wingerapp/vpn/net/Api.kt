package pro.wingerapp.vpn.net

import org.json.JSONObject
import java.net.CookieManager
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

object Api {
    const val BASE = "https://my.wingerapp.pro/api/v1"
    val cookies = CookieManager()

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
            return JSONObject(text).optString("message", "Неверный логин или пароль")
        }
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
            devices = o.optInt("deviceLimit", 4)
        )
    }

    fun fetchConfig(url: String, hwid: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            // Remnawave отдаёт готовый sing-box JSON только клиентам sfa/singbox.
            setRequestProperty("User-Agent", "sfa/1.11.0")
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
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
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
}

data class Sub(val name: String, val expires: String, val url: String, val devices: Int)
