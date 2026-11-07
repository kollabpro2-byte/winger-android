package pro.wingerapp.vpn

import org.json.JSONArray
import org.json.JSONObject

data class ServerNode(val name: String, val host: String, val port: Int)

data class SubConfig(val raw: String, val servers: List<ServerNode>)

/**
 * Happ/Remnawave style subscription: a JSON array of Xray configs, sometimes
 * base64-encoded. One entry carries hashed metadata in `meta`; we keep the array
 * as-is and pick a server by `remarks` later.
 */
fun readHapp(body: String): SubConfig {
    val text = body.trim()
    val source = when {
        text.startsWith("[") -> text
        text.startsWith("{") -> throw IllegalStateException("подписка пришла одним файлом, а не списком серверов")
        else -> {
            val decoded = runCatching {
                String(android.util.Base64.decode(text, android.util.Base64.DEFAULT))
            }.getOrNull()?.trim().orEmpty()
            if (decoded.startsWith("[")) decoded else throw IllegalStateException("не удалось прочитать список серверов")
        }
    }
    val arr = JSONArray(source)
    val servers = mutableListOf<ServerNode>()
    for (i in 0 until arr.length()) {
        val cfg = arr.optJSONObject(i) ?: continue
        val name = cfg.optString("remarks").ifBlank { "Сервер ${i + 1}" }
        val point = firstEndpoint(cfg)
        servers += ServerNode(name, point?.host.orEmpty(), point?.port ?: 443)
    }
    if (servers.isEmpty()) error("в подписке нет серверов")
    return SubConfig(body, servers)
}

/**
 * Picks one server out of the subscription and strips what the core does not need.
 *
 * Routing, DNS, mux and MTU are decided in [toSingBox] from [TunnelOptions]: the
 * Xray config is only a carrier for the outbound, so editing its routing here
 * would be silently ignored (that is what used to happen with every toggle in
 * the settings screen).
 */
fun xrayConfigFor(raw: String, server: String): String {
    val cfg = JSONObject(namedConfig(raw, server).toString())
    cfg.remove("remarks")
    cfg.remove("meta")
    return cfg.toString()
}

data class Endpoint(val host: String, val port: Int)

fun tcpEndpoints(raw: String, server: String): List<Endpoint> {
    val cfg = namedConfig(raw, server)
    val obs = cfg.optJSONArray("outbounds") ?: return emptyList()
    val found = mutableListOf<Endpoint>()
    for (i in 0 until obs.length()) {
        val outbound = obs.optJSONObject(i) ?: continue
        val proto = outbound.optString("protocol").lowercase()
        if (proto in setOf("freedom", "blackhole", "dns", "loopback", "")) continue
        // Whole outbound, not only settings: xhttp/splithttp still connect to address:port.
        val before = found.size
        collectEndpoints(outbound, found)
        if (found.size == before) found += looseEndpoints(outbound)
    }
    return found.distinct()
}

private fun namedConfig(raw: String, server: String): JSONObject {
    val arr = JSONArray(raw)
    val first = arr.optJSONObject(0) ?: JSONObject()
    for (i in 0 until arr.length()) {
        val c = arr.optJSONObject(i) ?: continue
        if (c.optString("remarks") == server) return c
    }
    return first
}

private fun jsonPort(node: JSONObject): Int {
    val raw = node.opt("port")
    return when (raw) {
        is Number -> raw.toInt()
        is String -> raw.trim().toIntOrNull() ?: 0
        else -> 0
    }
}

private fun endpointHost(node: JSONObject): String =
    node.optString("address").ifBlank { node.optString("host") }.ifBlank { node.optString("server") }

private fun usableHost(host: String): Boolean =
    host.isNotBlank() && host != "0.0.0.0" && host != "127.0.0.1" &&
        !host.contains("://") && !host.startsWith("geo") && !host.contains(" ")

private fun firstEndpoint(cfg: JSONObject): Endpoint? {
    val obs = cfg.optJSONArray("outbounds") ?: return null
    for (i in 0 until obs.length()) {
        val outbound = obs.optJSONObject(i) ?: continue
        val proto = outbound.optString("protocol").lowercase()
        if (proto in setOf("freedom", "blackhole", "dns", "loopback", "")) continue
        val found = mutableListOf<Endpoint>()
        collectEndpoints(outbound, found)
        if (found.isEmpty()) found += looseEndpoints(outbound)
        if (found.isNotEmpty()) return found.first()
    }
    return null
}

/** Address and port live on different objects (port only on the outbound). */
private fun looseEndpoints(outbound: JSONObject): List<Endpoint> {
    val hosts = mutableListOf<String>()
    val ports = mutableListOf<Int>()
    fun walk(node: Any?) {
        when (node) {
            is JSONObject -> {
                val host = endpointHost(node)
                if (usableHost(host)) hosts += host
                val port = jsonPort(node)
                if (port in 1..65535) ports += port
                val keys = node.keys()
                while (keys.hasNext()) walk(node.opt(keys.next()))
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
        }
    }
    walk(outbound)
    val port = ports.firstOrNull() ?: 443
    return hosts.distinct().map { Endpoint(it, port) }
}

private fun collectEndpoints(node: Any?, out: MutableList<Endpoint>) {
    when (node) {
        is JSONObject -> {
            val host = endpointHost(node)
            val port = jsonPort(node)
            if (usableHost(host) && port in 1..65535) {
                out += Endpoint(host, port)
            }
            val keys = node.keys()
            while (keys.hasNext()) collectEndpoints(node.opt(keys.next()), out)
        }
        is JSONArray -> for (i in 0 until node.length()) collectEndpoints(node.opt(i), out)
    }
}
