package ru.autoclicker.ui

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ru.autoclicker.R
import ru.autoclicker.data.ClickerConfig
import ru.autoclicker.data.Prefs
import ru.autoclicker.service.ClickerAccessibilityService
import ru.autoclicker.service.ClickerState
import kotlin.math.roundToInt

/**
 * Весь интерфейс: три кнопки и две шкалы.
 *
 * СТАРТ           — кликает в заданную точку
 * ЗАПИСАТЬ КООРДИНАТУ — тыкаешь по экрану, точка запоминается
 * ЗАПИСАТЬ ЭКРАН   — пишет движения, потом СТАРТ их повторяет
 *
 * Если запись непустая, СТАРТ проигрывает её; иначе кликает в точку.
 */
class MainActivity : AppCompatActivity() {

    private var pickMode = false

    private val stateListener: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(applicationContext)
        setContentView(R.layout.activity_main)

        val btnRun = findViewById<Button>(R.id.btnRun)
        btnRun.setOnClickListener { onStartClicked() }
        // Долгое нажатие — принудительно клик в точку. Иначе после записи
        // экрана обычное нажатие всегда уходит в проигрывание записи,
        // и режим точки становится недоступен.
        btnRun.setOnLongClickListener {
            if (accessibilityEnabled()) ClickerAccessibilityService.startClicking()
            render()
            true
        }
        findViewById<Button>(R.id.btnRecord).setOnClickListener { enterPickMode() }
        findViewById<Button>(R.id.btnRecordScreen).setOnClickListener { onRecordScreenClicked() }
        findViewById<Button>(R.id.btnClearMacro).setOnClickListener {
            ClickerAccessibilityService.clearRecording()
            render()
        }
        // Кнопка выдачи оверлея нужна только пока разрешения нет
        findViewById<Button>(R.id.btnGrantOverlay).setOnClickListener { requestOverlay() }

        setupSpeed()
        setupIterations()
    }

    override fun onResume() {
        super.onResume()
        ClickerState.addListener(stateListener)
        render()
    }

    override fun onPause() {
        ClickerState.removeListener(stateListener)
        super.onPause()
    }

    // --- шкала скорости ---

    private fun setupSpeed() {
        val seek = findViewById<SeekBar>(R.id.seekSpeed)
        val steps = ((ClickerConfig.SPEED_MAX - ClickerConfig.SPEED_MIN) / ClickerConfig.SPEED_STEP).roundToInt()
        seek.max = steps

        val current = Prefs.load().speedX
        seek.progress = ((current - ClickerConfig.SPEED_MIN) / ClickerConfig.SPEED_STEP).roundToInt()
        updateSpeedLabel(seek.progress)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSpeedLabel(progress)
                if (fromUser) {
                    val cfg = Prefs.load()
                    Prefs.save(
                        cfg.copy(speedX = ClickerConfig.SPEED_MIN + progress * ClickerConfig.SPEED_STEP)
                    )
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
    }

    private fun updateSpeedLabel(progress: Int) {
        val speed = ClickerConfig.SPEED_MIN + progress * ClickerConfig.SPEED_STEP
        findViewById<TextView>(R.id.tvSpeed).text = getString(R.string.speed_format, speed)
    }

    // --- выбор итераций ---

    /**
     * Шкала нелинейная: низ — шаги по 1, верх — по 500.
     * Иначе до 1000 итераций пришлось бы тянуть полосу в два раза длиннее.
     */
    private fun setupIterations() {
        val seek = findViewById<SeekBar>(R.id.seekIterations)
        seek.max = ITER_STEPS

        val current = Prefs.load().iterations
        seek.progress = iterationsToProgress(current)
        updateIterationsLabel(seek.progress)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateIterationsLabel(progress)
                if (fromUser) {
                    val cfg = Prefs.load()
                    Prefs.save(cfg.copy(iterations = progressToIterations(progress)))
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
    }

    private fun updateIterationsLabel(progress: Int) {
        val iterations = progressToIterations(progress)
        findViewById<TextView>(R.id.tvIterations).text = if (iterations == 0) {
            getString(R.string.iterations_infinite)
        } else {
            getString(R.string.iterations_format, iterations)
        }
    }

    private fun progressToIterations(progress: Int): Int = when {
        progress == 0 -> 0
        progress <= FINE_STEPS -> progress
        else -> FINE_STEPS + (progress - FINE_STEPS) * COARSE_STEP
    }

    private fun iterationsToProgress(iterations: Int): Int = when {
        iterations <= 0 -> 0
        iterations <= FINE_STEPS -> iterations
        else -> FINE_STEPS + (iterations - FINE_STEPS) / COARSE_STEP
    }.coerceIn(0, ITER_STEPS)

    // --- кнопки ---

    private fun onStartClicked() {
        if (!accessibilityEnabled()) {
            openAccessibilitySettings()
            return
        }
        if (ClickerState.running) {
            ClickerAccessibilityService.stop()
        } else if (ClickerAccessibilityService.macro().strokes.isNotEmpty()) {
            ClickerAccessibilityService.startPlayback()
        } else {
            ClickerAccessibilityService.startClicking()
        }
        render()
    }

    private fun onRecordScreenClicked() {
        if (!accessibilityEnabled()) {
            openAccessibilitySettings()
            return
        }
        if (ClickerState.recording) ClickerAccessibilityService.stopRecording()
        else ClickerAccessibilityService.startRecording()
        render()
    }

    // --- выбор точки касания ---

    private fun enterPickMode() {
        pickMode = true
        setTheme(R.style.Theme_AutoClicker_Pick)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val hint = TextView(this).apply {
            text = getString(R.string.pick_hint)
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundColor(0xAA000000.toInt())
            setPadding(32, 64, 32, 64)
        }
        setContentView(
            hint,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!pickMode) return super.onTouchEvent(event)
        if (event.actionMasked != MotionEvent.ACTION_UP) return true

        val cfg = Prefs.load()
        Prefs.save(cfg.copy(pointX = event.rawX.toInt(), pointY = event.rawY.toInt()))
        pickMode = false
        recreate()
        return true
    }

    // --- отрисовка ---

    private fun render() {
        val cfg = Prefs.load()
        val enabled = accessibilityEnabled()
        val macro = ClickerAccessibilityService.macro()

        findViewById<TextView>(R.id.tvService).text = when {
            !enabled -> getString(R.string.no_service)
            !Settings.canDrawOverlays(this) -> getString(R.string.no_overlay)
            else -> getString(R.string.service_on)
        }

        val btnRun = findViewById<Button>(R.id.btnRun)
        btnRun.text = getString(if (ClickerState.running) R.string.btn_stop else R.string.btn_start)

        findViewById<Button>(R.id.btnRecordScreen).text =
            getString(if (ClickerState.recording) R.string.btn_recording else R.string.btn_record_screen)

        findViewById<TextView>(R.id.tvPoint).text = if (cfg.pointX == 0 && cfg.pointY == 0) {
            getString(R.string.point_not_set)
        } else {
            getString(R.string.point_format, cfg.pointX, cfg.pointY)
        }

        findViewById<TextView>(R.id.tvMacro).text =
            getString(R.string.macro_format, macro.pointCount / 2)

        findViewById<TextView>(R.id.tvCounter).text =
            getString(R.string.counter_idle, ClickerState.clicks, ClickerState.iterationsDone)

        val grant = findViewById<Button>(R.id.btnGrantOverlay)
        val needOverlay = enabled && !Settings.canDrawOverlays(this)
        grant.visibility = if (needOverlay) android.view.View.VISIBLE else android.view.View.GONE

        findViewById<TextView>(R.id.tvStatus).text = when {
            ClickerState.recording -> getString(R.string.status_recording)
            ClickerState.running -> getString(R.string.status_running)
            !enabled -> getString(R.string.no_service)
            ClickerState.reason.isNotBlank() -> ClickerState.reason
            macro.strokes.isNotEmpty() -> getString(R.string.status_macro_ready)
            else -> getString(R.string.status_idle)
        }
    }

    // --- доступность ---

    private fun accessibilityEnabled(): Boolean {
        val expected = ComponentName(this, ClickerAccessibilityService::class.java)
        val flat = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return flat.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun requestOverlay() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
        }
    }

    companion object {
        private const val FINE_STEPS = 50        // 0..50 итераций по единице
        private const val COARSE_STEP = 500      // дальше по 500
        private const val ITER_STEPS = 70        // 50 + 20 * 500 = 10 000
    }
}
