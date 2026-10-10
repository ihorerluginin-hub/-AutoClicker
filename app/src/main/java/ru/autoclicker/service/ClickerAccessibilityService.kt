package ru.autoclicker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.TouchInteractionController
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import ru.autoclicker.data.ClickerConfig
import ru.autoclicker.data.Prefs
import kotlin.math.max

/**
 * Служба доступности: кликает в точку и проигрывает запись экрана.
 *
 * Режимы: IDLE, CLICKING, PLAYING, RECORDING — взаимно исключающие.
 *
 * Механика отправки. Клик в точку работает по таймеру: жесты короткие и
 * независимые, а при отказе dispatchGesture мы просто пробуем ещё раз через
 * интервал. Проигрывание записи идёт цепочкой — следующий штрих ждёт
 * колбэка предыдущего, иначе система начнёт отклонять запросы.
 *
 * Сторожевой таймер общий: если колбэк не пришёл вовсе (бывает на части
 * прошивок), он снимает зависшее состояние, чтобы цикл не встал навсегда.
 */
class ClickerAccessibilityService : AccessibilityService() {

    private enum class Mode { IDLE, CLICKING, PLAYING, RECORDING }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler

    private var config: ClickerConfig = ClickerConfig()
    private var mode = Mode.IDLE

    private var gestureInFlight = false

    // --- клик в точку ---
    private var clicksDone = 0L

    // --- проигрывание записи ---
    private var strokeIndex = 0
    private var iteration = 0

    // --- запись ---
    private var recording = Macro.EMPTY
    private var currentStroke = mutableListOf<MacroPoint>()
    private var strokeStartMs = 0L

    private var vibrator: Vibrator? = null
    private var overlay: OverlayController? = null

    // --- энергосбережение ---
    private var touchExplorationEnabled = false
    private var baseFlags = 0

    // --- наблюдение за касаниями (Android 13+) ---
    private var touchController: TouchInteractionController? = null
    private var touchCallback: TouchInteractionController.Callback? = null

    // --- остановка после исчерпания итераций ---
    private var stopReason: String = ""

    // --- состояние для UI ---
    var recordedMacro: Macro = Macro.EMPTY
        private set

    // --- жизненный цикл ---

    override fun onServiceConnected() {
        super.onServiceConnected()
        thread = HandlerThread("autoclicker-loop").also { it.start() }
        handler = Handler(thread.looper)
        vibrator = obtainVibrator()
        Prefs.init(applicationContext)
        reloadConfig()
        baseFlags = serviceInfo?.flags ?: 0
        instance = this
        Log.i(TAG, "Служба подключена")
    }

    override fun onDestroy() {
        stopAll("Служба выключена")
        overlay?.hide()
        overlay = null
        if (::thread.isInitialized) thread.quitSafely()
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        stopAll("Прервано системой")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopAll("Служба отвязана")
        return super.onUnbind(intent)
    }

    /**
     * Оба способа наблюдать за сырыми касаниями на этом устройстве
     * (TECNO KL8, MediaTek, Android 14) молчат: и onMotionEvent при
     * touch exploration, и TouchInteractionController возвращают ноль
     * событий, хотя и флаг, и capability, и регистрация применяются.
     * Проверено замерами, не догадками.
     *
     * Поэтому пишем через onAccessibilityEvent — эти события система
     * доставляет всегда. Записываются нажатия, без протяжек.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (mode != Mode.RECORDING) return
        val ev = event ?: return

        if (ev.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return

        // Первые доли секунды игнорируем: это нажатие на «ЗАПИСАТЬ ЭКРАН»,
        // которым запись только что началась.
        if (SystemClock.uptimeMillis() < recordFromUptime) return

        // У AccessibilityEvent в API 35 нет ни getX(), ни getY(), ни
        // getLocationOnScreen() — их убрали. Берём центр рамки узла.
        val rect = Rect()
        val node = runCatching { ev.source }.getOrNull()
        val got = node != null && runCatching { node.getBoundsInScreen(rect); true }.getOrDefault(false)
        if (!got || rect.isEmpty) {
            Log.i(TAG, "нажатие без координат, пропущено")
            return
        }
        addTap(rect.centerX(), rect.centerY())
    }

    /** Нажатие записываем как штрих из двух точек — воспроизведёт��я как тап. */
    private fun addTap(x: Int, y: Int) {
        val dx = x.coerceAtLeast(0)
        val dy = y.coerceAtLeast(0)
        val stroke = MacroStroke(
            listOf(
                MacroPoint(dx, dy, 0L),
                MacroPoint(dx, dy, TAP_STROKE_MS),
            )
        )
        recording = Macro(recording.strokes + stroke)
        touchEventCount++
        notifyUi()
        Log.i(TAG, "нажатие $dx,$dy (всего ${recording.strokes.size})")
    }

    private var recordFromUptime = 0L

    // --- запись экрана ---

    /**
     * Держим касания под наблюдением, не забирая их.
     *
     * Раньше для этого включался FLAG_REQUEST_TOUCH_EXPLORATION_MODE и
     * читался onMotionEvent — но touch exploration перехватывает весь ввод
     * на устройстве, и второй тап по «ЗАПИСАТЬ ЭКРАН» до кнопки не доходил:
     * запись нельзя было остановить.
     *
     * TouchInteractionController (с Android 13) решает это — события
     * приходят наблюдателю, а приложения продолжают получать их как обычно.
     * На старых версиях остаётся старый путь, но там запись снимается
     * автостопом, чтобы не зависнуть.
     */
    private fun attachTouchObserver(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return runCatching {
            val controller = getTouchInteractionController(Display.DEFAULT_DISPLAY)
            val callback = object : TouchInteractionController.Callback {
                override fun onMotionEvent(event: MotionEvent) {
                    touchEventCount++
                    if (mode != Mode.RECORDING) return
                    if (event.source == InputDevice.SOURCE_TOUCHSCREEN) {
                        handleTouch(event)
                    }
                }

                override fun onStateChanged(state: Int) {
                    Log.i(TAG, "состояние=${TouchInteractionController.stateToString(state)}")
                }
            }
            touchController = controller
            touchCallback = callback
            controller.registerCallback(mainExecutor, callback)
            touchEventCount = 0
            Log.i(TAG, "наблюдатель зарегистрирован, display=${controller.displayId}")
            true
        }.getOrElse {
            // Откат на touch exploration тут недопустим: он перехватывает весь
            // ввод, и кнопки приложения перестают нажиматься. Запись нажатий
            // через onAccessibilityEvent работает и без него.
            Log.w(TAG, "наблюдение за движениями недоступно, пишем только нажатия", it)
            false
        }
    }

    private var touchEventCount = 0

    private fun detachTouchObserver() {
        val cb = touchCallback
        if (cb != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Метод существует только с Android 13, ниже наблюдатель не ставился.
            runCatching { touchController?.unregisterCallback(cb) }
                .onFailure { Log.w(TAG, "Не удалось снять наблюдателя", it) }
        }
        touchCallback = null
        touchController = null
        enableTouchExploration(false)
    }

    /**
     * Запасной стоп для режима touch exploration: там кнопки недоступны,
     * поэтому запись обязана закрыться сама.
     */
    private fun armRecordingSafetyTimer() {
        val token = Runnable { stopRecording("Автостоп записи") }
        recordingGuard = token
        handler.postDelayed(token, RECORDING_MAX_MS)
    }

    private var recordingGuard: Runnable? = null

    private fun cancelRecordingGuard() {
        recordingGuard?.let { handler.removeCallbacks(it) }
        recordingGuard = null
    }

    private var skipCurrentGesture = false

    private fun handleTouch(event: MotionEvent) {
        if (skipCurrentGesture) {
            // Касание, которым пользователь нажал «ЗАПИСАТЬ ЭКРАН», записывать
            // не нужно — иначе воспроизведение будет тыкать в эту кнопку.
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                skipCurrentGesture = false
            }
            return
        }
        recordTouch(event)
    }

    /**
     * Путь через touch exploration. Имя метода — onMotionEvent, а не
     * onTouchEvent: публичного onTouchEvent у AccessibilityService нет.
     */
    override fun onMotionEvent(event: MotionEvent) {
        if (mode != Mode.RECORDING) return

        // Если источник не тачскрин, координаты в событии недостоверны —
        // приводим к тачскрину копией события.
        val ev = if (event.source == InputDevice.SOURCE_TOUCHSCREEN) event else MotionEvent.obtain(event)
        try {
            if (skipCurrentGesture) {
                if (ev.actionMasked == MotionEvent.ACTION_UP ||
                    ev.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    skipCurrentGesture = false
                }
            } else {
                recordTouch(ev)
            }
        } finally {
            if (ev !== event) ev.recycle()
        }
    }

    private fun recordTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                strokeStartMs = SystemClock.uptimeMillis()
                currentStroke = mutableListOf()
                appendPoint(event, 0)
            }

            MotionEvent.ACTION_MOVE -> {
                // Движений много, а жест всё равно один — прореживаем,
                // иначе Path раздувается и жест получается рваным.
                for (i in 0 until event.pointerCount) {
                    if (shouldSample(event.getX(i).toInt(), event.getY(i).toInt())) {
                        appendPoint(event, i)
                    }
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> appendPoint(event, event.actionIndex)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> appendPoint(event, event.actionIndex)

            MotionEvent.ACTION_CANCEL -> Unit
        }
    }

    private fun appendPoint(event: MotionEvent, index: Int) {
        val t = SystemClock.uptimeMillis() - strokeStartMs
        currentStroke.add(
            MacroPoint(
                x = event.getX(index).toInt().coerceAtLeast(0),
                y = event.getY(index).toInt().coerceAtLeast(0),
                tMs = t,
            )
        )
        if (event.actionMasked == MotionEvent.ACTION_UP && currentStroke.size >= MIN_STROKE_POINTS) {
            recording = Macro(recording.strokes + MacroStroke(currentStroke.toList()))
            currentStroke = mutableListOf()
            // Обновляем счётчик сразу, а не только по окончании записи —
            // иначе экран выглядит пустым всё время письма.
            notifyUi()
        }
    }

    private var lastSampleX = Int.MIN_VALUE
    private var lastSampleY = Int.MIN_VALUE

    /** Точку берём, только если она отошла дальше SAMPLE_DISTANCE_PX. */
    private fun shouldSample(x: Int, y: Int): Boolean {
        val dx = x - lastSampleX
        val dy = y - lastSampleY
        if (dx * dx + dy * dy >= SAMPLE_DISTANCE_PX * SAMPLE_DISTANCE_PX) {
            lastSampleX = x
            lastSampleY = y
            return true
        }
        return false
    }

    fun startRecording() {
        if (mode == Mode.CLICKING || mode == Mode.PLAYING) return
        stopLoop("Запись начата")
        recording = Macro.EMPTY
        currentStroke = mutableListOf()
        lastSampleX = Int.MIN_VALUE
        lastSampleY = Int.MIN_VALUE
        skipCurrentGesture = true
        mode = Mode.RECORDING
        // Пауза, чтобы не записать нажатие на саму кнопку «ЗАПИСАТЬ ЭКРАН»
        recordFromUptime = SystemClock.uptimeMillis() + RECORD_SELF_TAP_GUARD_MS
        val observing = attachTouchObserver()
        if (!observing) armRecordingSafetyTimer()
        notifyUi()
        Log.i(TAG, "Запись пошла")
    }

    fun stopRecording(reason: String = "Запись остановлена") {
        if (mode != Mode.RECORDING) return
        cancelRecordingGuard()
        detachTouchObserver()
        // Незакрытый штрих (палец остался на экране) тоже сохраняем.
        if (currentStroke.size >= MIN_STROKE_POINTS) {
            recording = Macro(recording.strokes + MacroStroke(currentStroke.toList()))
        }
        currentStroke = mutableListOf()
        skipCurrentGesture = false
        recordedMacro = recording
        mode = Mode.IDLE
        stopReason = reason
        notifyUi()
        Log.i(TAG, "$reason: ${recordedMacro.pointCount} точек, событий принято $touchEventCount")
    }

    fun clearRecording() {
        recordedMacro = Macro.EMPTY
        recording = Macro.EMPTY
        currentStroke = mutableListOf()
        notifyUi()
    }

    private fun enableTouchExploration(on: Boolean) {
        if (touchExplorationEnabled == on) return
        touchExplorationEnabled = on
        val info = serviceInfo ?: return
        val flags = if (on) {
            baseFlags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        } else {
            baseFlags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
        }
        // Копирующего конструктора у AccessibilityServiceInfo в API нет,
        // поэтому меняем флаги на существующем экземпляре и переустанавливаем.
        runCatching {
            info.flags = flags
            serviceInfo = info
        }.onFailure { Log.w(TAG, "Не удалось сменить touch exploration", it) }
    }

    // --- управление ---

    fun startClicking() {
        if (mode != Mode.IDLE) return
        reloadConfig()
        stopReason = ""
        clicksDone = 0L
        mode = Mode.CLICKING
        showOverlay(true)
        KeepAlive.start(applicationContext)
        notifyUi()
        handler.post({ nextClick() })
    }

    fun startPlayback() {
        if (mode != Mode.IDLE) return
        if (recordedMacro.strokes.isEmpty()) {
            stopReason = "Запись пустая"
            notifyUi()
            return
        }
        reloadConfig()
        stopReason = ""
        strokeIndex = 0
        iteration = 0
        mode = Mode.PLAYING
        showOverlay(true)
        KeepAlive.start(applicationContext)
        notifyUi()
        handler.post({ nextStroke() })
    }

    /** Старт с всплывающей кнопки: точка или запись — как на главном экране. */
    fun startFromOverlay() {
        if (mode != Mode.IDLE) return
        if (recordedMacro.strokes.isNotEmpty()) startPlayback() else startClicking()
    }

    private fun showOverlay(running: Boolean) {
        val ctrl = overlay ?: OverlayController(applicationContext).also { overlay = it }
        ctrl.show(running)
    }

    fun stopAll(reason: String) {
        if (mode == Mode.IDLE) return
        val wasRecording = mode == Mode.RECORDING
        if (wasRecording) {
            if (currentStroke.size >= MIN_STROKE_POINTS) {
                recording = Macro(recording.strokes + MacroStroke(currentStroke.toList()))
            }
            currentStroke = mutableListOf()
            recordedMacro = recording
        }
        mode = Mode.IDLE
        stopReason = reason
        gestureInFlight = false
        handler.removeCallbacksAndMessages(null)
        cancelRecordingGuard()
        detachTouchObserver()
        skipCurrentGesture = false
        // Кнопку не убираем: она нужна, чтобы запустить снова из любого приложения.
        showOverlay(false)
        KeepAlive.stop(applicationContext)
        notifyUi()
        Log.i(TAG, "Стоп ($reason)")
    }

    private fun stopLoop(reason: String) {
        if (mode == Mode.IDLE) return
        mode = Mode.IDLE
        stopReason = reason
        handler.removeCallbacksAndMessages(null)
        KeepAlive.stop(applicationContext)
        notifyUi()
    }

    // --- клик в точку ---

    private fun nextClick() {
        if (mode != Mode.CLICKING) return

        if (config.iterations in 1..clicksDone) {
            stopAll("Готово: $clicksDone кликов")
            return
        }

        val path = Path().apply { moveTo(config.pointX.toFloat(), config.pointY.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, config.tapDurationMs()))
            .build()

        if (!dispatch(gesture)) {
            handler.postDelayed({ nextClick() }, config.clickIntervalMs())
            return
        }
        handler.postDelayed({ nextClick() }, config.clickIntervalMs())
    }

    private fun onClickDone() {
        clicksDone++
        buzz(12)
        notifyUi()
    }

    // --- проигрывание записи ---

    private fun nextStroke() {
        if (mode != Mode.PLAYING) return

        if (strokeIndex >= recordedMacro.strokes.size) {
            strokeIndex = 0
            iteration++
            notifyUi()
            if (config.iterations in 1..iteration) {
                stopAll("Готово: $iteration повторов")
                return
            }
            // Пауза между повторами, чтобы записи не слипались в кашу.
            handler.postDelayed({ nextStroke() }, GAP_BETWEEN_ITERATIONS_MS)
            return
        }

        val stroke = recordedMacro.strokes[strokeIndex]
        val path = Path()
        stroke.points.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(p.x.toFloat(), p.y.toFloat()) else path.lineTo(p.x.toFloat(), p.y.toFloat())
        }

        val duration = max((stroke.durationMs / config.speedX).toLong(), MIN_STROKE_MS)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build()

        if (!dispatch(gesture)) {
            // Не приняли — этот штрих пропускаем, иначе запись встанет.
            Log.w(TAG, "Жест не принят, штрих пропущен")
            strokeIndex++
            handler.post({ nextStroke() })
            return
        }
        strokeIndex++
    }

    private fun onStrokeDone() {
        buzz(8)
        notifyUi()
        handler.post({ nextStroke() })
    }

    // --- отправка жеста ---

    private fun dispatch(gesture: GestureDescription): Boolean {
        gestureInFlight = true
        val accepted = runCatching {
            dispatchGesture(gesture, resultCallback, handler)
        }.getOrDefault(false)
        if (!accepted) gestureInFlight = false
        armWatchdog()
        return accepted
    }

    private val resultCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) = settle(true)
        override fun onCancelled(gestureDescription: GestureDescription?) = settle(false)
    }

    private fun settle(completed: Boolean) {
        if (mode == Mode.IDLE || mode == Mode.RECORDING) return
        gestureInFlight = false
        watchdog?.let { handler.removeCallbacks(it) }
        if (!completed) return

        when (mode) {
            Mode.CLICKING -> onClickDone()
            Mode.PLAYING -> onStrokeDone()
            else -> Unit
        }
    }

    private var watchdog: Runnable? = null

    private fun armWatchdog() {
        val token = Runnable {
            if (gestureInFlight) {
                gestureInFlight = false
                Log.w(TAG, "Сторож снял зависший жест")
                // Один зависший жест не должен убить цикл.
                handler.postDelayed({
                    if (mode == Mode.CLICKING) nextClick() else if (mode == Mode.PLAYING) nextStroke()
                }, config.clickIntervalMs())
            }
        }
        watchdog = token
        handler.postDelayed(token, GESTURE_WATCHDOG_MS)
    }

    // --- тактильный отклик ---

    private fun buzz(ms: Long) {
        runCatching {
            vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun obtainVibrator(): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()

    // --- конфиг и уведомление UI ---

    private fun reloadConfig() {
        config = Prefs.load()
    }

    private fun notifyUi() {
        ClickerState.update(
            running = mode == Mode.CLICKING || mode == Mode.PLAYING,
            recording = mode == Mode.RECORDING,
            clicks = clicksDone,
            iterationsDone = iteration,
            strokes = recordedMacro.pointCount,
            rawEvents = touchEventCount,
            reason = stopReason,
        )
    }

    fun currentConfig(): ClickerConfig {
        reloadConfig()
        return config
    }

    companion object {
        private const val TAG = "AutoClicker"
        private const val MIN_STROKE_POINTS = 2
        private const val SAMPLE_DISTANCE_PX = 12
        private const val MIN_STROKE_MS = 40L
        private const val GESTURE_WATCHDOG_MS = 3_000L
        private const val GAP_BETWEEN_ITERATIONS_MS = 150L

        /** Потолок для записи, страховка от зависания. */
        private const val RECORDING_MAX_MS = 120_000L

        /** Длительность штриха-нажатия. */
        private const val TAP_STROKE_MS = 30L

        /** Пауза после старта записи, чтобы не поймать нажатие на саму кнопку. */
        private const val RECORD_SELF_TAP_GUARD_MS = 600L

        @Volatile
        private var instance: ClickerAccessibilityService? = null

        val isEnabled: Boolean get() = instance != null

        fun startClicking() { instance?.startClicking() }

        fun startPlayback() { instance?.startPlayback() }

        fun startFromOverlay() { instance?.startFromOverlay() }

        fun stop() { instance?.stopAll("Остановлен вручную") }

        fun startRecording() { instance?.startRecording() }

        fun stopRecording() { instance?.stopRecording() }

        fun clearRecording() { instance?.clearRecording() }

        fun macro(): Macro = instance?.recordedMacro ?: Macro.EMPTY

        fun config(): ClickerConfig =
            instance?.currentConfig() ?: (runCatching { Prefs.load() }.getOrDefault(ClickerConfig()))
    }
}

/** Запуск/остановка фонового сервиса, который держит процесс живым. */
internal object KeepAlive {
    fun start(ctx: Context) {
        runCatching {
            ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java))
        }.onFailure { Log.w("AutoClicker", "Не удалось поднять KeepAlive", it) }
    }

    fun stop(ctx: Context) {
        runCatching {
            ctx.startService(
                Intent(ctx, KeepAliveService::class.java).setAction(KeepAliveService.ACTION_STOP)
            )
        }.onFailure { Log.w("AutoClicker", "Не удалось остановить KeepAlive", it) }
    }
}
