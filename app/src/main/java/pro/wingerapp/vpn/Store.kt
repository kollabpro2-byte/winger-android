package pro.wingerapp.vpn

import android.content.Context

object Store {
    private const val FILE = "winger"
    fun save(ctx: Context, login: String, password: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("login", login)
            .putString("password", password)
            .apply()
    }
    fun login(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("login", "").orEmpty()
    fun password(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("password", "").orEmpty()
}
