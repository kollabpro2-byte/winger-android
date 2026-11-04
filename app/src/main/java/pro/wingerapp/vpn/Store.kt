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
}
