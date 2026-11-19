package pro.wingerapp.vpn

/**
 * Известные адреса CDN. Нужны только для того, чтобы честно не показывать пинг
 * у узлов, где TCP-рукопожатие завершает край CDN, а не сам сервер.
 */
internal object CdnProfiles {

    private val hosts = setOf("ya5.wingertop.pro")

    fun isCdnHost(host: String): Boolean = hosts.contains(host.trim().lowercase())
}
