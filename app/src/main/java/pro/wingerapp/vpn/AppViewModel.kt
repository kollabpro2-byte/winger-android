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

/** Имя узла, который выбирается автоматически после входа (v87 макета). */
private const val AUTOPILOT_NAME = "Автопилот"

/**
 * Какой сервер выбрать сразу после входа: «Автопилот», если он есть в подписке,
 * иначе первый в списке. Вынесено отдельной функцией, чтобы поведение можно
 * было закрепить тестом без Android-окружения.
 */
internal fun pickAutoServer(names: List<String>): String =
    names.firstOrNull { it.contains(AUTOPILOT_NAME, ignoreCase = true) }
        ?: names.firstOrNull()
        ?: ""

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
    /**
     * Номер события «подписка обновлена». Растёт на каждой успешной загрузке:
     * по нему экран запускает ОДИН таймлайн для текста и вспышки кнопки.
     * Строка для этого не годится — повторное обновление присылает тот же текст,
     * и Compose не перезапустил бы анимацию.
     */
    val noticeSeq: Int = 0,
    /**
     * «Включись сам»: выставляется один раз — когда после входа загрузилась
     * подписка и сервер уже выбран. Экран забирает флаг через [AppViewModel.consumeAutoConnect].
     */
    val autoConnect: Boolean = false,
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

    /**
     * Взводится в [signedIn] и гасится после первой успешной загрузки подписки:
     * именно в этот момент приложение выбирает «Автопилот» и включается само.
     */
    private var autoConnectAfterLogin = false

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
        // Первый вход в сессии: после загрузки подписки включимся автоматически.
        autoConnectAfterLogin = true
    }

    fun logout() {
        Api.logout()
        autoConnectAfterLogin = false
        _signedIn.value = false
        _state.value = SubscriptionState()
    }

    /** Экран забрал запрос «включись сам» — второй раз он не сработает. */
    fun consumeAutoConnect() {
        _state.update { if (it.autoConnect) it.copy(autoConnect = false) else it }
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
            // Автозапуск после входа: подписка только что загрузилась — выбираем
            // «Автопилот» (если такого узла в подписке нет — первый сервер) и
            // просим экран включиться. Пока данные грузились, кнопка стояла
            // выключенной и без орла: подключаться было нечем.
            val autoNow = autoConnectAfterLogin && !TunnelState.running.value
            autoConnectAfterLogin = false
            _state.update {
                val list = result.nodes?.servers ?: it.servers
                val picked = when {
                    autoNow -> pickAutoServer(list.map { node -> node.name })
                    else -> it.selected.ifBlank { list.firstOrNull()?.name.orEmpty() }
                }
                it.copy(
                    plan = result.plan,
                    raw = result.nodes?.raw ?: it.raw,
                    servers = list,
                    selected = picked,
                    autoConnect = autoNow && picked.isNotBlank(),
                    loading = false,
                    error = null,
                    notice = if (ping) it.notice else fresh,
                    noticeSeq = if (ping) it.noticeSeq else it.noticeSeq + 1,
                )
            }
            if (ping) {
                result.nodes?.let { pingAll(it) }
            } else {
                // Текст держим в состоянии, пока идёт вся анимация (3.0 с):
                // плавность задаёт экран, а здесь важно не стереть строку
                // раньше времени. Затем гасим — уже с нулевой прозрачностью.
                delay(3000)
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
