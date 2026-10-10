package ru.autoclicker.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import ru.autoclicker.data.Prefs
import ru.autoclicker.ui.MainActivity
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Всплывающая кнопка старт/стоп поверх других приложений.
 *
 * Показывается, когда кликер запущен, чтобы остановить его не возвращаясь
 * в приложение. Короткое касание — стоп (или старт, если уже остановлен),
 * перетаскивание — перемещение, долгое касание — открыть настройки.
 *
 * Настроек у неё намеренно нет: позиция запоминается, всё остальное фиксировано.
 */
class OverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: View? = null
    private var bubble: BubbleView? = null

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    fun show(running: Boolean) {
        if (!canDraw()) return
        if (root == null) attach()
        val view = root ?: return
        bubble?.setRunning(running)

        if (view.isAttachedToWindow) return
        runCatching {
            windowManager.addView(view, buildParams())
        }.onFailure {
            root = null
            bubble = null
        }
    }

    fun hide() {
        val view = root ?: return
        if (view.isAttachedToWindow) runCatching { windowManager.removeView(view) }
        root = null
        bubble = null
    }

    private fun attach() {
        if (root != null) return
        if (!canDraw()) return

        val density = context.resources.displayMetrics.density
        val container = FrameLayout(context)

        val view = BubbleView(context).apply {
            onTap = {
                if (ClickerState.running) ClickerAccessibilityService.stop()
                else ClickerAccessibilityService.startFromOverlay()
            }
            onLongTap = {
                runCatching {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
        bubble = view

        val caption = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 10f
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }

        container.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (14 * density).roundToInt() }
        )
        container.addView(
            caption,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
        )

        root = container
    }

    private fun buildParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val cfg = Prefs.load()
        val metrics = context.resources.displayMetrics
        val size = (BUBBLE_DP * metrics.density).roundToInt()

        val x = if (cfg.bubbleX >= 0) cfg.bubbleX else metrics.widthPixels - size - (16 * metrics.density).roundToInt()
        val y = if (cfg.bubbleY >= 0) cfg.bubbleY else metrics.heightPixels / 2

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    // --- сама кнопка ---

    @SuppressLint("ViewConstructor")
    private inner class BubbleView(context: Context) : View(context) {

        var onTap: (() -> Unit)? = null
        var onLongTap: (() -> Unit)? = null

        private var running = false

        private val density = resources.displayMetrics.density
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = Color.WHITE
        }
        private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 11f * density
        }

        private var dragging = false
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var longPressFired = false

        private val longPressRunnable = Runnable {
            longPressFired = true
            onLongTap?.invoke()
        }

        fun setRunning(value: Boolean) {
            running = value
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = (BUBBLE_DP * density).roundToInt()
            setMeasuredDimension(w, w + (14 * density).roundToInt())
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = (BUBBLE_DP * density) / 2f
            val r = ((BUBBLE_DP - 4) * density) / 2f

            fill.color = if (running) COLOR_RUNNING else COLOR_IDLE
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r - stroke.strokeWidth / 2f, stroke)

            // Иконка: ▶ когда остановлен, ■ когда работает
            val s = density
            if (running) {
                val left = cx - 4 * s
                val top = cy - 6 * s
                canvas.drawRect(left, top, left + 11 * s, top + 12 * s, icon)
            } else {
                val path = android.graphics.Path().apply {
                    moveTo(cx - 3.5f * s, cy - 6.5f * s)
                    lineTo(cx + 7 * s, cy)
                    lineTo(cx - 3.5f * s, cy + 6.5f * s)
                    close()
                }
                canvas.drawPath(path, icon)
            }

            val caption = if (running) "работает" else "стоп"
            canvas.drawText(caption, cx, height - 2 * density, text)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val params = root?.layoutParams as? WindowManager.LayoutParams

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params?.x ?: 0
                    startY = params?.y ?: 0
                    dragging = false
                    longPressFired = false
                    postDelayed(longPressRunnable, longPressMs)
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        // Начали тащить — отменяем и тап, и долгое нажатие
                        removeCallbacks(longPressRunnable)
                    }
                    if (dragging && params != null) {
                        params.x = startX + dx.roundToInt()
                        params.y = startY + dy.roundToInt()
                        runCatching { windowManager.updateViewLayout(root, params) }
                    }
                }

                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPressRunnable)
                    if (!dragging && !longPressFired) {
                        savePosition(params)
                        onTap?.invoke()
                    } else if (dragging) {
                        savePosition(params)
                    }
                }

                MotionEvent.ACTION_CANCEL -> removeCallbacks(longPressRunnable)
            }
            return true
        }

        private fun savePosition(params: WindowManager.LayoutParams?) {
            if (params == null) return
            val cfg = Prefs.load()
            Prefs.save(cfg.copy(bubbleX = params.x, bubbleY = params.y))
        }
    }

    companion object {
        private const val BUBBLE_DP = 56
        private val COLOR_RUNNING = Color.parseColor("#2DA44E")
        private val COLOR_IDLE = Color.parseColor("#57606A")
    }
}
