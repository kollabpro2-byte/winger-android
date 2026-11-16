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

/** Пауза перед замером пингов, чтобы старт не конкурировал с сетью за кадры. */
private const val PING_SETTLE_MS = 350L

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
     * Данные пришли из кэша, а сети ещё не было. Нужен, чтобы интерфейс знал:
     * список может быть устаревшим, и обновление сейчас в пути.
     */
    val fromCache: Boolean = false,
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

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    /**
     * Готовность к показу интерфейса. Пока false — показываем заставку.
     * Сессия лежит в зашифрованном файле, а его чтение поднимает Keystore
     * (100–300 мс), поэтому проверка идёт в фоне, а не в onCreate.
     */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _state = MutableStateFlow(SubscriptionState())
    val state: StateFlow<SubscriptionState> = _state.asStateFlow()

    private var deviceId = ""

    /** Обновлялись ли уже из сети в этой сессии (кэш не считается). */
    private var refreshed = false

    /**
     * Взводится в [signedIn] и гасится после первой успешной загрузки подписки:
     * именно в этот момент приложение выбирает «Автопилот» и включается само.
     */
    private var autoConnectAfterLogin = false

    init {
        // A 401 from the panel means the cookie expired: show the login screen
        // instead of claiming the account has no subscription.
        Api.onUnauthorized = { _signedIn.value = false }
        bootstrap()
    }

    /**
     * Первый шаг после запуска: читаем сессию из зашифрованного файла и переносим
     * настройки в обычный. Всё это в фоне — Keystore на главном потоке давал
     * заметную задержку первого кадра.
     */
    private fun bootstrap() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            withContext(Dispatchers.IO) {
                runCatching { Api.init(ctx) }
                runCatching { Store.migrateSettingsIntoPlain(ctx) }
            }
            _signedIn.value = withContext(Dispatchers.IO) { runCatching { Api.hasSession() }.getOrDefault(false) }
            // Пока сеть отвечает, показываем прошлую подписку из кэша: главная
            // открывается сразу со списком серверов, а не с пустым экраном.
            if (_signedIn.value) loadCache(ctx)
            _ready.value = true
        }
    }

    private suspend fun loadCache(ctx: Application) {
        val cached = withContext(Dispatchers.IO) { runCatching { SubCache.load(ctx) }.getOrNull() } ?: return
        val nodes = withContext(Dispatchers.IO) { runCatching { readHapp(cached.raw) }.getOrNull() }
        val servers = nodes?.servers.orEmpty()
        val picked = cached.selected.takeIf { name -> servers.any { it.name == name } }
            ?: servers.firstOrNull()?.name.orEmpty()
        _state.update {
            it.copy(
                plan = cached.plan,
                raw = cached.raw,
                servers = servers,
                selected = picked,
                loading = false,
                fromCache = true,
            )
        }
        VpnLog.add("подписка из кэша: ${servers.size} серверов")
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
        // Кэш мог уже наполнить состояние, но сеть всё равно нужна: проверяем,
        // не обновлялись ли мы уже в этой сессии.
        if (refreshed) return
        refreshed = true
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
        refreshed = false
        runCatching { SubCache.clear(getApplication()) }
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
                    fromCache = false,
                    notice = if (ping) it.notice else fresh,
                    noticeSeq = if (ping) it.noticeSeq else it.noticeSeq + 1,
                )
            }
            // Сохраняем успешную подписку: при следующем запуске список серверов
            // появится сразу, ещё до ответа сети.
            withContext(Dispatchers.IO) {
                val snapshot = _state.value
                if (snapshot.raw.isNotBlank()) {
                    runCatching { SubCache.save(getApplication(), result.plan, snapshot.raw, snapshot.selected) }
                }
            }
            if (ping) {
                result.nodes?.let {
                    // Замер пингов — это N параллельных TCP-подключений. На старте
                    // они конкурировали с первым кадром и скроллом, поэтому даём
                    // интерфейсу устояться и только потом меряем.
                    delay(PING_SETTLE_MS)
                    pingAll(it)
                }
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
        // Собираем все замеры и обновляем состояние ОДНИМ изменением: раньше каждая
        // нода писала в StateFlow отдельно, и список серверов перерисовывался
        // столько раз, сколько в нём узлов.
        val fresh = coroutineScope {
            parsed.servers.map { node ->
                async(Dispatchers.IO) {
                    val points = tcpEndpoints(parsed.raw, node.name).ifEmpty {
                        listOf(Endpoint(node.host, if (node.port in 1..65535) node.port else 443))
                    }.filter { it.host.isNotBlank() && it.port in 1..65535 }
                    val ms = if (points.isEmpty()) -1
                    else points.map { point -> tcpPing(point.host, point.port) }.filterNotNull().minOrNull()
                    node.name to ms
                }
            }.awaitAll()
        }.toMap()
        _state.update { it.copy(pings = it.pings + fresh) }
    }

    private fun str(id: Int): String = getApplication<Application>().getString(id)

    private data class Loaded(val plan: Sub, val nodes: SubConfig?)
}
