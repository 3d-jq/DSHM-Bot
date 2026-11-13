package com.dshm.bot.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/**
 * 手机操控 Bot 工作时浮在别的 App 上面的控制胶囊（移植 DeepSearch `HandsCapsule`）：
 *
 * - 深色墨底 + 发丝描边，顶部居中、避开状态栏；
 * - 左侧是当前 Bot 的头像；
 * - 中间只显示当前动作，不再显示步数；
 * - 右侧 停止；等用户接管时多一颗「继续」；等批准时多「打开」。
 *
 * 舞台（边缘跑马灯 / 鼠标指针）在 [PhoneStage]。
 */
object PhoneOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var manager: WindowManager? = null
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var title: TextView? = null
    private var detail: TextView? = null
    private var face: PhoneBotFace? = null
    private var faceBotId: String? = null
    private var continueBtn: TextView? = null
    private var openBtn: TextView? = null
    private var allowBtn: TextView? = null
    private var denyBtn: TextView? = null
    private var stopBtn: TextView? = null
    private var interjectBtn: TextView? = null
    private val hide = Runnable { hideNow() }

    /** 兼容旧调用：显式文案的工作态。 */
    fun show(service: DshmAccessibilityService, text: String) {
        working(service, text)
    }

    fun working(service: DshmAccessibilityService, text: String) = onMain {
        if (PhoneControlSession.stopped) return@onMain
        PhoneResultOverlay.hide()
        ensure(service)
        refreshFace(service)
        title?.text = "DSH 正在操作手机"
        detail?.text = text
        continueBtn?.visibility = View.GONE
        openBtn?.visibility = View.GONE
        allowBtn?.visibility = View.GONE
        denyBtn?.visibility = View.GONE
        stopBtn?.visibility = View.VISIBLE
        interjectBtn?.visibility = View.VISIBLE
        PhoneStage.mood(PhoneStage.Mood.WORKING)
        main.removeCallbacks(hide)
        main.postDelayed(hide, 15_000)
    }

    /** 轮到你：登录、验证码等在 operated App 里完成，胶囊给「继续」。 */
    fun takeOver(service: DshmAccessibilityService, reason: String) = onMain {
        if (PhoneControlSession.stopped) return@onMain
        PhoneResultOverlay.hide()
        ensure(service)
        title?.text = "轮到你"
        detail?.text = reason.ifBlank { "请在手机上完成这一步，然后点继续" }
        continueBtn?.visibility = View.VISIBLE
        openBtn?.visibility = View.GONE
        allowBtn?.visibility = View.GONE
        denyBtn?.visibility = View.GONE
        stopBtn?.visibility = View.VISIBLE
        interjectBtn?.visibility = View.VISIBLE
        PhoneStage.mood(PhoneStage.Mood.WAITING)
        main.removeCallbacks(hide)
    }

    /** 等你批准：这一步要用户点一下允许。 */
    fun approval(service: DshmAccessibilityService, what: String, decidable: Boolean = true) = onMain {
        if (PhoneControlSession.stopped) return@onMain
        PhoneResultOverlay.hide()
        ensure(service)
        title?.text = "等你批准"
        detail?.text = what
        continueBtn?.visibility = View.GONE
        openBtn?.visibility = if (decidable) View.GONE else View.VISIBLE
        allowBtn?.visibility = if (decidable) View.VISIBLE else View.GONE
        denyBtn?.visibility = if (decidable) View.VISIBLE else View.GONE
        stopBtn?.visibility = View.VISIBLE
        // 批准态按钮已经够多，插话收起来，避免胶囊挤爆。
        interjectBtn?.visibility = View.GONE
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

    /** 这一轮手机任务结束：收起操作胶囊，显示 eta 同款「结果卡 + 回到界面」。 */
    fun finishWithResult(context: Context?, text: String) = onMain {
        hideNow()
        PhoneResultOverlay.show(context, text)
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
        face = null
        faceBotId = null
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

    private fun refreshFace(service: DshmAccessibilityService) {
        val botId = PhoneControlSession.activeBotId
        if (botId == faceBotId) return
        faceBotId = botId
        face?.setLook(PhoneBotLooks.load(service))
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
        val look = PhoneBotLooks.load(service)
        face = PhoneBotFace(service, faceSize, look).apply {
            layoutParams = FrameLayout.LayoutParams(faceSize, faceSize).apply { gravity = Gravity.CENTER }
        }
        faceBotId = PhoneControlSession.activeBotId
        faceBox.addView(face)
        capsule.addView(faceBox)

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
            DshmAccessibilityService.current?.let { working(it, "继续操作手机") }
        }.apply { visibility = View.GONE }
        openBtn = button(service, "打开", ACCENT) {
            val intent = android.content.Intent(service, com.dshm.bot.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { service.startActivity(intent) }
        }.apply { visibility = View.GONE }
        allowBtn = button(service, "允许", ACCENT) {
            PhoneApprovalGate.allowLatest()
            DshmAccessibilityService.current?.let { working(it, "继续操作手机") }
        }.apply { visibility = View.GONE }
        denyBtn = button(service, "拒绝", STOP_RED) {
            PhoneApprovalGate.denyLatest()
            DshmAccessibilityService.current?.let { working(it, "已拒绝这一步") }
        }.apply { visibility = View.GONE }
        stopBtn = button(service, "停止", STOP_RED) {
            PhoneControlSession.stopped = true
            PhoneControlSession.paused = false
            hideNow()
        }
        interjectBtn = button(service, "插话", ACCENT) {
            PhoneInterjectOverlay.show(service, PhoneControlSession.activeBotId)
        }.apply { visibility = View.VISIBLE }
        capsule.addView(continueBtn)
        capsule.addView(openBtn)
        capsule.addView(allowBtn)
        capsule.addView(denyBtn)
        capsule.addView(stopBtn)
        capsule.addView(interjectBtn)
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
    private const val INK = 0xD1111218.toInt()
    private const val EDGE = 0x1AFFFFFF
    private const val STOP_RED = 0xFF9B3B3B.toInt()
}

object PhoneControlSession {
    @Volatile
    var paused = false
    @Volatile
    var stopped = false

    @Volatile
    private var engagedBot: String? = null
    @Volatile
    private var engaged = false
    @Volatile
    var activeBotId: String? = null
        private set

    /** 这一轮里第一次真正调用了手机工具。 */
    fun engage(botId: String?) {
        engagedBot = botId
        engaged = true
        if (!botId.isNullOrBlank()) activeBotId = botId
    }

    /** 只让发起手机操作的那个 Bot 的回合结束触发“回到 App”。 */
    fun consumeFor(@Suppress("UNUSED_PARAMETER") botId: String): Boolean {
        if (!engaged) return false
        engaged = false
        engagedBot = null
        return true
    }

    /** 用户发出一条新消息：清掉上一轮停止/暂停和“已参与”标记。 */
    fun beginTurn() {
        paused = false
        stopped = false
        engaged = false
        engagedBot = null
    }

    fun reset() {
        paused = false
        stopped = false
        engaged = false
        engagedBot = null
        activeBotId = null
    }

    fun blockedReason(): String? = when {
        stopped -> "用户停止了这个手机任务。请让用户重新发起手机操控。"
        paused -> "用户暂停了手机操控；等用户点「继续」后再操作。"
        else -> null
    }
}
