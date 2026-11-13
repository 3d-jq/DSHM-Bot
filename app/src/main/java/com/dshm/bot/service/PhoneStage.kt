package com.dshm.bot.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.cos
import kotlin.math.min
import androidx.core.view.isVisible

/**
 * 手机操控时铺在屏幕上的舞台：
 *
 * - 边缘呼吸光晕（工作蓝 / 等待琥珀）；
 * - 一个电脑鼠标式指针，动作前移动到目标，点击时指针轻轻下压；
 * - 不再画瞄准环、动作文字或涟漪。
 *
 * 这层窗口不接触摸，手指和注入手势都能穿过去。
 */
object PhoneStage {
    enum class Mood { WORKING, WAITING }

    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var wm: WindowManager? = null
    private var view: StageView? = null

    fun show(service: Context) {
        context = service
        wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        onMain { ensure() }
    }

    fun hide() = onMain {
        view?.let { v -> runCatching { wm?.removeView(v) } }
        view = null
    }

    fun setVisible(visible: Boolean) = onMain {
        view?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    fun mood(m: Mood) = onMain { view?.mood = m }

    fun aim(x: Int, y: Int, @Suppress("UNUSED_PARAMETER") label: String) =
        onMain { view?.moveTo(x.toFloat(), y.toFloat()) }

    fun ripple(x: Int, y: Int) = onMain { view?.click(x.toFloat(), y.toFloat()) }

    fun sweep(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        @Suppress("UNUSED_PARAMETER") label: String,
    ) = sweep(x1, y1, x2, y2, 0L, 380L, label)

    fun sweep(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        delayMs: Long,
        durationMs: Long,
        @Suppress("UNUSED_PARAMETER") label: String,
    ) = onMain {
        view?.sweep(
            x1.toFloat(),
            y1.toFloat(),
            x2.toFloat(),
            y2.toFloat(),
            delayMs,
            durationMs,
        )
    }

    fun say(@Suppress("UNUSED_PARAMETER") label: String) = Unit

    fun hold(holdMs: Long) = onMain { view?.press(holdMs) }

    fun clear() = onMain { view?.clearCursor() }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching(block)
        } else {
            main.post { runCatching(block) }
        }
    }

    private fun ensure() {
        if (view != null) return
        val ctx = context ?: return
        val windowManager = wm ?: return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val stage = StageView(ctx)
        runCatching { windowManager.addView(stage, params) }.onFailure { return }
        view = stage
    }

    private class StageView(context: Context) : View(context) {
        var mood: Mood = Mood.WORKING
            set(value) {
                field = value
                postInvalidateOnAnimation()
            }

        private fun dp(v: Float): Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

        private val born = SystemClock.uptimeMillis()
        private val loc = IntArray(2)
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var glowShaders: Array<Shader>? = null
        private var glowW = 0
        private var glowH = 0
        private var glowColor = 0
        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pointerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        private val pointerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.4f)
            color = 0xE6000000.toInt()
            strokeJoin = Paint.Join.ROUND
        }
        private val pointerShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x33000000
        }
        private val pointer = Path()
        private val pointerScaled = Path()
        private val shadowPath = Path()

        private var cursorX = -1f
        private var cursorY = -1f
        private var fromX = -1f
        private var fromY = -1f
        private var toX = -1f
        private var toY = -1f
        private var moveStart = 0L
        private var moveDuration = 0L
        private var pressAt = 0L
        private var pressUntil = 0L

        init {
            pointer.moveTo(0f, 0f)
            pointer.lineTo(0f, dp(17f))
            pointer.lineTo(dp(4.6f), dp(12.7f))
            pointer.lineTo(dp(7.4f), dp(19.2f))
            pointer.lineTo(dp(10.6f), dp(17.8f))
            pointer.lineTo(dp(7.8f), dp(11.4f))
            pointer.lineTo(dp(13.4f), dp(10.7f))
            pointer.close()
        }

        fun moveTo(x: Float, y: Float) {
            if (cursorX < 0f) {
                cursorX = x
                cursorY = y
            }
            fromX = cursorX
            fromY = cursorY
            toX = x
            toY = y
            moveStart = SystemClock.uptimeMillis()
            moveDuration = 150L
            postInvalidateOnAnimation()
        }

        fun sweep(x1: Float, y1: Float, x2: Float, y2: Float, delay: Long, duration: Long) {
            cursorX = x1
            cursorY = y1
            fromX = x1
            fromY = y1
            toX = x2
            toY = y2
            moveStart = SystemClock.uptimeMillis() + delay
            moveDuration = duration.coerceAtLeast(120L)
            postInvalidateOnAnimation()
        }

        fun click(x: Float, y: Float) {
            cursorX = x
            cursorY = y
            fromX = x
            fromY = y
            toX = x
            toY = y
            moveDuration = 0L
            pressAt = SystemClock.uptimeMillis()
            pressUntil = pressAt + 160L
            postInvalidateOnAnimation()
        }

        fun press(holdMs: Long) {
            if (cursorX < 0f) return
            pressAt = SystemClock.uptimeMillis()
            pressUntil = pressAt + holdMs.coerceAtLeast(200L)
            postInvalidateOnAnimation()
        }

        fun clearCursor() {
            cursorX = -1f
            cursorY = -1f
            toX = -1f
            toY = -1f
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            val now = SystemClock.uptimeMillis()
            drawGlow(canvas, now)
            if (toX >= 0f) {
                if (moveDuration > 0L && now >= moveStart) {
                    val t = ((now - moveStart) / moveDuration.toFloat()).coerceIn(0f, 1f)
                    val eased = 1f - (1f - t) * (1f - t)
                    cursorX = fromX + (toX - fromX) * eased
                    cursorY = fromY + (toY - fromY) * eased
                    if (t >= 1f) moveDuration = 0L
                } else if (moveDuration == 0L) {
                    cursorX = toX
                    cursorY = toY
                }
            }
            if (cursorX >= 0f) {
                getLocationOnScreen(loc)
                drawPointer(canvas, cursorX - loc[0], cursorY - loc[1], now)
            }
            if (isVisible) postInvalidateDelayed(FRAME_MS)
        }

        private fun drawGlow(canvas: Canvas, now: Long) {
            val w = width
            val h = height
            if (w == 0 || h == 0) return
            val color = if (mood == Mood.WORKING) GLOW_WORKING else GLOW_WAITING
            if (glowShaders == null || glowW != w || glowH != h || glowColor != color) {
                val band = min(w, h) * GLOW_BAND
                val clear = color and 0x00FFFFFF
                val mid = band * 0.5f
                glowShaders = arrayOf(
                    LinearGradient(0f, 0f, mid, 0f, color, clear, Shader.TileMode.CLAMP),
                    LinearGradient(w.toFloat(), 0f, w - mid, 0f, color, clear, Shader.TileMode.CLAMP),
                    LinearGradient(0f, 0f, 0f, mid, color, clear, Shader.TileMode.CLAMP),
                    LinearGradient(0f, h.toFloat(), 0f, h - mid, color, clear, Shader.TileMode.CLAMP),
                )
                glowW = w
                glowH = h
                glowColor = color
            }
            val breathe = if (stillMotion) 0f else {
                val t = ((now - born) % GLOW_CYCLE_MS) / GLOW_CYCLE_MS.toFloat()
                (1f - cos(2.0 * Math.PI * t).toFloat()) / 2f
            }
            val scale = 1f + 0.05f * breathe
            glowPaint.alpha = (255 * (1f - 0.08f * breathe)).toInt()
            val shaders = glowShaders ?: return
            val band = min(w, h) * GLOW_BAND
            canvas.save()
            canvas.scale(scale, scale, w / 2f, h / 2f)
            glowPaint.shader = shaders[0]
            canvas.drawRect(0f, 0f, band, h.toFloat(), glowPaint)
            glowPaint.shader = shaders[1]
            canvas.drawRect(w - band, 0f, w.toFloat(), h.toFloat(), glowPaint)
            glowPaint.shader = shaders[2]
            canvas.drawRect(0f, 0f, w.toFloat(), band, glowPaint)
            glowPaint.shader = shaders[3]
            canvas.drawRect(0f, h - band, w.toFloat(), h.toFloat(), glowPaint)
            canvas.restore()
            glowPaint.shader = null
            edgePaint.color = color or 0xFF000000.toInt()
            edgePaint.alpha = (70 + 40 * breathe).toInt()
            val e = dp(1.5f)
            canvas.drawRect(0f, 0f, w.toFloat(), e, edgePaint)
            canvas.drawRect(0f, h - e, w.toFloat(), h.toFloat(), edgePaint)
            canvas.drawRect(0f, 0f, e, h.toFloat(), edgePaint)
            canvas.drawRect(w - e, 0f, w.toFloat(), h.toFloat(), edgePaint)
        }

        private fun drawPointer(canvas: Canvas, x: Float, y: Float, now: Long) {
            val pressed = now < pressUntil
            val scale = if (pressed) 0.86f else 1f
            val touched = if (pressed) {
                1f - ((now - pressAt).coerceAtLeast(0L) / 160f).coerceIn(0f, 1f)
            } else {
                0f
            }
            pointerScaled.set(pointer)
            pointerScaled.offset(x, y)
            canvas.save()
            canvas.scale(scale, scale, x, y)
            shadowPath.set(pointer)
            shadowPath.offset(x + dp(1.2f), y + dp(1.8f))
            pointerShadow.alpha = (0x33 + (0x30 * touched).toInt()).coerceAtMost(0xFF)
            canvas.drawPath(shadowPath, pointerShadow)
            canvas.drawPath(pointerScaled, pointerFill)
            canvas.drawPath(pointerScaled, pointerStroke)
            canvas.restore()
        }

        private val stillMotion: Boolean by lazy {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }

        private companion object {
            const val GLOW_WORKING = 0x2E0A66E4
            const val GLOW_WAITING = 0x36FFB000
            const val GLOW_BAND = 0.10f
            const val GLOW_CYCLE_MS = 4800L
            const val FRAME_MS = 16L
        }
    }
}
