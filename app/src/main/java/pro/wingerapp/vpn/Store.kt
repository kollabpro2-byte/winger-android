package pro.wingerapp.vpn

import android.content.Context

object Store {
    private const val FILE = "winger"
    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun save(ctx: Context, login: String, password: String) {
        p(ctx).edit().putString("login", login).putString("password", password).apply()
    }
    fun login(ctx: Context) = p(ctx).getString("login", "").orEmpty()
    fun password(ctx: Context) = p(ctx).getString("password", "").orEmpty()
    fun running(ctx: Context) = p(ctx).getBoolean("running", false)
    fun running(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("running", on).apply() }
    fun cookies(ctx: Context) = p(ctx).getString("cookies", "").orEmpty()
    fun cookies(ctx: Context, raw: String) { p(ctx).edit().putString("cookies", raw).apply() }
    fun pingHttp(ctx: Context) = p(ctx).getBoolean("pingHttp", false)
    fun pingHttp(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("pingHttp", on).apply() }
    fun testUrl(ctx: Context) = p(ctx).getString("testUrl", "https://www.gstatic.com/generate_204").orEmpty()
    fun testUrl(ctx: Context, url: String) { p(ctx).edit().putString("testUrl", url).apply() }
    fun dnsMode(ctx: Context) = p(ctx).getString("dnsMode", "auto").orEmpty()
    fun dnsMode(ctx: Context, mode: String) { p(ctx).edit().putString("dnsMode", mode).apply() }
    fun dnsCustom(ctx: Context) = p(ctx).getString("dnsCustom", "").orEmpty()
    fun dnsCustom(ctx: Context, ip: String) { p(ctx).edit().putString("dnsCustom", ip).apply() }
    fun dnsServer(ctx: Context): String? = when (dnsMode(ctx)) {
        "cf" -> "1.1.1.1"
        "gg" -> "8.8.8.8"
        "own" -> dnsCustom(ctx).trim().takeIf { it.isNotBlank() }
        else -> null
    }
    fun bypass(ctx: Context) = p(ctx).getString("bypass", "").orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
    fun bypass(ctx: Context, hosts: List<String>) { p(ctx).edit().putString("bypass", hosts.joinToString("\n")).apply() }
    fun coreDown(ctx: Context) = p(ctx).getLong("coreDown", 0L)
    fun coreUp(ctx: Context) = p(ctx).getLong("coreUp", 0L)
    fun coreTraffic(ctx: Context, down: Long, up: Long) { p(ctx).edit().putLong("coreDown", down).putLong("coreUp", up).apply() }
}
