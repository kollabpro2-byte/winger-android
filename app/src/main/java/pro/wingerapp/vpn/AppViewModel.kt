package pro.wingerapp.vpn

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.wingerapp.vpn.net.Api
import pro.wingerapp.vpn.net.Sub

/** Everything the home screen knows about the account and its subscription. */
data class SubscriptionState(
    val plan: Sub? = null,
    val raw: String = "",
    val servers: List<ServerNode> = emptyList(),
    val selected: String = "",
    val pings: Map<String, Int?> = emptyMap(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val pinging: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    val connectable: Boolean get() = raw.isNotBlank() && selected.isNotBlank()
}

/**
 * Holds the state that used to live inside the composables: session, the
 * subscription, the selected server and the latency probes.
 *
 * Screens keep only view state (current tab, open dialogs, the stopwatch), so a
 * configuration change no longer throws away a freshly loaded subscription and
 * the network logic sits outside the UI layer.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val _signedIn = MutableStateFlow(Api.hasSession())
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _state = MutableStateFlow(SubscriptionState())
    val state: StateFlow<SubscriptionState> = _state.asStateFlow()

    private var deviceId = ""

    init {
        // A 401 from the panel means the cookie expired: show the login screen
        // instead of claiming the account has no subscription.
        Api.onUnauthorized = { _signedIn.value = false }
    }

    override fun onCleared() {
        Api.onUnauthorized = null
        super.onCleared()
    }

    /** Remembers how this device identifies itself, without touching the network. */
    fun attachDevice(hwid: String) {
        deviceId = hwid
    }

    /** Loads everything for this device once; later network changes call [refresh]. */
    fun start(hwid: String) {
        attachDevice(hwid)
        if (_state.value.raw.isNotBlank()) return
        refresh(ping = true)
    }

    fun signedIn() {
        _signedIn.value = true
    }

    fun logout() {
        Api.logout()
        _signedIn.value = false
        _state.value = SubscriptionState()
    }

    fun select(name: String) {
        _state.update { it.copy(selected = name) }
    }

    fun fail(message: String) {
        _state.update { it.copy(error = message) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** Xray JSON of [name] (the selected server by default), null when unknown. */
    fun config(name: String = _state.value.selected): String? {
        val raw = _state.value.raw
        if (raw.isBlank() || name.isBlank()) return null
        return runCatching { xrayConfigFor(raw, name) }.getOrNull()
    }

    suspend fun signIn(user: String, pass: String): String? = withContext(Dispatchers.IO) {
        runCatching { Api.login(user, pass) }.getOrElse { it.message ?: "" }
    }

    suspend fun signUp(user: String, pass: String): String? = withContext(Dispatchers.IO) {
        runCatching { Api.register(user, pass, deviceId) }.getOrElse { it.message ?: "" }
    }

    /** Reloads the subscription; [ping] also probes every server. */
    fun refresh(ping: Boolean = false) {
        if (deviceId.isBlank()) return
        viewModelScope.launch {
            _state.update {
                it.copy(pinging = ping, refreshing = !ping, pings = if (ping) emptyMap() else it.pings)
            }
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val plan = Api.subscription() ?: return@runCatching null
                    val nodes = if (plan.url.isNotBlank()) readHapp(Api.fetchHapp(plan.url, deviceId)) else null
                    Loaded(plan, nodes)
                }
            }
            val result = loaded.getOrNull()
            if (result == null) {
                val message = loaded.exceptionOrNull()?.message
                    ?.lineSequence()?.firstOrNull()?.take(140)?.ifBlank { null }
                    ?: str(R.string.home_subscription_failed)
                _state.update { it.copy(loading = false, pinging = false, refreshing = false, error = message) }
                delay(4000)
                _state.update { if (it.error == message) it.copy(error = null) else it }
                return@launch
            }
            val fresh = str(R.string.home_subscription_updated)
            _state.update {
                it.copy(
                    plan = result.plan,
                    raw = result.nodes?.raw ?: it.raw,
                    servers = result.nodes?.servers ?: it.servers,
                    selected = it.selected.ifBlank { result.nodes?.servers?.firstOrNull()?.name.orEmpty() },
                    loading = false,
                    error = null,
                    notice = if (ping) it.notice else fresh,
                )
            }
            if (ping) {
                result.nodes?.let { pingAll(it) }
            } else {
                delay(2600)
                _state.update { if (it.notice == fresh) it.copy(notice = null) else it }
            }
            _state.update { it.copy(pinging = false, refreshing = false) }
        }
    }

    private suspend fun pingAll(parsed: SubConfig) {
        coroutineScope {
            parsed.servers.map { node ->
                async(Dispatchers.IO) {
                    val points = tcpEndpoints(parsed.raw, node.name).ifEmpty {
                        listOf(Endpoint(node.host, if (node.port in 1..65535) node.port else 443))
                    }.filter { it.host.isNotBlank() && it.port in 1..65535 }
                    val ms = if (points.isEmpty()) -1
                    else points.map { point -> tcpPing(point.host, point.port) }.filterNotNull().minOrNull()
                    _state.update { it.copy(pings = it.pings + (node.name to ms)) }
                }
            }.awaitAll()
        }
    }

    private fun str(id: Int): String = getApplication<Application>().getString(id)

    private data class Loaded(val plan: Sub, val nodes: SubConfig?)
}
