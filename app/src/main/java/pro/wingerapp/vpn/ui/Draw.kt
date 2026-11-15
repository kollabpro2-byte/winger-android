package pro.wingerapp.vpn.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Горизонтальная линия по нижнему краю области ровно в целое число экранных
 * пикселей.
 *
 * Зачем: `1.dp` при плотности 2.75 — это 2.75 px. Compose такую линию
 * сглаживает, и вместо разделителя получается серая полоска с размытыми
 * краями — на телефоне это читается как «нерезкая графика». Округлённая
 * толщина, прижатая к краю (а не к дробной середине), рисуется без
 * сглаживания — линия остаётся тонкой и чёткой на любой плотности.
 */
internal fun DrawScope.hairlineBottom(color: Color) {
    val stroke = 1.dp.toPx().roundToInt().coerceAtLeast(1).toFloat()
    val y = size.height - stroke / 2f
    drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
}
