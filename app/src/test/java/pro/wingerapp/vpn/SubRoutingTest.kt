package pro.wingerapp.vpn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Роутинг и DNS из подписки (формат Xray/Remnawave) должны доезжать до sing-box.
 * Раньше эти блоки просто игнорировались — отсюда «настройки не подтягиваются».
 */
class SubRoutingTest {

    private fun config(rules: String, dns: String = "null"): JSONObject = JSONObject(
        """
        {
          "outbounds": [
            {
              "tag": "proxy", "protocol": "vless",
              "settings": { "vnext": [{ "address": "1.2.3.4", "port": 443,
                "users": [{ "id": "uuid-1", "flow": "", "encryption": "none" }] }] },
              "streamSettings": { "network": "tcp", "security": "none" }
            }
          ],
          "routing": { "rules": $rules },
          "dns": $dns
        }
        """.trimIndent()
    )

    private fun plan(rules: String, dns: String = "null") = RoutingPlan.build(config(rules, dns))

    @Test
    fun domainPrefixesMapToSingBoxMatchers() {
        val p = plan(
            """
            [
              { "domain": ["domain:ozon.ru", "full:lkfl2.nalog.ru", "keyword:ads", "regexp:^ad\\."],
                "outboundTag": "direct" },
              { "domain": ["geosite:category-ads", "domain:yandex.ru"], "outboundTag": "block" }
            ]
            """
        )
        assertNotNull(p)
        val rules = p!!.rules
        assertEquals(2, rules.length())

        val first = rules.getJSONObject(0)
        assertEquals("route", first.getString("action"))
        assertEquals("direct", first.getString("outbound"))
        assertEquals("ozon.ru", first.getJSONArray("domain_suffix").getString(0))
        assertEquals("lkfl2.nalog.ru", first.getJSONArray("domain").getString(0))
        assertEquals("ads", first.getJSONArray("domain_keyword").getString(0))
        assertEquals(1, first.getJSONArray("domain_regex").length())

        val second = rules.getJSONObject(1)
        assertEquals("reject", second.getString("action"))
        assertEquals(1, second.getJSONArray("domain_suffix").length())

        // geosite без rule-set перенести нечем — он честно попадает в пропущенные.
        assertTrue(p.skipped.any { it.startsWith("geosite:") })
    }

    @Test
    fun geoipPrivateBecomesIpIsPrivateAndBlockBecomesReject() {
        val p = plan(
            """
            [
              { "ip": ["geoip:private", "77.88.8.8/32"], "outboundTag": "direct" },
              { "protocol": ["bittorrent"], "outboundTag": "block" },
              { "ip": ["geoip:ru"], "outboundTag": "direct" }
            ]
            """
        )!!
        val first = p.rules.getJSONObject(0)
        assertTrue(first.getBoolean("ip_is_private"))
        assertEquals("77.88.8.8/32", first.getJSONArray("ip_cidr").getString(0))

        val second = p.rules.getJSONObject(1)
        assertEquals("reject", second.getString("action"))
        assertEquals("bittorrent", second.getJSONArray("protocol").getString(0))

        // geoip:ru без rule-set — пропущен.
        assertEquals(2, p.rules.length())
        assertTrue(p.skipped.contains("geoip:ru"))
    }

    @Test
    fun dnsOutBecomesHijackAndInboundRulesAreSkipped() {
        val p = plan(
            """
            [
              { "port": 53, "outboundTag": "dns-out" },
              { "inboundTag": ["dns-in"], "outboundTag": "dns-out" }
            ]
            """
        )!!
        assertEquals(1, p.rules.length())
        val rule = p.rules.getJSONObject(0)
        assertEquals("hijack-dns", rule.getString("action"))
        assertTrue(p.skipped.contains("inboundTag"))
    }

    @Test
    fun dnsServersMapWithDetourAndDomains() {
        val p = plan(
            "[]",
            """
            {
              "hosts": { "lkfl2.nalog.ru": "213.24.64.175" },
              "servers": [
                { "tag": "dns-proxy", "address": "https://8.8.8.8/dns-query",
                  "domains": ["domain:habr.com"] },
                { "tag": "dns-direct", "address": "https+local://common.dot.dns.yandex.net/dns-query",
                  "domains": ["domain:ozon.ru"] },
                { "tag": "dns-proxy", "address": "https://1.1.1.1/dns-query" }
              ]
            }
            """
        )!!
        val dns = p.dns!!
        val servers = dns.getJSONArray("servers")
        val entries = (0 until servers.length()).map { servers.getJSONObject(it) }
        assertTrue(entries.any { it.optString("tag") == "sub-hosts" })

        val proxyDoh = entries.first { it.optString("server") == "8.8.8.8" }
        assertEquals("https", proxyDoh.getString("type"))
        assertEquals("proxy", proxyDoh.getString("detour"))
        assertEquals("/dns-query", proxyDoh.getString("path"))

        val directDoh = entries.first { it.optString("server") == "common.dot.dns.yandex.net" }
        // Без detour: ядро отвечает "detour to an empty direct outbound makes no
        // sense", а сервер без detour и так идёт мимо туннеля.
        assertFalse(directDoh.has("detour"))
        // Доменный адрес DoH без domain_resolver валит старт ядра:
        // "missing domain resolver for domain server address".
        assertEquals("bootstrap", directDoh.getString("domain_resolver"))
        assertTrue(entries.any { it.optString("tag") == "bootstrap" })

        val rules = dns.getJSONArray("rules")
        // habr → DoH через прокси, ozon → DoH мимо, nalog → hosts.
        assertEquals(3, rules.length())
        assertTrue(dns.has("final"))
    }

    @Test
    fun subscriptionRoutingReachesSingBoxAndCanBeDisabled() {
        val json = config(
            """[ { "domain": ["domain:ozon.ru"], "outboundTag": "direct" } ]""",
            """{ "servers": [ { "tag": "dns-proxy", "address": "https://8.8.8.8/dns-query" } ] }"""
        ).toString()

        val withSub = JSONObject(toSingBox(json, TunnelOptions(subRouting = RoutingPlan.RULES_AND_DNS)))
        assertEquals("sub-dns-1", withSub.getJSONObject("dns").getString("final"))
        assertTrue(hasSuffixRule(withSub, "ozon.ru", "direct"))

        val without = JSONObject(toSingBox(json, TunnelOptions(subRouting = RoutingPlan.OFF)))
        assertEquals("remote", without.getJSONObject("dns").getString("final"))
        assertFalse(hasSuffixRule(without, "ozon.ru", "direct"))
    }

    @Test
    fun rulesOnlyModeKeepsOurDns() {
        val json = config(
            """[ { "domain": ["domain:ozon.ru"], "outboundTag": "direct" } ]""",
            """{ "servers": [ { "tag": "dns-proxy", "address": "https://8.8.8.8/dns-query" } ] }"""
        ).toString()
        val sb = JSONObject(toSingBox(json, TunnelOptions(subRouting = RoutingPlan.RULES)))
        assertEquals("remote", sb.getJSONObject("dns").getString("final"))
        assertTrue(hasSuffixRule(sb, "ozon.ru", "direct"))
    }

    private fun hasSuffixRule(config: JSONObject, suffix: String, outbound: String): Boolean {
        val rules = config.getJSONObject("route").getJSONArray("rules")
        for (i in 0 until rules.length()) {
            val r = rules.getJSONObject(i)
            val list = r.optJSONArray("domain_suffix") ?: continue
            for (j in 0 until list.length()) {
                if (list.optString(j) == suffix) {
                    return r.optString("outbound") == outbound
                }
            }
        }
        return false
    }
}
