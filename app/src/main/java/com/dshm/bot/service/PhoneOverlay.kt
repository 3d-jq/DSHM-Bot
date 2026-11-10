package com.dshm.bot.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.min

/**
 * 手机操控 Bot 工作时浮在别的 App 上面的控制胶囊（移植 DeepSearch `HandsCapsule`）：
 *
 * - 深色墨底 + 发丝描边，顶部居中、避开状态栏；
 * - 左侧三色渐变呼吸环（SweepGradient）+ 四根活动条；
 * - 中间「第 N 步」+ 当前动作；
 * - 右侧 停止；等用户接管时多一颗「继续」；等批准时多「打开」。
 *
 * 舞台（边缘跑马灯 / 瞄准环 / 涟漪）在 [PhoneStage]。
 */
object PhoneOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var manager: WindowManager? = null
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var title: TextView? = null
    private var detail: TextView? = null
    private var ring: RingView? = null
    private var bars: BarsView? = null
    private var continueBtn: TextView? = null
    private var openBtn: TextView? = null
    private var allowBtn: TextView? = null
    private var denyBtn: TextView? = null
    private var stopBtn: TextView? = null
    private val hide = Runnable { hideNow() }

    /** 兼容旧调用：显式文案的工作态。 */
    fun show(service: DshmAccessibilityService, text: String) {
        working(service, 0, text)
    }

    fun working(service: DshmAccessibilityService, step: Int, text: String) = onMain {
        if (PhoneControlSession.stopped) return@onMain
        ensure(service)
        title?.text = if (step > 0) "第 $step 步" else "DSH 正在操作手机"
        detail?.text = text
        continueBtn?.visibility = View.GONE
        openBtn?.visibility = View.GONE
        allowBtn?.visibility = View.GONE
        denyBtn?.visibility = View.GONE
        stopBtn?.visibility = View.VISIBLE
        ring?.turning(true)
        bars?.start()
        PhoneStage.mood(PhoneStage.Mood.WORKING)
        main.removeCallbacks(hide)
        main.postDelayed(hide, 15_000)
    }

    /** 轮到你：登录、验证码等在 operated App 里完成，胶囊给「继续」。 */
    fun takeOver(service: DshmAccessibilityService, reason: String) = onMain {
        if (PhoneControlSession.stopped) return@onMain
        ensure(service)
        title?.text = "轮到你"
        detail?.text = reason.ifBlank { "请在手机上完成这一步，然后点继续" }
        continueBtn?.visibility = View.VISIBLE
        openBtn?.visibility = View.GONE
        allowBtn?.visibility = View.GONE
        denyBtn?.visibility = View.GONE
        stopBtn?.visibility = View.VISIBLE
        ring?.turning(false)
        bars?.stop()
        PhoneStage.mood(PhoneStage.Mood.WAITING)
        main.removeCallbacks(hide)
    }

    /** 等你批准：这一步要用户点一下允许。 */
    fun approval(service: DshmAccessibilityService, what: String, decidable: Boolean = true) = onMain {
        if (PhoneControlSession.stopped) return@onMain
        ensure(service)
        title?.text = "等你批准"
        detail?.text = what
        continueBtn?.visibility = View.GONE
        openBtn?.visibility = if (decidable) View.GONE else View.VISIBLE
        allowBtn?.visibility = if (decidable) View.VISIBLE else View.GONE
        denyBtn?.visibility = if (decidable) View.VISIBLE else View.GONE
        stopBtn?.visibility = View.VISIBLE
        ring?.turning(false)
        bars?.stop()
        PhoneStage.mood(PhoneStage.Mood.WAITING)
        main.removeCallbacks(hide)
    }

    /** 注入手势期间让浮窗不接触摸，避免手势落在自己身上。 */
    fun passThrough(service: DshmAccessibilityService, on: Boolean) = onMain {
        val view = root ?: return@onMain
        val p = params ?: return@onMain
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val has = p.flags and flag != 0
        if (has == on) return@onMain
        p.flags = if (on) p.flags or flag else p.flags and flag.inv()
        runCatching { manager?.updateViewLayout(view, p) }
    }

    /** 目标点被胶囊压住时，把胶囊挪到屏幕另一端。 */
    fun dodge(service: DshmAccessibilityService, targetX: Int, targetY: Int) = onMain {
        val view = root ?: return@onMain
        val p = params ?: return@onMain
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val margin = dp(service, 12)
        val hit = targetX >= loc[0] - margin &&
            targetX <= loc[0] + view.width + margin &&
            targetY >= loc[1] - margin &&
            targetY <= loc[1] + view.height + margin
        val wanted = if (hit) {
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        } else {
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        if (p.gravity == wanted) return@onMain
        p.gravity = wanted
        p.y = if (hit) navigationBarHeight(service) + dp(service, 6) else statusBarHeight(service) + dp(service, 6)
        runCatching { manager?.updateViewLayout(view, p) }
    }

    /** 截图的一瞬整层隐藏；[restore] 再放回来。 */
    fun hideForCapture() = onMain {
        root?.visibility = View.INVISIBLE
        PhoneStage.setVisible(false)
    }

    fun restore() = onMain {
        root?.visibility = View.VISIBLE
        PhoneStage.setVisible(true)
    }

    /**
     * 这一轮手机任务结束：收起浮层并回到 DSHM Bot。
     * 这是 DeepSearch `HandsOperator.finally` 里 `bringAppToFront` 的同款闭环。
     */
    fun finishAndReturn(context: Context? = null) = onMain {
        val target = DshmAccessibilityService.current ?: context
        hideNow()
        val ctx = target ?: return@onMain
        main.postDelayed({
            val intent = android.content.Intent(ctx, com.dshm.bot.MainActivity::class.java)
                .addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            runCatching { ctx.startActivity(intent) }
        }, 180L)
    }

    fun hideNow(): Unit = onMain {
        main.removeCallbacks(hide)
        val view = root ?: return@onMain
        runCatching { manager?.removeView(view) }
        root = null
        manager = null
        params = null
        title = null
        detail = null
        ring = null
        bars = null
        continueBtn = null
        openBtn = null
        allowBtn = null
        denyBtn = null
        stopBtn = null
        PhoneStage.hide()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) runCatching(block) else main.post { runCatching(block) }
    }

    private fun ensure(service: DshmAccessibilityService) {
        if (root != null) return
        PhoneStage.show(service)
        val wm = service.getSystemService(WindowManager::class.java)
        val capsule = build(service)
        val dm = service.resources.displayMetrics
        val p = WindowManager.LayoutParams(
            min((dm.widthPixels * 0.94f).toInt(), dp(service, 420)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight(service) + dp(service, 6)
        }
        runCatching { wm.addView(capsule, p) }.onFailure { return }
        manager = wm
        root = capsule
        params = p
        capsule.alpha = 0f
        capsule.translationY = -dp(service, 8).toFloat()
        capsule.animate().alpha(1f).translationY(0f).setDuration(220L).start()
    }

    private fun build(service: DshmAccessibilityService): LinearLayout {
        val capsule = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(service, 8), dp(service, 6), dp(service, 8), dp(service, 6))
            background = GradientDrawable().apply {
                cornerRadius = dp(service, 24).toFloat()
                setColor(INK)
                setStroke(dp(service, 1), EDGE)
            }
            elevation = dp(service, 8).toFloat()
        }

        val faceSize = dp(service, 34)
        val faceBox = FrameLayout(service).apply {
            layoutParams = LinearLayout.LayoutParams(faceSize, faceSize).apply { rightMargin = dp(service, 8) }
        }
        val logo = ImageView(service).apply {
            layoutParams = FrameLayout.LayoutParams(faceSize - dp(service, 8), faceSize - dp(service, 8))
                .apply { gravity = Gravity.CENTER }
            setImageResource(com.dshm.bot.R.mipmap.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        faceBox.addView(logo)
        ring = RingView(service).apply {
            layoutParams = FrameLayout.LayoutParams(faceSize, faceSize).apply { gravity = Gravity.CENTER }
        }
        faceBox.addView(ring)
        capsule.addView(faceBox)

        bars = BarsView(service).apply {
            layoutParams = LinearLayout.LayoutParams(dp(service, 18), dp(service, 20)).apply {
                gravity = Gravity.CENTER_VERTICAL
                rightMargin = dp(service, 6)
            }
        }
        capsule.addView(bars)

        val texts = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        title = TextView(service).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        detail = TextView(service).apply {
            setTextColor(Color.argb(200, 220, 220, 220))
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        texts.addView(title)
        texts.addView(detail)
        capsule.addView(texts)

        continueBtn = button(service, "继续", ACCENT) {
            PhoneControlSession.paused = false
            PhoneApprovalGate.allowLatest()
            DshmAccessibilityService.current?.let { working(it, 0, "继续操作手机") }
        }.apply { visibility = View.GONE }
        openBtn = button(service, "打开", ACCENT) {
            val intent = android.content.Intent(service, com.dshm.bot.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { service.startActivity(intent) }
        }.apply { visibility = View.GONE }
        allowBtn = button(service, "允许", ACCENT) {
            PhoneApprovalGate.allowLatest()
            DshmAccessibilityService.current?.let { working(it, 0, "继续操作手机") }
        }.apply { visibility = View.GONE }
        denyBtn = button(service, "拒绝", STOP_RED) {
            PhoneApprovalGate.denyLatest()
            DshmAccessibilityService.current?.let { working(it, 0, "已拒绝这一步") }
        }.apply { visibility = View.GONE }
        stopBtn = button(service, "停止", STOP_RED) {
            PhoneControlSession.stopped = true
            PhoneControlSession.paused = false
            hideNow()
        }
        capsule.addView(continueBtn)
        capsule.addView(openBtn)
        capsule.addView(allowBtn)
        capsule.addView(denyBtn)
        capsule.addView(stopBtn)
        return capsule
    }

    private fun button(service: DshmAccessibilityService, text: String, color: Int, onClick: () -> Unit): TextView =
        TextView(service).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(service, 12), dp(service, 7), dp(service, 12), dp(service, 7))
            background = GradientDrawable().apply {
                cornerRadius = dp(service, 16).toFloat()
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(service, 6) }
            setOnClickListener { onClick() }
        }

    /** 三色渐变呼吸环（移植 DeepSearch `HandsCapsule.RingView`）。 */
    private class RingView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()
        private var shader: SweepGradient? = null
        private var breath = 0.5f
        private var working = false
        private var animator: ValueAnimator? = null

        fun turning(on: Boolean) {
            working = on
            animator?.cancel()
            animator = null
            breath = if (stillMotion(context)) 0.5f else 0f
            if (on && !stillMotion(context)) {
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = BREATH_MS
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = null
                    addUpdateListener {
                        breath = 0.5f - 0.5f * cos((it.animatedValue as Float) * 2f * Math.PI.toFloat())
                        invalidate()
                    }
                    start()
                }
            }
            invalidate()
        }

        override fun onDetachedFromWindow() {
            animator?.cancel()
            super.onDetachedFromWindow()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val stroke = resources.displayMetrics.density * 1.5f
            paint.strokeWidth = stroke
            rect.set(stroke, stroke, w - stroke, h - stroke)
            shader = SweepGradient(
                w / 2f,
                h / 2f,
                intArrayOf(ACCENT, VIOLET, CYAN, ACCENT),
                floatArrayOf(0f, 0.38f, 0.72f, 1f),
            )
        }

        override fun onDraw(canvas: Canvas) {
            if (working) {
                paint.shader = shader
                paint.alpha = (255 * (0.86f + 0.14f * breath)).toInt()
                val scale = 1f + 0.06f * breath
                canvas.save()
                canvas.scale(scale, scale, width / 2f, height / 2f)
                canvas.drawOval(rect, paint)
                canvas.restore()
            } else {
                paint.shader = null
                paint.color = 0x59FFFFFF
                canvas.drawOval(rect, paint)
            }
        }
    }

    /** 四根依次起伏的活动条（移植 DeepSearch `HandsCapsule.BarsView`）。 */
    private class BarsView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var t = 0f
        private var animator: ValueAnimator? = null

        fun start() {
            if (animator != null || stillMotion(context)) {
                invalidate()
                return
            }
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = BREATH_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = null
                addUpdateListener {
                    t = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stop() {
            animator?.cancel()
            animator = null
        }

        override fun onDetachedFromWindow() {
            stop()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val barW = 3f * d
            val gap = 2.5f * d
            val total = 4 * barW + 3 * gap
            var x = (width - total) / 2f
            for (i in 0 until 4) {
                val phase = ((t - i * BAR_LAG) % 1f + 1f) % 1f
                val eased = if (stillMotion(context)) 1f else 0.5f - 0.5f * cos(phase * 2f * Math.PI.toFloat())
                val h = height * (0.35f + 0.65f * eased)
                paint.color = if (i % 2 == 0) CYAN else ACCENT
                paint.alpha = (255 * (0.6f + 0.4f * eased)).toInt()
                canvas.drawRoundRect(x, height - h, x + barW, height.toFloat(), barW / 2f, barW / 2f, paint)
                x += barW + gap
            }
        }
    }

    private fun statusBarHeight(service: DshmAccessibilityService): Int {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else dp(service, 24)
    }

    private fun navigationBarHeight(service: DshmAccessibilityService): Int {
        val id = service.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else dp(service, 48)
    }

    private fun dp(service: DshmAccessibilityService, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            service.resources.displayMetrics,
        ).toInt()

    private const val ACCENT = 0xFF0A66E4.toInt()
    private const val VIOLET = 0xFF7C5CFF.toInt()
    private const val CYAN = 0xFF06B6D4.toInt()
    private const val INK = 0xD1111218.toInt()
    private const val EDGE = 0x1AFFFFFF
    private const val STOP_RED = 0xFF9B3B3B.toInt()
    private const val BREATH_MS = 4800L
    private const val BAR_LAG = 600f / BREATH_MS

    private fun stillMotion(context: Context): Boolean =
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
}

object PhoneControlSession {
    @Volatile
    var paused = false
    @Volatile
    var stopped = false

    @Volatile
    private var engagedBot: String? = null

    /** 这一轮里第一次真正调用了手机工具。 */
    fun engage(botId: String?) {
        engagedBot = botId
    }

    /** 只让发起手机操作的那个 Bot 的回合结束触发“回到 App”。 */
    fun consumeFor(botId: String): Boolean {
        val owner = engagedBot ?: return false
        if (owner.isNotEmpty() && owner != botId) return false
        engagedBot = null
        return true
    }

    fun reset() {
        paused = false
        stopped = false
        engagedBot = null
    }

    fun blockedReason(): String? = when {
        stopped -> "用户停止了这个手机任务。请让用户重新发起手机操控。"
        paused -> "用户暂停了手机操控；等用户点「继续」后再操作。"
        else -> null
    }
}
