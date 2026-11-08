package pro.wingerapp.vpn

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences

/**
 * Everything that survives a restart lives here. Values are encrypted with a
 * Keystore-backed master key, so a device dump or a backup no longer exposes the
 * password or the session cookie in plain text.
 *
 * The old plaintext file is migrated once and then wiped.
 */
object Store {
    private const val SECURE_FILE = "winger_secure"
    private const val LEGACY_FILE = "winger"
    private const val MASTER_KEY = "winger_master_key"

    @Volatile
    private var cached: SharedPreferences? = null

    @Suppress("DEPRECATION")
    private fun prefs(ctx: Context): SharedPreferences? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val app = ctx.applicationContext
            val secure = runCatching {
                EncryptedSharedPreferences.create(
                    SECURE_FILE,
                    MASTER_KEY,
                    app,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.onFailure {
                VpnLog.add("secure prefs недоступны, откат на обычные: ${it.javaClass.simpleName}: ${it.message}")
            }.getOrNull()
            if (secure != null) migrate(app, secure)
            val chosen = secure ?: app.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
            cached = chosen
            return chosen
        }
    }

    /** Copy values from the old plaintext file once, then wipe it. */
    private fun migrate(ctx: Context, target: SharedPreferences) {
        val legacy = runCatching { ctx.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE) }.getOrNull() ?: return
        val values = legacy.all
        if (values.isEmpty()) return
        val edit = target.edit()
        values.forEach { (key, value) ->
            when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Long -> edit.putLong(key, value)
            }
        }
        edit.apply()
        legacy.edit().clear().apply()
        VpnLog.add("миграция prefs: ${values.size} значений, старый файл очищен")
    }

    private fun write(ctx: Context, block: SharedPreferences.Editor.() -> Unit) {
        val p = prefs(ctx) ?: return
        p.edit().apply(block).apply()
    }

    private fun text(ctx: Context, key: String, fallback: String = ""): String =
        prefs(ctx)?.getString(key, fallback).orEmpty()

    // --- credentials -----------------------------------------------------------------

    fun save(ctx: Context, login: String, password: String) = write(ctx) {
        putString("login", login)
        putString("password", password)
    }

    fun login(ctx: Context) = text(ctx, "login")

    fun password(ctx: Context) = text(ctx, "password")

    fun cookies(ctx: Context) = text(ctx, "cookies")

    fun cookies(ctx: Context, raw: String) = write(ctx) { putString("cookies", raw) }

    /** Drop the session, keep the settings. */
    fun logout(ctx: Context) = write(ctx) {
        remove("login")
        remove("password")
        remove("cookies")
        remove("lastConfig")
        putBoolean("running", false)
    }

    // --- tunnel ----------------------------------------------------------------------

    fun running(ctx: Context) = prefs(ctx)?.getBoolean("running", false) ?: false

    fun running(ctx: Context, on: Boolean) = write(ctx) { putBoolean("running", on) }

    /** Last applied sing-box config, so a sticky restart does not lose the tunnel. */
    fun lastConfig(ctx: Context) = text(ctx, "lastConfig")

    fun lastConfig(ctx: Context, json: String) = write(ctx) { putString("lastConfig", json) }

    fun clearLastConfig(ctx: Context) = write(ctx) { remove("lastConfig") }

    // --- settings --------------------------------------------------------------------

    fun dnsMode(ctx: Context) = text(ctx, "dnsMode", "auto")

    fun dnsMode(ctx: Context, mode: String) = write(ctx) { putString("dnsMode", mode) }

    fun dnsCustom(ctx: Context) = text(ctx, "dnsCustom")

    fun dnsCustom(ctx: Context, ip: String) = write(ctx) { putString("dnsCustom", ip) }

    fun dnsServer(ctx: Context): String? = when (dnsMode(ctx)) {
        "cf" -> "1.1.1.1"
        "gg" -> "8.8.8.8"
        "own" -> dnsCustom(ctx).trim().takeIf { it.isNotBlank() }
        else -> null
    }

    fun bypass(ctx: Context) = text(ctx, "bypass").lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun bypass(ctx: Context, hosts: List<String>) = write(ctx) {
        putString("bypass", hosts.joinToString("\n"))
    }

    fun useMux(ctx: Context) = prefs(ctx)?.getBoolean("useMux", false) ?: false

    fun useMux(ctx: Context, on: Boolean) = write(ctx) { putBoolean("useMux", on) }

    fun mtuLow(ctx: Context) = prefs(ctx)?.getBoolean("mtuLow", false) ?: false

    fun mtuLow(ctx: Context, on: Boolean) = write(ctx) { putBoolean("mtuLow", on) }

    /** Verbose sing-box file log, also for release builds (diagnostics on device). */
    fun coreLog(ctx: Context) = prefs(ctx)?.getBoolean("coreLog", false) ?: false

    fun coreLog(ctx: Context, on: Boolean) = write(ctx) { putBoolean("coreLog", on) }
}
