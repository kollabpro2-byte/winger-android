package pro.wingerapp.vpn.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Localised text for the whole UI.
 *
 * `stringResource` only works from a composable scope, but this app also builds
 * text inside click handlers and coroutines ("Подписка обновлена", error
 * messages, toasts). Screens therefore read [Resources] once and format through
 * this holder, which works in every scope and keeps a single translation point.
 */
@Composable
fun rememberStrings(): Strings = Strings(LocalContext.current.resources)

class Strings(private val res: Resources) {
    operator fun get(id: Int): String = res.getString(id)

    fun format(id: Int, vararg args: Any): String = res.getString(id, *args)

    /** Plurals: `quantity(R.plurals.days, 5)` → "5 дней" / "5 days". */
    fun quantity(id: Int, n: Long): String = res.getQuantityString(id, n.toInt(), n)

    /** Decimal number in the device locale: "1,2" in ru, "1.2" in en. */
    fun number(value: Double, decimals: Int = 1): String =
        String.format(Locale.getDefault(), "%.${decimals}f", value)
}
