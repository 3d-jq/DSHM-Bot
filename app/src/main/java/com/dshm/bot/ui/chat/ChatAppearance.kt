package com.dshm.bot.ui.chat

import com.dshm.bot.domain.model.BotPrefs

/**
 * 团队偏好推到本机对话区的样子。
 *
 * 网页端（`client.js`）：
 * - `layout=flat` 时 `.bt-bubble` 去掉底色、圆角和内边距，消息块本身按
 *   `flatWidth` 限宽；`bubble` 时气泡按 `width` 限宽。
 * - `thinking` 打开时在回复上方画一条思考行（模型的 reasoning）。
 *
 * 本机把同一套语义落在对话区上：助手气泡的底色 / 限宽、用户气泡的限宽，
 * 以及思考行。
 */
data class ChatAppearance(
    /** 扁平布局：助手气泡不画底色、圆角和内边距。 */
    val flat: Boolean,
    /** 内容最大宽度占对话列的比例（0.5..1）。 */
    val contentFraction: Float,
    /** 显示思考过程。 */
    val showThinking: Boolean,
) {
    companion object {
        val Default = ChatAppearance(flat = false, contentFraction = 0.8f, showThinking = false)

        fun of(prefs: BotPrefs): ChatAppearance {
            val flat = prefs.layout == "flat"
            val percent = if (flat) prefs.flatWidth else prefs.width
            return ChatAppearance(
                flat = flat,
                contentFraction = percent.coerceIn(50, 100) / 100f,
                showThinking = prefs.thinking,
            )
        }
    }
}
