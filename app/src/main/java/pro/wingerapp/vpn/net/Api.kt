package pro.wingerapp.vpn.net

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import pro.wingerapp.vpn.Store
import pro.wingerapp.vpn.VpnLog
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

    /** Fired on the main thread when the panel answers 401: the UI must re-login. */
    @Volatile
    var onUnauthorized: (() -> Unit)? = null

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

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

    fun hasSession(): Boolean = runCatching {
        cookies.cookieStore.cookies.isNotEmpty() ||
            (::app.isInitialized && Store.cookies(app).isNotBlank())
    }.getOrDefault(false)

    /** Drop the in-memory session (cookies are persisted by [logout]). */
    fun reset() {
        runCatching { cookies.cookieStore.removeAll() }
    }

    fun logout() {
        reset()
        if (::app.isInitialized) Store.logout(app)
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

    fun register(username: String, password: String, hwid: String = ""): String? {
        val name = username.trim()
        if (!name.matches(Regex("^[a-zA-Z0-9_-]{3,32}$"))) return "Логин: 3–32 символа, латиница, цифры, _ и -"
        if (password.length < 8 || password.length > 128) return "Пароль от 8 до 128 символов"
        val body = JSONObject().put("username", name).put("passwordHash", sha256(password))
            .put("source", "app").put("hwid", hwid)
        val docs = legalKeys()
        if (docs.length() > 0) body.put("acceptedLegalDocuments", docs)
        val (code, text) = post("$BASE/auth/register", body.toString())
        if (code !in 200..299) return apiError(code, text, "Регистрация не прошла")
        persist()
        return login(name, password)
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
        if (me.first != 200) return null
        val root = JSONObject(me.second)
        val user = when {
            root.has("webAccount") || root.has("telegramId") || root.has("id") -> root
            root.optJSONObject("user") != null -> root.getJSONObject("user")
            root.optJSONObject("data") != null -> root.getJSONObject("data")
            else -> root
        }
        val sub = get("$BASE/subscription")
        val has = sub.first == 200 && sub.second.isNotBlank() && sub.second != "null"
        val o = if (has) JSONObject(sub.second) else null
        val plan = o?.optJSONObject("plan")
        val planId = plan?.optString("id").orEmpty()
        val offers = offersFor(planId)
        var limit = o?.optInt("deviceLimit", 0) ?: offers.limit
        if (limit <= 0) limit = offers.limit
        if (limit <= 0) limit = 4
        val usedRaw = o?.optDouble("trafficUsed", 0.0) ?: 0.0
        val limitRaw = o?.optDouble("trafficLimit", 0.0) ?: 0.0
        fun gb(v: Double) = if (v > 10000) v / 1024 / 1024 / 1024 else v
        val used = "%.1f".format(gb(usedRaw)).replace('.', ',')
        val total = if (o == null || o.isNull("trafficLimit") || limitRaw <= 0) "∞" else "%.0f ГБ".format(gb(limitRaw))
        val tgId = jsonText(user, "telegramId").ifBlank { jsonText(user.optJSONObject("webAccount"), "telegramId") }
        val tgUser = jsonText(user, "telegramUsername")
            .ifBlank { jsonText(user, "username") }
            .ifBlank { jsonText(user.optJSONObject("webAccount"), "telegramUsername") }
            .ifBlank { jsonText(user.optJSONObject("webAccount"), "username") }
        val telegramDisplay = when {
            tgUser.isNotBlank() && tgUser.any { !it.isDigit() } -> if (tgUser.startsWith("@")) tgUser else "@$tgUser"
            tgId.isNotBlank() -> "ID $tgId"
            else -> null
        }
        val devices = if (has) deviceList() else emptyList()
        return Cabinet(
            login = user.optJSONObject("webAccount")?.optString("login").orEmpty().ifBlank { jsonText(user, "username") }.ifBlank { "аккаунт" },
            telegram = telegramDisplay,
            plan = plan?.optString("name")?.ifBlank { null } ?: offers.planName,
            status = o?.optString("status").orEmpty().ifBlank { "NONE" },
            expires = o?.optString("expiresAt").orEmpty(),
            traffic = if (has) "$used ГБ из $total" else "нет подписки",
            devicesUsed = devices.size,
            deviceLimit = limit,
            subscriptionId = o?.optString("id").orEmpty(),
            planId = planId.ifBlank { offers.planId },
            offers = offers.items,
            devices = devices
        )
    }

    fun removeDevice(hwid: String): Boolean {
        val (code, _) = call("DELETE", "$BASE/devices/${java.net.URLEncoder.encode(hwid, "UTF-8")}", null)
        return code in 200..299
    }

    fun payWays(): List<PayWay> {
        val (code, text) = get("$BASE/gateways")
        val types = mutableSetOf<String>()
        val arr = runCatching { jsonList(text, "gateways", "items") }.getOrDefault(JSONArray())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.optString("type")?.takeIf { it.isNotBlank() }?.let { types += it }
        }
        if (code != 200 || types.isEmpty()) {
            // No hardcoded fallback: offering a gateway the panel does not accept
            // only produces a dead payment button.
            VpnLog.add("gateways: code=$code types=${types.size}")
            return emptyList()
        }
        val out = mutableListOf<PayWay>()
        if ("ANTILOPAY" in types) {
            out += PayWay("AntilopaPay СБП с автосписанием", "дальше списывается само", "ANTILOPAY", true)
            out += PayWay("AntilopaPay СБП", "разовая оплата", "ANTILOPAY", false)
        }
        if ("PLATEGA" in types) out += PayWay("Patega СБП или карта МИР на выбор", "", "PLATEGA", false)
        return out
    }

    fun pay(subscriptionId: String, planId: String, days: Int, price: String, gateway: String, autopay: Boolean): String {
        val body = JSONObject().put("gatewayType", gateway).put("source", "web")
        val url = if (subscriptionId.isNotBlank()) {
            body.put("subscriptionIds", JSONArray().put(subscriptionId))
                .put("expectedAmount", price)
                .put("expectedCurrency", "RUB")
                .put("durations", JSONArray().put(JSONObject().put("subscriptionId", subscriptionId).put("days", days)))
            "$BASE/payments/renewal-checkout"
        } else {
            body.put("planId", planId).put("durationDays", days).put("purchaseType", "NEW")
            "$BASE/payments/checkout"
        }
        if (autopay) {
            body.put("savePaymentMethod", true)
            body.put("savePaymentMethodConsent", true)
            body.put("autopayMethod", "SBP")
        }
        val (code, text) = post(url, body.toString())
        if (code !in 200..299) return apiError(code, text, "оплата не открылась")
        val link = runCatching { JSONObject(text).optString("checkoutUrl") }.getOrDefault("")
        return if (link.startsWith("http")) link else "ссылка оплаты не пришла"
    }

    private fun offersFor(planId: String): Offers {
        val (code, text) = get("$BASE/plans")
        if (code != 200 || text.isBlank()) return Offers(4, "", "Подписка", emptyList())
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
                val map = linkedMapOf<String, String>()
                var first = ""
                var currency = "RUB"
                for (n in 0 until prices.length()) {
                    val price = prices.getJSONObject(n)
                    val value = price.optString("price")
                    val gateway = price.optString("gatewayType")
                    if (gateway.isNotBlank() && value.isNotBlank()) map[gateway] = value
                    if (first.isBlank()) {
                        first = value
                        currency = price.optString("currency", "RUB")
                    }
                }
                items += Offer(dur.optInt("days"), map["ANTILOPAY"] ?: first, currency, map)
            }
            return Offers(p.optInt("deviceLimit", 4), p.optString("id"), p.optString("name", "Подписка"), items)
        }
        return Offers(4, "", "Подписка", emptyList())
    }

    fun supportThread(): SupportThread {
        val (code, text) = get("$BASE/support/tickets")
        if (code != 200 || text.isBlank()) return SupportThread(null, emptyList())
        val arr = jsonList(text, "tickets", "items")
        var id = ""
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            if (t.optString("status") != "closed") { id = t.optString("id"); break }
        }
        if (id.isBlank()) return SupportThread(null, emptyList())
        return thread(id)
    }

    fun attachmentBytes(ticketId: String, file: SupportFile): ByteArray? {
        val direct = file.url.takeIf { it.startsWith("http") }
        val (code, raw) = bytes(direct ?: "$BASE/support/tickets/$ticketId/attachments/${file.id}")
        if (code !in 200..299 || raw.isEmpty()) return null
        if (raw[0] == '{'.code.toByte()) {
            val o = runCatching { JSONObject(String(raw)) }.getOrNull() ?: return null
            val encoded = jsonText(o, "data").ifBlank { jsonText(o, "dataBase64") }.ifBlank { jsonText(o, "base64") }
            if (encoded.isNotBlank()) return runCatching { android.util.Base64.decode(encoded, android.util.Base64.DEFAULT) }.getOrNull()
            val url = jsonText(o, "url").ifBlank { jsonText(o, "href") }
            if (url.startsWith("http")) {
                val (c2, raw2) = bytes(url)
                return if (c2 in 200..299) raw2 else null
            }
            return null
        }
        return raw
    }

    fun telegramLink(): String {
        val (code, text) = post("$BASE/link/telegram/initiate", JSONObject().put("source", "app").toString())
        if (code !in 200..299) error(apiError(code, text, "Telegram не открылся"))
        val o = runCatching { JSONObject(text) }.getOrNull() ?: error("Telegram не открылся")
        val ready = jsonText(o, "url").ifBlank { jsonText(o, "link") }.ifBlank { jsonText(o, "deepLink") }
        if (ready.startsWith("http")) return ready
        val bot = jsonText(o, "botUsername").ifBlank { jsonText(o, "bot") }
        val token = jsonText(o, "code").ifBlank { jsonText(o, "token") }
        if (bot.isBlank() || token.isBlank()) error("Telegram не открылся")
        return "https://t.me/$bot?start=link_$token"
    }

    private fun thread(id: String): SupportThread {
        val (code, text) = get("$BASE/support/tickets/$id")
        if (code != 200) return SupportThread(null, emptyList())
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return SupportThread(null, emptyList())
        if (root.optString("status") == "closed") return SupportThread(null, emptyList())
        val messages = messageArray(root)
        val lines = (0 until messages.length()).mapNotNull { i ->
            val m = messages.optJSONObject(i) ?: return@mapNotNull null
            val files = filesOf(m)
            val text = jsonText(m, "content").ifBlank { jsonText(m, "text") }.ifBlank { jsonText(m, "body") }
            if (text.isBlank() && files.isEmpty()) return@mapNotNull null
            val kind = jsonText(m, "authorType").ifBlank { jsonText(m, "role") }.ifBlank { jsonText(m, "sender") }.lowercase()
            val mine = kind == "user" || kind == "client" || kind == "customer"
            SupportLine(if (mine) "Вы" else "Поддержка", text, files)
        }
        return SupportThread(id, lines)
    }

    private fun messageArray(root: JSONObject): JSONArray {
        root.optJSONArray("messages")?.takeIf { it.length() > 0 }?.let { return it }
        listOf("ticket", "data", "item").forEach { key ->
            root.optJSONObject(key)?.optJSONArray("messages")?.takeIf { it.length() > 0 }?.let { return it }
        }
        return root.optJSONArray("messages") ?: JSONArray()
    }

    private fun filesOf(m: JSONObject): List<SupportFile> {
        val out = mutableListOf<SupportFile>()
        fun take(f: JSONObject) {
            val purged = jsonText(f, "purgedAt")
            if (purged.isNotBlank()) return
            val id = jsonText(f, "id").ifBlank { jsonText(f, "attachmentId") }
            if (id.isBlank()) return
            val name = jsonText(f, "filename").ifBlank { jsonText(f, "name") }.ifBlank { jsonText(f, "fileName") }.ifBlank { "файл" }
            val mime = jsonText(f, "mimeType").ifBlank { jsonText(f, "mime") }.ifBlank { jsonText(f, "contentType") }
            val url = jsonText(f, "url").ifBlank { jsonText(f, "href") }
            val known = f.has("filename") || f.has("mimeType") || f.has("name") || url.isNotBlank()
            if (!known) return
            out += SupportFile(id, name, mime.ifBlank { guessMime(name) }, url)
        }
        listOf("attachments", "files", "images").forEach { key ->
            val arr = m.optJSONArray(key) ?: return@forEach
            for (i in 0 until arr.length()) m.let { arr.optJSONObject(i)?.let { take(it) } }
        }
        m.optJSONObject("attachment")?.let { take(it) }
        return out.distinctBy { it.id }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return if (ext in setOf("png", "jpg", "jpeg", "webp", "gif")) "image/$ext" else "application/octet-stream"
    }

    private fun jsonText(o: JSONObject?, key: String): String {
        if (o == null || !o.has(key) || o.isNull(key)) return ""
        val value = o.opt(key)?.toString().orEmpty()
        return if (value == "null") "" else value
    }

    private fun jsonList(text: String, vararg keys: String): JSONArray {
        val t = text.trim()
        if (t.startsWith("[")) return JSONArray(t)
        val o = runCatching { JSONObject(t) }.getOrNull() ?: return JSONArray()
        keys.forEach { key -> o.optJSONArray(key)?.let { return it } }
        return JSONArray()
    }

    fun sendSupport(ticketId: String?, message: String, files: List<Attached>): String? {
        var id = ticketId
        if (id.isNullOrBlank()) {
            val subject = message.lineSequence().firstOrNull()?.take(60)?.ifBlank { null } ?: "Обращение из приложения"
            val body = JSONObject().put("subject", subject).put("message", message.ifBlank { "вложение" })
            val (code, text) = post("$BASE/support/tickets", body.toString())
            if (code !in 200..299) return apiError(code, text, "тикет не создался")
            id = runCatching { JSONObject(text).optString("id") }.getOrNull()
            if (id.isNullOrBlank()) return "тикет создан без номера"
        } else if (message.isNotBlank()) {
            val (code, text) = post("$BASE/support/tickets/$id/reply", JSONObject().put("content", message).toString())
            if (code !in 200..299) return apiError(code, text, "сообщение не ушло")
        }
        files.forEach { file ->
            val payload = JSONObject()
                .put("filename", file.name)
                .put("mimeType", file.mime)
                .put("dataBase64", android.util.Base64.encodeToString(file.bytes, android.util.Base64.NO_WRAP))
            val (code, text) = post("$BASE/support/tickets/$id/attachments", payload.toString())
            if (code !in 200..299) return apiError(code, text, "файл не прикрепился")
        }
        return null
    }

    private fun bytes(url: String): Pair<Int, ByteArray> {
        val c = exchange(url) {
            requestMethod = "GET"
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Origin", "https://my.wingerapp.pro")
            setRequestProperty("Referer", "https://my.wingerapp.pro/")
            setRequestProperty("User-Agent", "Mozilla/5.0 WingerAndroid")
            connectTimeout = 15000
            readTimeout = 20000
            val cookie = cookies.cookieStore.get(URI(url)).joinToString("; ") { "${it.name}=${it.value}" }
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
        }
        val code = c.responseCode
        val payload = try {
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            stream?.readBytes() ?: ByteArray(0)
        } finally {
            runCatching { c.disconnect() }
        }
        return code to payload
    }

    private fun legalKeys(): JSONArray {
        val (code, text) = get("$BASE/legal-documents?locale=ru")
        val out = JSONArray()
        if (code != 200) return out
        val docs = runCatching { JSONObject(text).optJSONArray("documents") }.getOrNull() ?: return out
        for (i in 0 until docs.length()) {
            val key = docs.getJSONObject(i).optString("key")
            if (key.isNotBlank()) out.put(key)
        }
        return out
    }

    private fun apiError(code: Int, text: String, fallback: String): String {
        val o = runCatching { JSONObject(text) }.getOrNull()
        val kind = o?.optString("code").orEmpty()
        return when (kind) {
            "USERNAME_TAKEN" -> "Это имя уже занято"
            "LEGAL_CONSENT_REQUIRED" -> "Нужно принять правила сервиса"
            "AUTOPAY_NOT_AVAILABLE_FOR_PURCHASE", "AUTOPAY_NOT_AVAILABLE" -> "Для этого срока автосписание недоступно. Выберите 30, 90 или 365 дней"
            else -> o?.optString("message")?.ifBlank { null } ?: "$fallback ($code)"
        }
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
            val model = d.optString("deviceModel").ifBlank { d.optString("model") }
            val os = d.optString("osVersion").ifBlank { d.optString("os") }
            val platform = d.optString("platform")
            val ua = d.optString("userAgent")
            val system = listOf(platform, os).filter { it.isNotBlank() }.joinToString(" ")
            val title = listOf(model, system).filter { it.isNotBlank() }.joinToString(" · ")
                .ifBlank { ua.ifBlank { "Устройство" } }
            val seen = d.optString("lastSeenAt").replace("T", " ").take(16)
            HwDevice(d.optString("hwid"), title, seen)
        }
    }

    fun fetchConfig(url: String, hwid: String): String = getSub(url, hwid)
    fun fetchHapp(url: String, hwid: String): String = getSub(url, hwid)

    private fun getSub(url: String, hwid: String): String {
        val rawMaker = Build.MANUFACTURER.orEmpty()
        val maker = if (rawMaker.isEmpty()) "" else rawMaker.substring(0, 1).uppercase() + rawMaker.substring(1)
        val model = Build.MODEL.orEmpty()
        val device = if (model.startsWith(maker, true)) model else "$maker $model"
        val ver = pro.wingerapp.vpn.BuildConfig.VERSION_NAME
        val c = exchange(url) {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Happ/4.8.0/Android Winger/$ver")
            setRequestProperty("x-hwid", hwid)
            setRequestProperty("x-device-os", "Android")
            setRequestProperty("x-ver-os", Build.VERSION.RELEASE)
            setRequestProperty("x-device-model", device)
            connectTimeout = 15000
            readTimeout = 20000
        }
        return c.inputStream.bufferedReader().readText()
    }

    private fun post(url: String, body: String) = call("POST", url, body)
    private fun get(url: String) = call("GET", url, null)

    private fun call(method: String, url: String, body: String?): Pair<Int, String> {
        val c = exchange(url) {
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
        val text = try {
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            stream?.bufferedReader()?.readText().orEmpty()
        } finally {
            runCatching { c.disconnect() }
        }
        if (code == 401 && !url.endsWith("/auth/login")) {
            // The session is gone: the UI must go back to login instead of
            // showing "нет подписки" for a live account.
            main.post { runCatching { onUnauthorized?.invoke() } }
        }
        return code to text
    }

    private fun exchange(url: String, setup: HttpURLConnection.() -> Unit): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply(setup)
    }

    private fun persist() {
        if (!::app.isInitialized) return
        val raw = cookies.cookieStore.cookies.joinToString("\n") { "${it.domain}|${it.name}|${it.value}" }
        Store.cookies(app, raw)
    }
}

data class Sub(val name: String, val expires: String, val url: String, val devices: Int, val devicesUsed: Int = 0, val trafficUsedGb: Double = 0.0, val trafficLimitGb: Double = 0.0, val unlimitedTraffic: Boolean = true)
data class Offer(val days: Int, val price: String, val currency: String, val prices: Map<String, String> = emptyMap()) {
    fun amount(gateway: String) = prices[gateway] ?: price
}
data class PayWay(val title: String, val hint: String, val gateway: String, val autopay: Boolean)
data class SupportFile(val id: String, val name: String, val mime: String, val url: String = "")
data class SupportLine(val author: String, val text: String, val files: List<SupportFile> = emptyList())
data class SupportThread(val id: String?, val lines: List<SupportLine>)
data class Attached(val name: String, val mime: String, val bytes: ByteArray)
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
    val planId: String,
    val offers: List<Offer>,
    val devices: List<HwDevice>
)
private data class Offers(val limit: Int, val planId: String, val planName: String, val items: List<Offer>)
