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

    /** Restore the persisted flag after a process restart. */
    fun sync(ctx: Context) {
        _running.value = Store.running(ctx)
    }

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
