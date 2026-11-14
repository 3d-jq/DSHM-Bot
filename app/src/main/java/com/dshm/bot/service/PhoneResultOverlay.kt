package com.dshm.bot.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

/**
 * `.eta-ref` 的完成态：任务结束后不强行拉回 App，而是在屏幕底部留一张结果卡，
 * 显示这一轮的结果摘要，并提供「回到界面」按钮。
 */
object PhoneResultOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var manager: WindowManager? = null
    private var root: View? = null

    fun show(context: Context?, text: String) {
        val service = DshmAccessibilityService.current
        // 只留状态不落内容：结果文本里常带屏读出来的用户数据，logcat 不该有。
        Log.i("PhoneResult", "show service=${service != null} textLen=${text.length}")
        if (service == null) return
        val body = text.trim().ifBlank { "任务已完成。" }
        onMain {
            hideNow()
            val wm = service.getSystemService(WindowManager::class.java) ?: return@onMain
            val dm = service.resources.displayMetrics
            val width = min((dm.widthPixels * 0.92f).toInt(), dp(service, 460))
            val card = build(service, body, width)
            val params = WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = navigationBarHeight(service) + dp(service, 14)
            }
            card.alpha = 0f
            card.translationY = dp(service, 28).toFloat()
            runCatching { wm.addView(card, params) }.onFailure {
                Log.w("PhoneResult", "addView failed: ${it.message}")
                return@onMain
            }
            Log.i("PhoneResult", "card added")
            manager = wm
            root = card
            card.animate().alpha(1f).translationY(0f).setDuration(240L).start()
        }
    }

    fun hide() = onMain { hideNow() }

    private fun hideNow() {
        root?.let { v -> runCatching { manager?.removeView(v) } }
        root = null
        manager = null
    }

    private fun build(service: Context, result: String, width: Int): LinearLayout {
        val look = PhoneBotLooks.load(service)
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(service, 16), dp(service, 14), dp(service, 16), dp(service, 14))
            background = GradientDrawable().apply {
                cornerRadius = dp(service, 22).toFloat()
                setColor(INK)
                setStroke(dp(service, 1), EDGE)
            }
            elevation = dp(service, 12).toFloat()
        }

        val header = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val faceSize = dp(service, 38)
        header.addView(
            PhoneBotFace(service, faceSize, look).apply {
                layoutParams = LinearLayout.LayoutParams(faceSize, faceSize).apply {
                    rightMargin = dp(service, 10)
                }
            },
        )
        val names = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        names.addView(
            TextView(service).apply {
                this.text = look?.name ?: "DSHM Bot"
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        names.addView(
            TextView(service).apply {
                this.text = "任务完成"
                setTextColor(0xFF9FD0FF.toInt())
                textSize = 11.5f
            },
        )
        header.addView(names)
        val close = TextView(service).apply {
            this.text = "关闭"
            setTextColor(0xFFB9BDC8.toInt())
            textSize = 12f
            setPadding(dp(service, 8), dp(service, 6), dp(service, 8), dp(service, 6))
            setOnClickListener { hideNow() }
        }
        header.addView(close)
        card.addView(header)

        val scroll = ScrollView(service).apply {
            isFillViewport = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                min(dp(service, 260), service.resources.displayMetrics.heightPixels / 3),
            ).apply { topMargin = dp(service, 10) }
        }
        scroll.addView(
            TextView(service).apply {
                this.text = result
                setTextColor(0xE6FFFFFF.toInt())
                textSize = 13f
                setLineSpacing(dp(service, 2).toFloat(), 1f)
                setTextIsSelectable(true)
            },
        )
        card.addView(scroll)

        val actions = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(service, 12) }
        }
        actions.addView(
            actionButton(service, "回到界面", true) {
                val botId = PhoneControlSession.activeBotId
                hideNow()
                openApp(service, botId)
            },
        )
        card.addView(actions)
        return card
    }

    private fun actionButton(
        context: Context,
        label: String,
        primary: Boolean,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setPadding(dp(context, 16), dp(context, 9), dp(context, 16), dp(context, 9))
        background = GradientDrawable().apply {
            cornerRadius = dp(context, 16).toFloat()
            setColor(if (primary) ACCENT else 0x33FFFFFF)
        }
        setOnClickListener { onClick() }
    }

    private fun openApp(context: Context, botId: String?) {
        val intent = Intent(context, com.dshm.bot.MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
        if (!botId.isNullOrBlank()) {
            intent.putExtra(com.dshm.bot.MainActivity.EXTRA_OPEN_BOT_ID, botId)
        }
        runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.appTasks
                ?.firstOrNull()
                ?.moveToFront()
        }
        runCatching { context.startActivity(intent) }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) runCatching(block) else main.post { runCatching(block) }
    }

    private fun navigationBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(context, 48)
    }

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()

    private const val INK = 0xF0111218.toInt()
    private const val EDGE = 0x1AFFFFFF
    private const val ACCENT = 0xFF0A66E4.toInt()
}
