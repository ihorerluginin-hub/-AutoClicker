package ru.autoclicker.service

/** Точка внутри одного штриха. tMs — смещение от начала штриха. */
data class MacroPoint(val x: Int, val y: Int, val tMs: Long)

/**
 * Один штрих записи: палец вниз → движения → палец вверх.
 * При проигрывании превращается в один Path и отправляется одним жестом.
 */
data class MacroStroke(val points: List<MacroPoint>) {
    val durationMs: Long get() = (points.last().tMs - points.first().tMs).coerceAtLeast(1)
}

/** Вся запись — список штрихов. Пустой список означает «ничего не записано». */
data class Macro(val strokes: List<MacroStroke>) {
    val pointCount: Int get() = strokes.sumOf { it.points.size }

    val durationMs: Long
        get() = strokes.sumOf { it.durationMs }

    companion object {
        val EMPTY = Macro(emptyList())
    }
}
