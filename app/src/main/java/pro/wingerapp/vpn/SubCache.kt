package pro.wingerapp.vpn

import android.content.Context
import org.json.JSONObject
import pro.wingerapp.vpn.net.Sub

/**
 * Кэш последней успешной подписки.
 *
 * Зачем: серверы и конфиг приходят по сети, и до ответа главная показывала
 * пустой экран. С кэшем прошлая подписка видна сразу после запуска, а сеть
 * догружает свежие данные в фоне.
 *
 * Хранится в уже существующем шифрованном файле (`Store`), потому что внутри
 * конфига серверов есть ключи доступа. Чтение идёт в фоновой инициализации.
 */
object SubCache {

    data class Cached(val plan: Sub, val raw: String, val selected: String, val savedAt: Long)

    fun save(ctx: Context, plan: Sub, raw: String, selected: String) {
        runCatching { Store.subCache(ctx, encode(plan, raw, selected)) }
    }

    fun load(ctx: Context): Cached? = runCatching { decode(Store.subCache(ctx)) }.getOrNull()

    /** Удаляет кэш: при выходе из аккаунта чужие серверы видеть не нужно. */
    fun clear(ctx: Context) {
        runCatching { Store.clearSubCache(ctx) }
    }

    /** Сериализация вынесена отдельно — её проверяют юнит-тесты. */
    internal fun encode(plan: Sub, raw: String, selected: String, savedAt: Long = System.currentTimeMillis()): String =
        JSONObject().apply {
            put("savedAt", savedAt)
            put("selected", selected)
            put("raw", raw)
            put("name", plan.name)
            put("expires", plan.expires)
            put("url", plan.url)
            put("devices", plan.devices)
            put("devicesUsed", plan.devicesUsed)
            put("trafficUsedGb", plan.trafficUsedGb)
            put("trafficLimitGb", plan.trafficLimitGb)
            put("unlimitedTraffic", plan.unlimitedTraffic)
        }.toString()

    internal fun decode(text: String): Cached? {
        if (text.isBlank()) return null
        return runCatching {
            val json = JSONObject(text)
            val raw = json.optString("raw")
            if (raw.isBlank()) return null
            val plan = Sub(
                name = json.optString("name"),
                expires = json.optString("expires"),
                url = json.optString("url"),
                devices = json.optInt("devices"),
                devicesUsed = json.optInt("devicesUsed"),
                trafficUsedGb = json.optDouble("trafficUsedGb"),
                trafficLimitGb = json.optDouble("trafficLimitGb"),
                unlimitedTraffic = json.optBoolean("unlimitedTraffic", true),
            )
            Cached(plan, raw, json.optString("selected"), json.optLong("savedAt"))
        }.getOrNull()
    }
}

