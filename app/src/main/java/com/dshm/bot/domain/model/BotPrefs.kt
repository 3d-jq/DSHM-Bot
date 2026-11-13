package com.dshm.bot.domain.model

/**
 * DS Bot 的团队级偏好（`state.prefs` / `set-prefs`）。
 *
 * 这些值属于**团队**，由引擎持久化，网页端和其它设备共用；和本 App 自己的
 * 主题 / 字号设置是两套，不互相覆盖。本机按它们渲染（配色 / 强调色 /
 * 气泡布局 / 内容宽度 / 动效档位 / 思考过程），见 `core/design` 与
 * `ui/chat/ChatAppearance`。
 */
data class BotPrefs(
    val theme: String = "deepseek",
    val accent: String? = null,
    val motion: String = "normal",
    val layout: String = "bubble",
    val width: Int = 80,
    val flatWidth: Int = 100,
    val thinking: Boolean = false,
)
