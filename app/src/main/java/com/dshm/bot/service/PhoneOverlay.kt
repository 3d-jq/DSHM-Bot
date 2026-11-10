package com.dshm.bot.service

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** 手机操控 Bot 操作时显示的最小控制条：状态、暂停/继续、停止。 */
object PhoneOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var manager: WindowManager? = null
    private var root: LinearLayout? = null
    private var status: TextView? = null
    private var pauseButton: Button? = null
    private val hide = Runnable { hideNow() }

    fun show(service: DshmAccessibilityService, text: String) {
        if (PhoneControlSession.stopped) return
        main.removeCallbacks(hide)
        val windowManager = service.getSystemService(WindowManager::class.java)
        if (root == null) {
            root = build(service)
            manager = windowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(service, 96)
            }
            runCatching { windowManager.addView(root, params) }
        }
        status?.text = text
        pauseButton?.text = if (PhoneControlSession.paused) "继续" else "暂停"
        if (!PhoneControlSession.paused) {
            main.postDelayed(hide, 15_000)
        }
    }

    fun hideNow() {
        main.removeCallbacks(hide)
        val view = root ?: return
        runCatching { manager?.removeView(view) }
        root = null
        manager = null
        status = null
        pauseButton = null
    }

    private fun build(service: DshmAccessibilityService): LinearLayout {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(service, 22).toFloat()
            setColor(Color.parseColor("#E61B2430"))
            setStroke(dp(service, 1), Color.parseColor("#33FFFFFF"))
        }
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape
            setPadding(dp(service, 12), dp(service, 7), dp(service, 8), dp(service, 7))
        }
        status = TextView(service).apply {
            text = "DSH 正在操作手机"
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 1
        }
        row.addView(status, LinearLayout.LayoutParams(0, dp(service, 36), 1f))
        pauseButton = Button(service).apply {
            text = "暂停"
            textSize = 12f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#334A90D9"))
            minWidth = dp(service, 58)
            minHeight = dp(service, 34)
            setOnClickListener {
                PhoneControlSession.paused = !PhoneControlSession.paused
                text = if (PhoneControlSession.paused) "继续" else "暂停"
                if (!PhoneControlSession.paused) show(service, "DSH 正在操作手机")
                else main.removeCallbacks(hide)
            }
        }
        row.addView(pauseButton)
        val stop = Button(service).apply {
            text = "停止"
            textSize = 12f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#55C0392B"))
            minWidth = dp(service, 52)
            minHeight = dp(service, 34)
            setOnClickListener {
                PhoneControlSession.stopped = true
                PhoneControlSession.paused = false
                hideNow()
            }
        }
        row.addView(stop)
        return row
    }

    private fun dp(service: DshmAccessibilityService, value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()
}

object PhoneControlSession {
    @Volatile var paused = false
    @Volatile var stopped = false

    fun reset() {
        paused = false
        stopped = false
    }

    fun blockedReason(): String? = when {
        stopped -> "用户停止了这个手机任务。请让用户重新发起手机操控。"
        paused -> "用户暂停了手机操控；等用户点「继续」后再操作。"
        else -> null
    }
}
