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
    private const val UI_FILE = "winger_ui"
    private const val LEGACY_FILE = "winger"
    private const val MASTER_KEY = "winger_master_key"

    /**
     * Только то, что действительно секрет: сессия, логин/пароль и последний
     * конфиг туннеля (в нём ключи сервера). Всё остальное — настройки интерфейса,
     * и держать их в зашифрованном файле незачем: каждое обращение к нему
     * поднимает Android Keystore, а это 100–300 мс на холодном старте.
     */
    private val SECRET_KEYS = setOf("cookies", "login", "password", "lastConfig", "subCache")

    @Volatile
    private var uiCache: SharedPreferences? = null

    @Volatile
    private var secretCache: SharedPreferences? = null

    /** Настройки интерфейса: обычный файл, читается мгновенно. */
    private fun prefs(ctx: Context): SharedPreferences? {
        uiCache?.let { return it }
        synchronized(this) {
            uiCache?.let { return it }
            val app = ctx.applicationContext
            val p = runCatching { app.getSharedPreferences(UI_FILE, Context.MODE_PRIVATE) }.getOrNull()
            if (p != null) migrateLegacy(app, p)
            uiCache = p
            return p
        }
    }

    @Suppress("DEPRECATION")
    private fun secure(ctx: Context): SharedPreferences? {
        secretCache?.let { return it }
        synchronized(this) {
            secretCache?.let { return it }
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
                VpnLog.add("secure prefs недоступны: ${it.javaClass.simpleName}: ${it.message}")
            }.getOrNull()
            secretCache = secure
            return secure
        }
    }

    /**
     * Переносит настройки из старого шифрованного файла в обычный. Вызывается из
     * фоновой инициализации, а не из onCreate: создание зашифрованного файла само
     * обращается к Keystore.
     */
    fun migrateSettingsIntoPlain(ctx: Context) {
        val secure = runCatching { secure(ctx) }.getOrNull() ?: return
        val ui = prefs(ctx) ?: return
        val movable = runCatching { secure.all }.getOrNull()
            ?.filterKeys { it !in SECRET_KEYS }.orEmpty()
        if (movable.isEmpty()) return
        val toUi = ui.edit()
        val fromSecure = secure.edit()
        movable.forEach { (key, value) ->
            when (value) {
                is String -> toUi.putString(key, value)
                is Boolean -> toUi.putBoolean(key, value)
                is Int -> toUi.putInt(key, value)
                is Long -> toUi.putLong(key, value)
                is Float -> toUi.putFloat(key, value)
            }
            fromSecure.remove(key)
        }
        toUi.apply()
        fromSecure.apply()
        VpnLog.add("настройки перенесены в обычный файл: ${movable.size}")
    }

    /** Copy values from the old plaintext file once, then wipe it. */
    private fun migrateLegacy(ctx: Context, target: SharedPreferences) {
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

    private fun writeSecret(ctx: Context, block: SharedPreferences.Editor.() -> Unit) {
        val p = runCatching { secure(ctx) }.getOrNull() ?: return
        p.edit().apply(block).apply()
    }

    private fun text(ctx: Context, key: String, fallback: String = ""): String =
        prefs(ctx)?.getString(key, fallback).orEmpty()

    private fun secretText(ctx: Context, key: String): String =
        runCatching { secure(ctx)?.getString(key, "") }.getOrNull().orEmpty()

    // --- credentials -----------------------------------------------------------------

    fun save(ctx: Context, login: String, password: String) = writeSecret(ctx) {
        putString("login", login)
        putString("password", password)
    }

    fun login(ctx: Context) = secretText(ctx, "login")

    fun password(ctx: Context) = secretText(ctx, "password")

    fun cookies(ctx: Context) = secretText(ctx, "cookies")

    fun cookies(ctx: Context, raw: String) = writeSecret(ctx) { putString("cookies", raw) }

    /** Drop the session, keep the settings. */
    fun logout(ctx: Context) {
        writeSecret(ctx) {
            remove("login")
            remove("password")
            remove("cookies")
            remove("lastConfig")
        }
        write(ctx) { putBoolean("running", false) }
    }

    // --- tunnel ----------------------------------------------------------------------

    fun running(ctx: Context) = prefs(ctx)?.getBoolean("running", false) ?: false

    fun running(ctx: Context, on: Boolean) = write(ctx) { putBoolean("running", on) }

    /** Last applied sing-box config, so a sticky restart does not lose the tunnel. */
    fun lastConfig(ctx: Context) = secretText(ctx, "lastConfig")

    fun lastConfig(ctx: Context, json: String) = writeSecret(ctx) { putString("lastConfig", json) }

    fun clearLastConfig(ctx: Context) = writeSecret(ctx) { remove("lastConfig") }

    /** Кэш последней успешной подписки (внутри — ключи серверов, поэтому шифруем). */
    fun subCache(ctx: Context) = secretText(ctx, "subCache")

    fun subCache(ctx: Context, json: String) = writeSecret(ctx) { putString("subCache", json) }

    fun clearSubCache(ctx: Context) = writeSecret(ctx) { remove("subCache") }

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

    /**
     * Только IPv4 для адреса прокси. По умолчанию ВЫКЛЮЧЕНО, то есть IPv6 не
     * используется: адрес сервера берётся по A-записи, а если её нет — ядру
     * отдаётся имя, и оно резолвится само. IPv6-адреса в подписке — самая частая
     * причина «ядро запущено, а трафика нет»: dial по v6 висит до таймаута.
     */
    fun ipv6Proxy(ctx: Context) = prefs(ctx)?.getBoolean("ipv6Proxy", false) ?: false

    fun ipv6Proxy(ctx: Context, on: Boolean) = write(ctx) { putBoolean("ipv6Proxy", on) }

    /**
     * Как часто обновляются счётчики трафика ↑ / ↓ на главной, в секундах.
     * По умолчанию 10: цифры всё равно меняются на килобайты, а каждое
     * обновление — это пробуждение процесса и перерисовка строки. В настройках
     * можно выбрать 1 / 5 / 10 / 30 с.
     */
    fun trafficIntervalSec(ctx: Context) =
        prefs(ctx)?.getInt("trafficIntervalSec", 10)?.coerceIn(1, 60) ?: 10

    fun trafficIntervalSec(ctx: Context, seconds: Int) =
        write(ctx) { putInt("trafficIntervalSec", seconds.coerceIn(1, 60)) }

    /** Verbose sing-box file log, also for release builds (diagnostics on device). */
    /**
     * Прослойка для шлюза Яндекса (`*.apigw.yandexcloud.net`).
     *
     * 0 — выключена: ядро подключается к шлюзу напрямую, как Xray;
     * 1 — «флаш»: байты идут как есть, после каждого сообщения уходит пустое.
     *     Лечит все сценарии, но сообщений вдвое больше — медленнее;
     * 2 — «кадры»: нумерация seq и восстановление порядка. Требует такого же
     *     разбора на стороне сервера за шлюзом;
     * 3 — «авто»: байты копятся в пачки до 32 КБ, пустой «толкач» уходит, когда
     *     пачка закрылась — по размеру или по паузе 25 мс. Открывает страницы,
     *     не тормозит видео и доводит отправку файлов.
     *
     * По умолчанию «авто».
     */
    fun lte4Mode(ctx: Context) = prefs(ctx)?.getInt("lte4Mode", Lte4.MODE_AUTO)?.coerceIn(0, 3)
        ?: Lte4.MODE_AUTO

    fun lte4Mode(ctx: Context, mode: Int) = write(ctx) { putInt("lte4Mode", mode.coerceIn(0, 3)) }

    /**
     * Сколько параллельных отправок вниз просить у релея (`f=1cN`).
     * По замерам релея: 32 — лучший результат (130–150 Мбит/с), 16 — если
     * телефону не хватает памяти (70–110 Мбит/с), 1 — строгий порядок.
     */
    fun lte4Streams(ctx: Context) = prefs(ctx)?.getInt("lte4Streams", 32)?.coerceIn(1, 32) ?: 32

    fun lte4Streams(ctx: Context, n: Int) = write(ctx) { putInt("lte4Streams", n.coerceIn(1, 32)) }

    /**
     * Один раз после обновления переводим «кадры» в «авто»: на живом трафике
     * кадры оказались медленнее обычного режима, и оставлять их выбранными
     * нечестно. Явный выбор после этой миграции сохраняется.
     */
    fun migrateLte4Mode(ctx: Context) {
        val p = prefs(ctx) ?: return
        if (p.getBoolean("lte4Migrated", false)) return
        if (p.getInt("lte4Mode", Lte4.MODE_AUTO) == Lte4.MODE_FRAMED) {
            lte4Mode(ctx, Lte4.MODE_AUTO)
        }
        write(ctx) { putBoolean("lte4Migrated", true) }
    }

    fun coreLog(ctx: Context) = prefs(ctx)?.getBoolean("coreLog", false) ?: false

    fun coreLog(ctx: Context, on: Boolean) = write(ctx) { putBoolean("coreLog", on) }

    /**
     * Тема оформления: 0 — тёмная, 1 — средняя, 2 — светлая. Средняя стоит по
     * умолчанию: она между чёрным и серым и подходит большинству.
     */
    fun themeMode(ctx: Context) = prefs(ctx)?.getInt("themeMode", 1)?.coerceIn(0, 2) ?: 1

    fun themeMode(ctx: Context, mode: Int) = write(ctx) { putInt("themeMode", mode.coerceIn(0, 2)) }

    /**
     * Через сколько очищать журнал, в минутах. По умолчанию 30, максимум 120:
     * лог нужен для разбора последних событий, а не как архив.
     */
    fun logClearMinutes(ctx: Context) =
        prefs(ctx)?.getInt("logClearMinutes", 30)?.coerceIn(5, 120) ?: 30

    fun logClearMinutes(ctx: Context, minutes: Int) =
        write(ctx) { putInt("logClearMinutes", minutes.coerceIn(5, 120)) }

    /**
     * Какие разделы настроек пользователь раскрыл. `null` — он ещё ничего не
     * трогал, тогда открываем только «Отображение». Если свернул — так и
     * запоминаем: приложение не должно навязывать своё состояние.
     */
    fun openSections(ctx: Context): Set<String>? {
        val raw = prefs(ctx)?.getString("openSections", null) ?: return null
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun openSections(ctx: Context, ids: Set<String>) =
        write(ctx) { putString("openSections", ids.joinToString(",")) }

    /** Раскрыт ли блок устройств в аккаунте (по умолчанию — да). */
    fun devicesOpen(ctx: Context) = prefs(ctx)?.getBoolean("devicesOpen", true) ?: true

    fun devicesOpen(ctx: Context, on: Boolean) = write(ctx) { putBoolean("devicesOpen", on) }
}
