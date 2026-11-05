package pro.wingerapp.vpn.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.json.JSONArray
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
        val usedRaw = o.optDouble("trafficUsed", o.optDouble("usedTraffic", 0.0))
        val limitRaw = o.optDouble("trafficLimit", o.optDouble("trafficLimitGb", 0.0))
        fun gb(v: Double) = if (v > 10000) v / 1024 / 1024 / 1024 else v
        val plan = o.optJSONObject("plan")
        var limit = o.optInt("deviceLimit", 0)
        if (limit <= 0) limit = planLimit(plan?.optString("id").orEmpty())
        return Sub(
            name = plan?.optString("name") ?: "Подписка",
            expires = o.optString("expiresAt", ""),
            url = o.optString("url", ""),
            devices = limit,
            devicesUsed = devicesCount(),
            trafficUsedGb = gb(usedRaw),
            trafficLimitGb = gb(limitRaw),
            unlimitedTraffic = o.isNull("trafficLimit") || o.optBoolean("unlimitedTraffic", limitRaw == 0.0)
        )
    }

    fun devicesCount(): Int {
        val (code, text) = get("$BASE/devices")
        if (code != 200 || text.isBlank()) return 0
        return runCatching {
            val t = text.trim()
            if (t.startsWith("[")) org.json.JSONArray(t).length()
            else org.json.JSONObject(t).optJSONArray("devices")?.length()
                ?: org.json.JSONObject(t).optJSONArray("items")?.length() ?: 0
        }.getOrDefault(0)
    }

    fun cabinet(): Cabinet? {
        val me = get("$BASE/me")
        val sub = get("$BASE/subscription")
        if (me.first != 200 || sub.first != 200) return null
        val user = JSONObject(me.second)
        val o = JSONObject(sub.second)
        val plan = o.optJSONObject("plan")
        val planId = plan?.optString("id").orEmpty()
        val offers = offersFor(planId)
        var limit = o.optInt("deviceLimit", 0)
        if (limit <= 0) limit = offers.limit
        if (limit <= 0) limit = 4
        val usedRaw = o.optDouble("trafficUsed", 0.0)
        val limitRaw = o.optDouble("trafficLimit", 0.0)
        fun gb(v: Double) = if (v > 10000) v / 1024 / 1024 / 1024 else v
        val used = "%.1f".format(gb(usedRaw)).replace('.', ',')
        val total = if (o.isNull("trafficLimit") || limitRaw <= 0) "∞" else "%.0f ГБ".format(gb(limitRaw))
        val linked = user.optString("telegramId").isNotBlank()
        val devices = deviceList()
        return Cabinet(
            login = user.optJSONObject("webAccount")?.optString("login").orEmpty().ifBlank { "аккаунт" },
            telegram = if (linked) user.optString("username").ifBlank { "привязан" } else null,
            plan = plan?.optString("name") ?: "Подписка",
            status = o.optString("status", ""),
            expires = o.optString("expiresAt", ""),
            traffic = "$used ГБ из $total",
            devicesUsed = devices.size,
            deviceLimit = limit,
            subscriptionId = o.optString("id"),
            offers = offers.items,
            devices = devices
        )
    }

    fun removeDevice(hwid: String): Boolean {
        val (code, _) = call("DELETE", "$BASE/devices/${java.net.URLEncoder.encode(hwid, "UTF-8")}", null)
        return code in 200..299
    }

    fun payRenewal(subscriptionId: String, days: Int, price: String): String {
        val body = JSONObject()
            .put("subscriptionIds", JSONArray().put(subscriptionId))
            .put("gatewayType", "ANTILOPAY")
            .put("expectedAmount", price)
            .put("expectedCurrency", "RUB")
            .put("source", "web")
            .put("durations", JSONArray().put(JSONObject().put("subscriptionId", subscriptionId).put("days", days)))
        val (code, text) = post("$BASE/payments/renewal-checkout", body.toString())
        if (code !in 200..299) {
            return runCatching { JSONObject(text).optString("message") }.getOrNull()?.ifBlank { null }
                ?: "оплата не открылась ($code)"
        }
        val url = runCatching { JSONObject(text).optString("checkoutUrl") }.getOrDefault("")
        return if (url.startsWith("http")) url else "ссылка оплаты не пришла"
    }

    private fun offersFor(planId: String): Offers {
        val (code, text) = get("$BASE/plans")
        if (code != 200 || text.isBlank()) return Offers(4, emptyList())
        val arr = JSONArray(text)
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            if (planId.isNotBlank() && p.optString("id") != planId) continue
            val items = mutableListOf<Offer>()
            val durations = p.optJSONArray("durations") ?: JSONArray()
            for (d in 0 until durations.length()) {
                val dur = durations.getJSONObject(d)
                val prices = dur.optJSONArray("prices") ?: continue
                if (prices.length() == 0) continue
                val price = prices.getJSONObject(0)
                items += Offer(dur.optInt("days"), price.optString("price"), price.optString("currency", "RUB"))
            }
            return Offers(p.optInt("deviceLimit", 4), items)
        }
        return Offers(4, emptyList())
    }

    private fun planLimit(planId: String) = offersFor(planId).limit

    private fun deviceList(): List<HwDevice> {
        val (code, text) = get("$BASE/devices")
        if (code != 200 || text.isBlank()) return emptyList()
        val arr = runCatching {
            val t = text.trim()
            if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("devices") ?: JSONArray()
        }.getOrDefault(JSONArray())
        return (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            val agent = d.optString("userAgent").substringBefore("/").ifBlank { "Клиент" }
            val platform = d.optString("platform").ifBlank { "Устройство" }
            val seen = d.optString("lastSeenAt").replace("T", " ").take(16)
            HwDevice(d.optString("hwid"), "$platform · $agent", seen)
        }
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

data class Sub(val name: String, val expires: String, val url: String, val devices: Int, val devicesUsed: Int = 0, val trafficUsedGb: Double = 0.0, val trafficLimitGb: Double = 0.0, val unlimitedTraffic: Boolean = true)
data class Offer(val days: Int, val price: String, val currency: String)
data class HwDevice(val hwid: String, val title: String, val seen: String)
data class Cabinet(
    val login: String,
    val telegram: String?,
    val plan: String,
    val status: String,
    val expires: String,
    val traffic: String,
    val devicesUsed: Int,
    val deviceLimit: Int,
    val subscriptionId: String,
    val offers: List<Offer>,
    val devices: List<HwDevice>
)
private data class Offers(val limit: Int, val items: List<Offer>)
