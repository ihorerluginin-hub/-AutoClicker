package ru.autoclicker.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Настройки автокликера. Сознательно всего четыре поля:
 * точка, скорость, число итераций. Остальное убрано.
 *
 * speedX — множитель скорости относительно «эталонной»:
 *   клик в точку: пауза BASE_INTERVAL_MS / speedX между кликами
 *   проигрывание записи: длительность каждого штриха делится на speedX
 */
data class ClickerConfig(
    val pointX: Int = 0,
    val pointY: Int = 0,
    val speedX: Float = 1.0f,
    val iterations: Int = 0,          // 0 = бесконечно
    /** Позиция всплывающей кнопки. -1 — ещё не задана, ставится по умолчанию. */
    val bubbleX: Int = -1,
    val bubbleY: Int = -1,
) {

    fun normalized(): ClickerConfig = copy(
        pointX = pointX.coerceAtLeast(0),
        pointY = pointY.coerceAtLeast(0),
        speedX = speedX.coerceIn(SPEED_MIN, SPEED_MAX),
        iterations = iterations.coerceIn(ITERATIONS_MIN, ITERATIONS_MAX),
    )

    /** Пауза между кликами в фиксированной точке с учётом скорости. */
    fun clickIntervalMs(): Long =
        (BASE_INTERVAL_MS / speedX).toLong().coerceAtLeast(MIN_INTERVAL_MS)

    /** Длительность одного нажатия с учётом скорости. */
    fun tapDurationMs(): Long =
        (BASE_TAP_MS / speedX).toLong().coerceAtLeast(MIN_TAP_MS)

    fun save(out: SharedPreferences.Editor) {
        out.putInt("pointX", pointX)
        out.putInt("pointY", pointY)
        out.putFloat("speedX", speedX)
        out.putInt("iterations", iterations)
        out.putInt("bubbleX", bubbleX)
        out.putInt("bubbleY", bubbleY)
    }

    companion object {
        const val SPEED_MIN = 0.5f
        const val SPEED_MAX = 5.0f
        const val SPEED_STEP = 0.1f
        const val ITERATIONS_MIN = 0
        const val ITERATIONS_MAX = 10_000

        /** 1.0x = один клик в 500 мс. */
        const val BASE_INTERVAL_MS = 500f
        const val BASE_TAP_MS = 40f

        /** Android душит частые жесты, ниже этого опускаться бессмысленно. */
        const val MIN_INTERVAL_MS = 20L
        const val MIN_TAP_MS = 8L
    }
}

/** Типобезопасный доступ к SharedPreferences. */
object Prefs {

    private const val NAME = "autoclicker"

    @Volatile
    private var sp: SharedPreferences? = null

    fun init(context: Context) {
        if (sp == null) {
            synchronized(this) {
                if (sp == null) {
                    sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                }
            }
        }
    }

    private fun require(): SharedPreferences =
        sp ?: throw IllegalStateException("Prefs.init() не вызван — проверь AutoClickerApp")

    fun load(): ClickerConfig {
        val p = require()
        return ClickerConfig(
            pointX = p.getInt("pointX", 0),
            pointY = p.getInt("pointY", 0),
            speedX = p.getFloat("speedX", 1.0f),
            iterations = p.getInt("iterations", 0),
            bubbleX = p.getInt("bubbleX", -1),
            bubbleY = p.getInt("bubbleY", -1),
        ).normalized()
    }

    fun save(config: ClickerConfig) {
        val editor = require().edit()
        config.normalized().save(editor)
        editor.apply()
    }

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        require().registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        runCatching { require().unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
