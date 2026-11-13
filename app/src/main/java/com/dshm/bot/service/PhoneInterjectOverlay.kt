package com.dshm.bot.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.dshm.bot.MainActivity
import kotlin.math.min

/**
 * 悬浮条「插话」的输入面板。
 *
 * 悬浮胶囊本身是 `FLAG_NOT_FOCUSABLE`，拿不到输入法焦点，所以插话要用**单独一个
 * 可聚焦的窗口**。用户写完点发送 → 带 `EXTRA_OPEN_BOT_ID` + `EXTRA_INTERJECT_DRAFT`
 * 拉起 MainActivity，回到对应 Bot 的对话并预填输入框；发送时走 steer 模式插进当前回合。
 */
internal object PhoneInterjectOverlay {

    @Volatile
    private var root: View? = null

    fun show(service: DshmAccessibilityService, botId: String?) {
        hide(service)
        val wm = service.getSystemService(WindowManager::class.java)
        val dm = service.resources.displayMetrics
        val edit = EditText(service).apply {
            hint = "插一句话给正在干活的 Bot…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(160, 220, 220, 220))
            textSize = 14f
            isSingleLine = false
            maxLines = 3
            background = GradientDrawable().apply {
                cornerRadius = dp(service, 12).toFloat()
                setColor(0xFF1C1F25.toInt())
                setStroke(dp(service, 1), 0xFF3A3F48.toInt())
            }
            setPadding(dp(service, 12), dp(service, 10), dp(service, 12), dp(service, 10))
        }
        val send = TextView(service).apply {
            text = "发送"
            setTextColor(Color.WHITE)
            textSize = 13f
            background = GradientDrawable().apply {
                cornerRadius = dp(service, 16).toFloat()
                setColor(0xFF1F4FD8.toInt())
            }
            setPadding(dp(service, 14), dp(service, 8), dp(service, 14), dp(service, 8))
        }
        val cancel = TextView(service).apply {
            text = "取消"
            setTextColor(Color.argb(200, 220, 220, 220))
            textSize = 13f
            setPadding(dp(service, 12), dp(service, 8), dp(service, 12), dp(service, 8))
        }
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(service, 20).toFloat()
                setColor(0xF214161A.toInt())
                setStroke(dp(service, 1), 0xFF3A3F48.toInt())
            }
            setPadding(dp(service, 16), dp(service, 14), dp(service, 16), dp(service, 14))
            elevation = dp(service, 12).toFloat()
            addView(TextView(service).apply {
                text = "插话（会插入当前这一轮，不用等它做完）"
                setTextColor(Color.argb(180, 220, 220, 220))
                textSize = 11f
            })
            addView(edit, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(service, 8) })
            addView(LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(cancel)
                addView(send)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(service, 8) })
        }

        val p = WindowManager.LayoutParams(
            min((dm.widthPixels * 0.9f).toInt(), dp(service, 380)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // 关键：不要 FLAG_NOT_FOCUSABLE，否则 EditText 拿不到 IME 焦点。
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        cancel.setOnClickListener { hide(service) }
        send.setOnClickListener {
            val text = edit.text?.toString()?.trim().orEmpty()
            hide(service)
            if (text.isEmpty()) return@setOnClickListener
            val intent = android.content.Intent(service, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_INTERJECT_DRAFT, text)
            botId?.let { intent.putExtra(MainActivity.EXTRA_OPEN_BOT_ID, it) }
            runCatching { service.startActivity(intent) }
        }
        runCatching { wm.addView(panel, p) }.onFailure { return }
        root = panel
        edit.requestFocus()
        val imm = service.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hide(service: DshmAccessibilityService) {
        val view = root ?: return
        root = null
        runCatching { service.getSystemService(WindowManager::class.java).removeView(view) }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
