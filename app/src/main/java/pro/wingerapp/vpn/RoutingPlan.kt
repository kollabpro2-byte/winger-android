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
    class Plan(
        val rules: JSONArray,
        val dns: JSONObject?,
        val ruleSets: JSONArray?,
        val skipped: List<String>,
    )

    fun build(xray: JSONObject): Plan? {
        val routing = xray.optJSONObject("routing") ?: return null
        val xrules = routing.optJSONArray("rules") ?: return null
        val skipped = ArrayList<String>()
        val rules = JSONArray()
        val ruleSets = JSONArray()
        val seenSets = HashSet<String>()
        for (i in 0 until xrules.length()) {
            val rule = xrules.optJSONObject(i) ?: continue
            convertRule(rule, rules, ruleSets, seenSets, skipped)
        }
        val dns = convertDns(xray.optJSONObject("dns"), skipped)
        if (rules.length() == 0 && dns == null) return null
        return Plan(rules, dns, if (ruleSets.length() > 0) ruleSets else null, skipped.distinct())
    }

    // ---------------------------------------------------------------- правила

    private fun convertRule(
        xray: JSONObject,
        out: JSONArray,
        ruleSets: JSONArray,
        seenSets: MutableSet<String>,
        skipped: MutableList<String>,
    ) {
        val target = xray.optString("outboundTag").trim()
        // Правило без цели бессмысленно; dns-out и block — это не outbound'ы.
        if (target.isBlank()) return
        if (xray.has("inboundTag")) {
            skipped += "inboundTag"
            return
        }
        if (target == "dns-out") {
            // В Xray это «отдать запрос своему DNS». В sing-box то же самое —
            // перехват DNS, поэтому порт 53 здесь уже не нужен.
            out.put(JSONObject().put("action", "hijack-dns").put("protocol", "dns"))
            return
        }

        // Xray объединяет разные поля правила через ИЛИ («домен ИЛИ адрес»), а
        // sing-box внутри одного правила — через И. Поэтому каждую группу
        // проверок выпускаем отдельным правилом с тем же действием: получается
        // ровно та же логика.
        val domains = JSONObject()
        val sets = JSONArray()
        domainMatchers(xray.optJSONArray("domain"), domains, skipped, sets, ruleSets, seenSets)

        val ips = JSONObject()
        ipMatchers(xray.optJSONArray("ip"), ips, skipped, sets, ruleSets, seenSets)

        val ports = JSONArray()
        val port = xray.opt("port")
        val portNumber = when (port) {
            is Int -> port
            is Number -> port.toInt()
            is String -> port.trim().toIntOrNull()
            else -> null
        }
        if (portNumber != null) ports.put(portNumber) else if (port != null) skipped += "port:$port"

        val protos = protocols(xray.optJSONArray("protocol"), skipped)

        fun emit(rule: JSONObject) {
            if (rule.length() == 0) return
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
            out.put(rule)
        }

        emit(domains)
        if (sets.length() > 0) emit(JSONObject().put("rule_set", sets))
        emit(ips)
        if (ports.length() > 0) emit(JSONObject().put("port", ports))
        if (protos.isNotEmpty()) emit(JSONObject().put("protocol", JSONArray(protos)))
    }

    /**
     * Набор geosite/geoip из официального репозитория SagerNet. Ядро скачает его
     * при старте и положит в кэш (cache_file), так что повторные подключения
     * работают без сети. Только эти два репозитория и только формат .srs.
     */
    private fun ruleSetEntry(
        code: String,
        geoip: Boolean,
        ruleSets: JSONArray,
        seen: MutableSet<String>,
    ): String {
        val prefix = if (geoip) "geoip" else "geosite"
        val tag = "$prefix-$code"
        if (seen.add(tag)) {
            val repo = if (geoip) "sing-geoip" else "sing-geosite"
            ruleSets.put(
                JSONObject()
                    .put("type", "remote")
                    .put("tag", tag)
                    .put("format", "binary")
                    .put("url", "https://raw.githubusercontent.com/SagerNet/$repo/rule-set/$tag.srs")
                    .put("download_detour", "direct"),
            )
        }
        return tag
    }

    /** `domain:` → суффикс, `full:` → точное, `keyword:`/`regexp:` как есть. */
    private fun domainMatchers(
        list: JSONArray?,
        rule: JSONObject,
        skipped: MutableList<String>,
        sets: JSONArray? = null,
        ruleSets: JSONArray? = null,
        seen: MutableSet<String>? = null,
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
                // Набор geosite — это отдельное правило-ссылка, а не список
                // доменов: ядро скачает его при старте.
                raw.startsWith("geosite:") -> {
                    if (sets != null && ruleSets != null && seen != null) {
                        sets.put(ruleSetEntry(raw.removePrefix("geosite:"), false, ruleSets, seen))
                    } else {
                        skipped += raw
                    }
                }
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
        sets: JSONArray? = null,
        ruleSets: JSONArray? = null,
        seen: MutableSet<String>? = null,
    ): Boolean {
        if (list == null || list.length() == 0) return false
        val cidrs = JSONArray()
        var privateRanges = false
        for (i in 0 until list.length()) {
            val raw = list.optString(i).trim()
            if (raw.isEmpty()) continue
            when {
                raw == "geoip:private" -> privateRanges = true
                // geoip-наборы не переносим: коды в подписках бывают
                // нестандартными (`geoip:direct` в репозитории SagerNet нет —
                // 404), а ядро падает на любом отсутствующем наборе. Главное
                // (youtube, telegram, реклама, RU-домены) приходит через geosite.
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
        // DoH/DoT-серверу с доменным адресом ядро требует свой резолвер:
        // "missing domain resolver for domain server address". Даём bootstrap.
        var needsBootstrap = false

        for (i in 0 until xservers.length()) {
            val s = xservers.optJSONObject(i) ?: continue
            val address = s.optString("address").trim()
            if (address.isEmpty()) continue
            val tag = "sub-dns-" + (index + 1)
            val entry = serverEntry(address, tag, skipped)
            if (entry == null) continue
            index++
            val host = entry.optString("server")
            if (host.isNotBlank() && !isIpLiteralHost(host)) {
                entry.put("domain_resolver", "bootstrap")
                needsBootstrap = true
            }
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
        if (needsBootstrap) {
            // Резолвер для самих DoH-серверов: обычный UDP по IP, работает без DNS.
            servers.put(
                JSONObject()
                    .put("type", "udp")
                    .put("tag", "bootstrap")
                    .put("server", "1.1.1.1")
            )
        }
        val out = JSONObject().put("servers", servers)
        if (rules.length() > 0) out.put("rules", rules)
        if (finalTag != null) out.put("final", finalTag)
        return out
    }

    private fun isIpLiteralHost(host: String): Boolean =
        host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$""")) || host.contains(':')

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
