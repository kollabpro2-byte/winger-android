package pro.wingerapp.vpn

import org.json.JSONArray
import org.json.JSONObject

/**
 * Переносит `routing` и `dns` из подписки (формат Xray/Remnawave) в sing-box.
 *
 * Где смысл совпадает — переносим один в один: правила проверяются сверху вниз,
 * первое совпадение выигрывает, как в Xray. Где совпадения нет — правило
 * выбрасывается и попадает в список пропущенных, чтобы это было видно в логе, а
 * не выглядело «настройки не подтянулись».
 *
 * Что не переносится:
 *  - `geosite:` и `geoip:` (кроме `geoip:private`) — в sing-box 1.12+ это
 *    отдельные rule-set файлы, которых в подписке нет;
 *  - `inboundTag` — у нас нет отдельных DNS-инбаундов, как в Xray;
 *  - `domainStrategy`, `domainMatcher` — прямых аналогов нет.
 */
internal object RoutingPlan {

    /** Роутинг из подписки выключен — работает то, что собрано из настроек. */
    const val OFF = 0

    /** Только правила маршрутизации. */
    const val RULES = 1

    /** Правила и DNS из подписки. */
    const val RULES_AND_DNS = 2

    /** Правила для sing-box плюс DNS-блок (null — оставить наш). */
    class Plan(val rules: JSONArray, val dns: JSONObject?, val skipped: List<String>)

    fun build(xray: JSONObject): Plan? {
        val routing = xray.optJSONObject("routing") ?: return null
        val xrules = routing.optJSONArray("rules") ?: return null
        val skipped = ArrayList<String>()
        val rules = JSONArray()
        for (i in 0 until xrules.length()) {
            val rule = xrules.optJSONObject(i) ?: continue
            convertRule(rule, skipped)?.let { rules.put(it) }
        }
        val dns = convertDns(xray.optJSONObject("dns"), skipped)
        if (rules.length() == 0 && dns == null) return null
        return Plan(rules, dns, skipped.distinct())
    }

    // ---------------------------------------------------------------- правила

    private fun convertRule(xray: JSONObject, skipped: MutableList<String>): JSONObject? {
        val target = xray.optString("outboundTag").trim()
        // Правило без цели бессмысленно; dns-out и block — это не outbound'ы.
        if (target.isBlank()) return null
        if (xray.has("inboundTag")) {
            skipped += "inboundTag"
            return null
        }
        if (target == "dns-out") {
            // В Xray это «отдать запрос своему DNS». В sing-box то же самое —
            // перехват DNS, поэтому порт 53 здесь уже не нужен.
            return JSONObject().put("action", "hijack-dns").put("protocol", "dns")
        }

        val rule = JSONObject()
        var matched = false

        if (domainMatchers(xray.optJSONArray("domain"), rule, skipped)) matched = true
        if (ipMatchers(xray.optJSONArray("ip"), rule, skipped)) matched = true

        val port = xray.opt("port")
        val portNumber = when (port) {
            is Int -> port
            is Number -> port.toInt()
            is String -> port.trim().toIntOrNull()
            else -> null
        }
        if (portNumber != null) {
            rule.put("port", portNumber)
            matched = true
        } else if (port != null) {
            skipped += "port:$port"
        }

        val protocols = protocols(xray.optJSONArray("protocol"), skipped)
        if (protocols.isNotEmpty()) {
            rule.put("protocol", JSONArray(protocols))
            matched = true
        }

        if (!matched) return null

        when (target) {
            "block" -> rule.put("action", "reject")
            "direct" -> rule.put("action", "route").put("outbound", "direct")
            "proxy" -> rule.put("action", "route").put("outbound", "proxy")
            else -> {
                // Неизвестный тег (в подписке бывают свои) — считаем прокси:
                // молча терять правило хуже, чем вести трафик в туннель.
                skipped += "outbound:$target"
                rule.put("action", "route").put("outbound", "proxy")
            }
        }
        return rule
    }

    /** `domain:` → суффикс, `full:` → точное, `keyword:`/`regexp:` как есть. */
    private fun domainMatchers(
        list: JSONArray?,
        rule: JSONObject,
        skipped: MutableList<String>,
    ): Boolean {
        if (list == null || list.length() == 0) return false
        val suffix = JSONArray()
        val exact = JSONArray()
        val keyword = JSONArray()
        val regex = JSONArray()
        for (i in 0 until list.length()) {
            val raw = list.optString(i).trim()
            if (raw.isEmpty()) continue
            when {
                raw.startsWith("domain:") -> suffix.put(raw.removePrefix("domain:"))
                raw.startsWith("full:") -> exact.put(raw.removePrefix("full:"))
                raw.startsWith("keyword:") -> keyword.put(raw.removePrefix("keyword:"))
                raw.startsWith("regexp:") -> regex.put(raw.removePrefix("regexp:"))
                raw.startsWith("geosite:") -> skipped += raw
                // Голое имя Xray трактует как домен с поддоменами.
                else -> suffix.put(raw)
            }
        }
        if (suffix.length() > 0) rule.put("domain_suffix", suffix)
        if (exact.length() > 0) rule.put("domain", exact)
        if (keyword.length() > 0) rule.put("domain_keyword", keyword)
        if (regex.length() > 0) rule.put("domain_regex", regex)
        // Ничего не перенесли (например, только geosite:) — правило не создаём,
        // иначе оно стало бы «поймать всё».
        return suffix.length() > 0 || exact.length() > 0 ||
            keyword.length() > 0 || regex.length() > 0
    }

    private fun ipMatchers(
        list: JSONArray?,
        rule: JSONObject,
        skipped: MutableList<String>,
    ): Boolean {
        if (list == null || list.length() == 0) return false
        val cidrs = JSONArray()
        var privateRanges = false
        for (i in 0 until list.length()) {
            val raw = list.optString(i).trim()
            if (raw.isEmpty()) continue
            when {
                raw == "geoip:private" -> privateRanges = true
                raw.startsWith("geoip:") -> skipped += raw
                raw.contains('/') -> cidrs.put(raw)
                else -> cidrs.put("$raw/32")
            }
        }
        if (privateRanges) rule.put("ip_is_private", true)
        if (cidrs.length() > 0) rule.put("ip_cidr", cidrs)
        // Только неконвертируемые geoip: — правила нет, иначе поймает весь трафик.
        return privateRanges || cidrs.length() > 0
    }

    private val KNOWN_PROTOCOLS = setOf(
        "http", "tls", "quic", "stun", "dns", "bittorrent", "dtls", "ssh", "rdp", "ntp",
    )

    private fun protocols(list: JSONArray?, skipped: MutableList<String>): List<String> {
        if (list == null) return emptyList()
        val out = ArrayList<String>(list.length())
        for (i in 0 until list.length()) {
            val p = list.optString(i).trim().lowercase()
            if (p.isEmpty()) continue
            if (p in KNOWN_PROTOCOLS) out += p else skipped += "protocol:$p"
        }
        return out
    }

    // -------------------------------------------------------------------- DNS

    private fun convertDns(dns: JSONObject?, skipped: MutableList<String>): JSONObject? {
        if (dns == null) return null
        val xservers = dns.optJSONArray("servers") ?: return null

        val servers = JSONArray()
        val rules = JSONArray()
        var finalTag: String? = null
        var index = 0

        for (i in 0 until xservers.length()) {
            val s = xservers.optJSONObject(i) ?: continue
            val address = s.optString("address").trim()
            if (address.isEmpty()) continue
            val tag = "sub-dns-" + (index + 1)
            val entry = serverEntry(address, tag, skipped)
            if (entry == null) continue
            index++
            servers.put(entry)

            val matchers = JSONObject()
            if (domainMatchers(s.optJSONArray("domains"), matchers, skipped)) {
                // Правило DNS: этим доменам — этот сервер.
                rules.put(JSONObject(matchers.toString()).put("server", tag))
            } else if (finalTag == null) {
                // Первый сервер без списка доменов — общий, он же final.
                finalTag = tag
            }
        }

        // Хосты из подписки — статические ответы (налог, служебные имена).
        val hosts = dns.optJSONObject("hosts")
        if (hosts != null && hosts.length() > 0) {
            val predefined = JSONObject()
            val domains = JSONArray()
            for (host in hosts.keys()) {
                val value = hosts.opt(host)?.toString()?.trim().orEmpty()
                if (value.isEmpty() || value.startsWith("geosite:") || value.startsWith("domain:")) {
                    skipped += "dns.hosts:$host"
                    continue
                }
                predefined.put(host, JSONArray().put(value))
                domains.put("full:$host")
            }
            if (predefined.length() > 0) {
                servers.put(
                    JSONObject()
                        .put("type", "hosts")
                        .put("tag", "sub-hosts")
                        .put("predefined", predefined)
                )
                val matchers = JSONObject()
                domainMatchers(domains, matchers, skipped)
                if (matchers.length() > 0) {
                    rules.put(JSONObject(matchers.toString()).put("server", "sub-hosts"))
                }
            }
        }

        if (servers.length() == 0) return null
        val out = JSONObject().put("servers", servers)
        if (rules.length() > 0) out.put("rules", rules)
        if (finalTag != null) out.put("final", finalTag)
        return out
    }

    /**
     * `https://` — DoH через туннель, `https+local://` — DoH мимо туннеля,
     * `tls://`, `tcp://` и голый адрес — соответствующие типы sing-box.
     */
    private fun serverEntry(
        address: String,
        tag: String,
        skipped: MutableList<String>,
    ): JSONObject? {
        fun hostPart(rest: String): String {
            val noPath = rest.substringBefore('/')
            val host = noPath.substringBefore(':')
            val port = noPath.substringAfter(':', "").toIntOrNull()
            return if (port != null && port != 0) "$host:$port" else host
        }

        fun pathPart(rest: String): String {
            val afterSlash = rest.substringAfter('/', "")
            return if (afterSlash.isEmpty()) "/dns-query" else "/$afterSlash"
        }

        return when {
            address.startsWith("https+local://") -> {
                val rest = address.removePrefix("https+local://")
                // Без detour — и это принципиально: sing-box не разрешает detour
                // на «пустой» direct ("detour to an empty direct outbound makes
                // no sense"), а сервер без detour и так резолвит мимо туннеля —
                // ровно как наш bootstrap.
                JSONObject().put("type", "https").put("tag", tag)
                    .put("server", hostPart(rest)).put("path", pathPart(rest))
            }
            address.startsWith("https://") -> {
                val rest = address.removePrefix("https://")
                JSONObject().put("type", "https").put("tag", tag)
                    .put("server", hostPart(rest)).put("path", pathPart(rest))
                    .put("detour", "proxy")
            }
            address.startsWith("tls://") -> JSONObject().put("type", "tls").put("tag", tag)
                .put("server", hostPart(address.removePrefix("tls://"))).put("detour", "proxy")
            address.startsWith("tcp://") -> JSONObject().put("type", "tcp").put("tag", tag)
                .put("server", hostPart(address.removePrefix("tcp://"))).put("detour", "proxy")
            address.contains("://") -> {
                // quic://, h3:// и прочее без прямого аналога — пропускаем.
                skipped += "dns:$address"
                null
            }
            else -> JSONObject().put("type", "udp").put("tag", tag).put("server", address)
        }
    }
}
