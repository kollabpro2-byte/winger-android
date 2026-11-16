package pro.wingerapp.vpn

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One source of truth for the tunnel state: the service publishes, the UI collects.
 * This is what keeps "подключено" from appearing before the core is actually up
 * (the VPN permission dialog can be denied, the core can fail to start).
 */
object TunnelState {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Жив ли наш сервис туннеля в этом процессе. Система может убить сервис,
     * не вызвав `onDestroy` (HyperOS так делает при выключении экрана), и тогда
     * сохранённый флаг остаётся `true` — интерфейс показывал «включено», хотя
     * значка VPN уже нет. Проверяем реальность, а не только флаг.
     */
    @Volatile
    var serviceAlive: Boolean = false

    /** Идёт восстановление туннеля после того, как систему его убила. */
    @Volatile
    var restoring: Boolean = false

    /** Restore the persisted flag after a process restart, but only if the service is alive. */
    fun sync(ctx: Context) {
        val persisted = Store.running(ctx)
        if (persisted && !serviceAlive) {
            // Флаг устарел: сервиса нет. Сбрасываем, чтобы не врать пользователю.
            Store.running(ctx, false)
            _running.value = false
            return
        }
        _running.value = persisted
    }

    /** Устаревший флаг «включено»: сервиса нет, но в настройках записано обратное. */
    fun looksStale(ctx: Context): Boolean = Store.running(ctx) && !serviceAlive

    fun on() {
        _error.value = null
        _running.value = true
    }

    fun off() {
        _running.value = false
    }

    fun fail(message: String?) {
        _running.value = false
        _error.value = message?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun clearError() {
        _error.value = null
    }
}
