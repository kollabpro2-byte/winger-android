package pro.wingerapp.vpn

/**
 * Встроенные профили CDN: у узла один адрес, но несколько XHTTP-входов с разными
 * путями, а подписка отдаёт только один из них.
 *
 * Профиль применяется **только** если путь из подписки совпадает с одним из
 * известных: если на сервере пути поменяют, профиль просто не сработает и
 * приложение останется на одном канале — молча ломать трафик не будем.
 */
internal object CdnProfiles {

    private val profiles: Map<String, List<String>> = mapOf(
        "ya5.wingertop.pro" to listOf(
            "/api/v2/dicom/sync/",
            "/api/v3/pwa/sync/stream/",
            "/api/v2/vault/sync/datastream/",
        ),
    )

    /** Все пути узла, если подписка дала один из них. Иначе null. */
    fun pathsFor(host: String, path: String): List<String>? {
        val all = profiles[host.trim().lowercase()] ?: return null
        if (all.none { it == path.trim() }) return null
        return all
    }
}
