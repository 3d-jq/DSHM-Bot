package com.dshm.bot.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 手机操控时铺在屏幕上的一层「舞台」，移植 DeepSearch 的 `HandsStage`：
 *
 * - **四周跑马灯**：四条边缘光带，2.4 秒亮、2.4 秒暗地呼吸；工作时蓝色，等用户时琥珀色；
 * - **瞄准环**：模型选中的位置先亮一圈柔和光晕，再画虚线环、青色旋转弧和中心点；
 * - **落地涟漪**：点按时落点扩散两圈；长按填一圈；滑动画出虚线路径、彗尾与箭头；
 * - **动作胶囊**：动作名写在深色小胶囊里，带青蓝竖条，靠近屏幕边缘时自动换边；
 * - **截图隐藏**：截图瞬间整层不可见，模型永远看不到自己的浮层。
 *
 * 这层窗口 `FLAG_NOT_TOUCHABLE`：不接任何触摸，手指和注入的手势都能穿过去。
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
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { ensure() }
            return
        }
        val latch = CountDownLatch(1)
        main.post {
            runCatching { ensure() }
            latch.countDown()
        }
        latch.await(500, TimeUnit.MILLISECONDS)
    }

    fun hide() = onMain {
        view?.let { v -> runCatching { wm?.removeView(v) } }
        view = null
    }

    /** 截图时整层隐藏，并清掉上一动作的瞄准标记。 */
    fun setVisible(visible: Boolean) = onMain {
        val v = view ?: return@onMain
        if (!visible) v.clearMarks()
        v.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    fun mood(m: Mood) = onMain { view?.mood = m }

    /** 瞄准某个点：[label] 是动作名（点按 / 滚动 …）。 */
    fun aim(x: Int, y: Int, label: String) = onMain { view?.aim(x.toFloat(), y.toFloat(), label) }

    /** 长按：第二个环在 [holdMs] 内填满。 */
    fun hold(holdMs: Long) = onMain { view?.hold(holdMs) }

    /** 手指落下。 */
    fun ripple(x: Int, y: Int) = onMain { view?.ripple(x.toFloat(), y.toFloat()) }

    /** 滑动：环从 (x1,y1) 走到 (x2,y2)。 */
    fun sweep(x1: Int, y1: Int, x2: Int, y2: Int, label: String) =
        sweep(x1, y1, x2, y2, 0L, 380L, label)

    fun sweep(x1: Int, y1: Int, x2: Int, y2: Int, delayMs: Long, durationMs: Long, label: String) = onMain {
        view?.sweep(
            x1.toFloat(),
            y1.toFloat(),
            x2.toFloat(),
            y2.toFloat(),
            delayMs,
            durationMs,
            label,
        )
    }

    /** 没有落点的动作（输入、按键）：在上一处位置写动作名。 */
    fun say(label: String) = onMain { view?.say(label) }

    fun clear() = onMain { view?.clearMarks() }

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

        private fun sp(v: Float): Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

        private val born = SystemClock.uptimeMillis()
        private val loc = IntArray(2)

        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var glowW = 0
        private var glowH = 0
        private var glowColor = 0
        private var glowShaders: Array<Shader>? = null
        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = dp(2.5f)
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = dp(3f)
        }
        private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = dp(2.5f)
        }
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var haloShader: RadialGradient? = null
        private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CHIP_BG }
        private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = 0x1AFFFFFF
        }
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = sp(12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val arc = RectF()
        private val chipRect = RectF()
        private val arrow = Path()
        private val dashPath = Path()

        private class Mark(val x: Float, val y: Float, val label: String, val at: Long) {
            var holdMs: Long = 0L
            var holdFrom: Long = 0L
        }

        private class Ripple(val x: Float, val y: Float, val at: Long)
        private class Sweep(
            val x1: Float,
            val y1: Float,
            val x2: Float,
            val y2: Float,
            val at: Long,
            val delay: Long,
            val duration: Long,
            val label: String,
        )

        private class Chip(val text: String, val x: Float, val y: Float, val at: Long)

        private var mark: Mark? = null
        private val ripples = ArrayList<Ripple>(6)
        private var sweep: Sweep? = null
        private var chip: Chip? = null
        private var lastX = -1f
        private var lastY = -1f

        fun aim(x: Float, y: Float, label: String) {
            mark = Mark(x, y, label, SystemClock.uptimeMillis())
            sweep = null
            chip = null
            lastX = x
            lastY = y
            postInvalidateOnAnimation()
        }

        fun hold(holdMs: Long) {
            mark?.let {
                it.holdMs = holdMs
                it.holdFrom = SystemClock.uptimeMillis()
            }
            postInvalidateOnAnimation()
        }

        fun ripple(x: Float, y: Float) {
            val now = SystemClock.uptimeMillis()
            ripples += Ripple(x, y, now)
            ripples += Ripple(x, y, now + RIPPLE_GAP_MS)
            postInvalidateOnAnimation()
        }

        fun sweep(x1: Float, y1: Float, x2: Float, y2: Float, delay: Long, duration: Long, label: String) {
            sweep = Sweep(x1, y1, x2, y2, SystemClock.uptimeMillis(), delay, duration, label)
            mark = null
            chip = null
            lastX = x2
            lastY = y2
            postInvalidateOnAnimation()
        }

        fun say(label: String) {
            val x = if (lastX >= 0) lastX else width / 2f
            val y = if (lastY >= 0) lastY else height * 0.42f
            chip = Chip(label, x, y, SystemClock.uptimeMillis())
            mark = null
            sweep = null
            postInvalidateOnAnimation()
        }

        fun clearMarks() {
            mark = null
            sweep = null
            chip = null
            ripples.clear()
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            val now = SystemClock.uptimeMillis()
            drawGlow(canvas, now)
            getLocationOnScreen(loc)
            canvas.save()
            canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
            sweep?.let { drawSweep(canvas, it, now) }
            mark?.let { drawMark(canvas, it, now) }
            drawRipples(canvas, now)
            chip?.let { drawChip(canvas, it, now) }
            canvas.restore()
            if (visibility == VISIBLE) postInvalidateDelayed(FRAME_MS)
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
            val breathe = if (stillMotion) {
                0f
            } else {
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

        private val stillMotion: Boolean by lazy {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }

        private fun drawHalo(canvas: Canvas, x: Float, y: Float, alpha: Float) {
            val r = dp(HALO_DP)
            if (haloShader == null) {
                haloShader = RadialGradient(
                    0f,
                    0f,
                    r,
                    intArrayOf(ACCENT and 0x38FFFFFF, ACCENT and 0x00FFFFFF),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            haloPaint.shader = haloShader
            haloPaint.alpha = (255 * alpha).toInt()
            canvas.save()
            canvas.translate(x, y)
            canvas.drawCircle(0f, 0f, r, haloPaint)
            canvas.restore()
        }

        private fun drawMark(canvas: Canvas, m: Mark, now: Long) {
            val age = now - m.at
            val p = (age / LOCK_ON_MS.toFloat()).coerceIn(0f, 1f)
            val eased = 1f - (1f - p) * (1f - p)
            val scale = 1.8f - 0.8f * eased
            val alpha = (255 * eased).toInt()
            val r = dp(RING_DP) * scale
            drawHalo(canvas, m.x, m.y, eased)
            ringPaint.color = ACCENT
            ringPaint.alpha = (alpha * 0.22f).toInt()
            ringPaint.strokeWidth = dp(7f)
            canvas.drawCircle(m.x, m.y, r, ringPaint)
            ringPaint.strokeWidth = dp(2.5f)
            ringPaint.alpha = alpha
            canvas.drawCircle(m.x, m.y, r, ringPaint)
            val from = if (stillMotion) -45f else (age % 520L) / 520f * 360f
            val ar = r + dp(6f)
            ringPaint.color = CYAN
            ringPaint.alpha = (alpha * 0.95f).toInt()
            ringPaint.strokeWidth = dp(2f)
            arc.set(m.x - ar, m.y - ar, m.x + ar, m.y + ar)
            canvas.drawArc(arc, from, 135f, false, ringPaint)
            ringPaint.strokeWidth = dp(2.5f)
            fillPaint.color = ACCENT
            fillPaint.alpha = alpha
            canvas.drawCircle(m.x, m.y, dp(4f), fillPaint)
            fillPaint.color = Color.WHITE
            fillPaint.alpha = alpha
            canvas.drawCircle(m.x, m.y, dp(1.6f), fillPaint)
            if (m.holdMs > 0) {
                val hp = ((now - m.holdFrom) / m.holdMs.toFloat()).coerceIn(0f, 1f)
                val hr = r + dp(12f)
                ringPaint.color = CYAN
                ringPaint.strokeWidth = dp(2f)
                ringPaint.alpha = (alpha * 0.9f).toInt()
                arc.set(m.x - hr, m.y - hr, m.x + hr, m.y + hr)
                canvas.drawArc(arc, -90f, 360f * hp, false, ringPaint)
                ringPaint.strokeWidth = dp(2.5f)
            }
            if (m.label.isNotBlank()) {
                drawLabel(canvas, m.label, m.x, m.y, r + dp(14f), alpha, age)
            }
        }

        private fun drawRipples(canvas: Canvas, now: Long) {
            if (ripples.isEmpty()) return
            val it = ripples.iterator()
            while (it.hasNext()) {
                val rp = it.next()
                val p = (now - rp.at) / RIPPLE_MS.toFloat()
                if (p < 0f) continue
                if (p >= 1f) {
                    it.remove()
                    continue
                }
                val eased = 1f - (1f - p) * (1f - p)
                val r = dp(RING_DP) * (1f + 2.2f * eased)
                ringPaint.color = ACCENT
                ringPaint.alpha = (200 * (1f - p)).toInt()
                ringPaint.strokeWidth = dp(1f) + dp(1.5f) * (1f - p)
                canvas.drawCircle(rp.x, rp.y, r, ringPaint)
                ringPaint.strokeWidth = dp(2.5f)
            }
        }

        private fun drawSweep(canvas: Canvas, s: Sweep, now: Long) {
            val age = now - s.at
            val fadeIn = (age / LOCK_ON_MS.toFloat()).coerceIn(0f, 1f)
            val travel = ((age - s.delay) / s.duration.toFloat()).coerceIn(0f, 1f)
            val e = if (travel < 0.5f) {
                2f * travel * travel
            } else {
                1f - (-2f * travel + 2f) * (-2f * travel + 2f) / 2f
            }
            val cx = s.x1 + (s.x2 - s.x1) * e
            val cy = s.y1 + (s.y2 - s.y1) * e
            val done = travel >= 1f
            val fadeOut = if (done) {
                (1f - ((age - s.delay - s.duration) / SWEEP_FADE_MS.toFloat())).coerceIn(0f, 1f)
            } else {
                1f
            }
            if (fadeOut <= 0f) {
                sweep = null
                return
            }
            val alpha = (255 * fadeIn * fadeOut).toInt()
            dashPaint.shader = LinearGradient(
                s.x1,
                s.y1,
                s.x2,
                s.y2,
                ACCENT,
                CYAN,
                Shader.TileMode.CLAMP,
            )
            dashPaint.pathEffect = DashPathEffect(
                floatArrayOf(dp(8f), dp(6f)),
                -(age % 560L) / 560f * dp(14f),
            )
            dashPaint.alpha = (alpha * 0.55f).toInt()
            dashPath.reset()
            dashPath.moveTo(s.x1, s.y1)
            dashPath.lineTo(s.x2, s.y2)
            canvas.drawPath(dashPath, dashPaint)
            dashPaint.shader = null
            dashPaint.pathEffect = null
            if (e > 0f) {
                val tail = 0.35f
                val tx = s.x1 + (s.x2 - s.x1) * maxOf(0f, e - tail)
                val ty = s.y1 + (s.y2 - s.y1) * maxOf(0f, e - tail)
                trailPaint.shader = LinearGradient(
                    tx,
                    ty,
                    cx,
                    cy,
                    CYAN and 0x00FFFFFF,
                    CYAN,
                    Shader.TileMode.CLAMP,
                )
                trailPaint.alpha = (alpha * 0.9f).toInt()
                canvas.drawLine(tx, ty, cx, cy, trailPaint)
                trailPaint.shader = null
            }
            drawHalo(canvas, cx, cy, fadeIn * fadeOut * 0.8f)
            val r = dp(RING_DP)
            ringPaint.color = ACCENT
            ringPaint.alpha = (alpha * 0.22f).toInt()
            ringPaint.strokeWidth = dp(7f)
            canvas.drawCircle(cx, cy, r, ringPaint)
            ringPaint.strokeWidth = dp(2.5f)
            ringPaint.alpha = alpha
            canvas.drawCircle(cx, cy, r, ringPaint)
            fillPaint.color = CYAN
            fillPaint.alpha = alpha
            canvas.drawCircle(cx, cy, dp(4f), fillPaint)
            if (done) {
                fillPaint.color = CYAN
                fillPaint.alpha = alpha
                drawArrow(canvas, s.x1, s.y1, s.x2, s.y2, fillPaint)
                ringPaint.color = CYAN
                ringPaint.alpha = (alpha * 0.8f).toInt()
                ringPaint.strokeWidth = dp(2f)
                canvas.drawCircle(s.x2, s.y2, r + dp(6f), ringPaint)
                ringPaint.strokeWidth = dp(2.5f)
            }
            if (s.label.isNotBlank()) {
                drawLabel(canvas, s.label, s.x1, s.y1, r + dp(14f), alpha, age)
            }
            if (done && ripples.isEmpty() && fadeOut > 0.98f) ripple(s.x2, s.y2)
        }

        private fun drawArrow(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, paint: Paint) {
            val len = hypot(x2 - x1, y2 - y1)
            if (len < dp(12f)) return
            val ang = atan2(y2 - y1, x2 - x1)
            val head = dp(10f)
            val spread = 0.45f
            arrow.reset()
            arrow.moveTo(x2, y2)
            arrow.lineTo(x2 - head * cos(ang - spread), y2 - head * sin(ang - spread))
            arrow.lineTo(x2 - head * cos(ang + spread), y2 - head * sin(ang + spread))
            arrow.close()
            canvas.drawPath(arrow, paint)
        }

        private fun drawChip(canvas: Canvas, c: Chip, now: Long) {
            val age = now - c.at
            if (age > CHIP_TTL_MS) {
                chip = null
                return
            }
            val fadeIn = (age / 150f).coerceIn(0f, 1f)
            val fadeOut = ((CHIP_TTL_MS - age) / 250f).coerceIn(0f, 1f)
            drawLabel(
                canvas,
                c.text,
                c.x,
                c.y,
                dp(RING_DP) + dp(14f),
                (255 * min(fadeIn, fadeOut)).toInt(),
                age,
            )
        }

        private fun drawLabel(
            canvas: Canvas,
            text: String,
            x: Float,
            y: Float,
            gap: Float,
            alpha: Int,
            age: Long,
        ) {
            val label = if (text.length > 30) text.take(29) + "…" else text
            val padX = dp(11f)
            val padY = dp(6f)
            val bar = dp(3f)
            val tw = textPaint.measureText(label)
            val th = textPaint.descent() - textPaint.ascent()
            val cw = tw + 2 * padX + bar + dp(6f)
            val ch = th + 2 * padY
            val screenRight = loc[0] + width
            var left = x + gap
            if (left + cw > screenRight - dp(8f)) left = x - gap - cw
            if (left < loc[0] + dp(8f)) left = loc[0] + dp(8f)
            val top = (y - ch / 2f).coerceIn(loc[1] + dp(8f), loc[1] + height - ch - dp(8f))
            val pop = (age / 120f).coerceIn(0f, 1f)
            val scale = 0.92f + 0.08f * (1f - (1f - pop) * (1f - pop))
            canvas.save()
            canvas.scale(scale, scale, left + cw / 2f, top + ch / 2f)
            platePaint.alpha = (alpha * 0.08f).toInt()
            chipRect.set(left - dp(2f), top + dp(2f), left + cw + dp(2f), top + ch + dp(3f))
            canvas.drawRoundRect(chipRect, ch / 2f + dp(2f), ch / 2f + dp(2f), platePaint)
            platePaint.alpha = (alpha * 0.12f).toInt()
            chipRect.set(left - dp(1f), top + dp(1f), left + cw + dp(1f), top + ch + dp(1.5f))
            canvas.drawRoundRect(chipRect, ch / 2f + dp(1f), ch / 2f + dp(1f), platePaint)
            chipRect.set(left, top, left + cw, top + ch)
            chipPaint.alpha = (alpha * 0.88f).toInt()
            canvas.drawRoundRect(chipRect, ch / 2f, ch / 2f, chipPaint)
            hairPaint.alpha = (alpha * 0.1f).toInt()
            canvas.drawRoundRect(chipRect, ch / 2f, ch / 2f, hairPaint)
            barPaint.shader = LinearGradient(0f, top, 0f, top + ch, CYAN, ACCENT, Shader.TileMode.CLAMP)
            barPaint.alpha = alpha
            chipRect.set(
                left + padX - dp(2f),
                top + padY + dp(1f),
                left + padX - dp(2f) + bar,
                top + ch - padY - dp(1f),
            )
            canvas.drawRoundRect(chipRect, bar / 2f, bar / 2f, barPaint)
            barPaint.shader = null
            textPaint.alpha = alpha
            canvas.drawText(label, left + padX + bar + dp(6f), top + padY - textPaint.ascent(), textPaint)
            canvas.restore()
        }

        private companion object {
            const val ACCENT = 0xFF0A66E4.toInt()
            const val CYAN = 0xFF06B6D4.toInt()
            const val GLOW_WORKING = 0x550A66E4.toInt()
            const val GLOW_WAITING = 0x60FFB000.toInt()
            const val GLOW_BAND = 0.14f
            const val GLOW_CYCLE_MS = 4800L
            const val RING_DP = 16f
            const val HALO_DP = 44f
            const val CHIP_BG = 0xFF111218.toInt()
            const val FRAME_MS = 33L
            const val LOCK_ON_MS = 220L
            const val RIPPLE_MS = 520L
            const val RIPPLE_GAP_MS = 110L
            const val SWEEP_FADE_MS = 320L
            const val CHIP_TTL_MS = 2200L
        }
    }
}
