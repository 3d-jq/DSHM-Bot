package com.dshm.bot.ui.chat

/**
 * 气泡宽度上限（占**可用宽**的比例）。
 *
 * 直接取官方网页端的 CSS 值（`ds-bot/src/client/styles.js`）：
 * - 助手气泡 `.bt-bubble`：`max-width: min(80%, 560px, calc(100% - 82px))` → **80%**
 * - 用户气泡 `.bt-ububble`：`max-width: min(560px, 78%)` → **78%**
 *
 * 两个数几乎一样，所以左右留白看起来是一样宽的 —— 官方要的就是这个观感：
 * 助手气泡右边留白、用户气泡左边留白。注意 `calc(100% - 82px)` 是给左侧头像列让位，
 * 在手机宽度上与 80% 基本重合，所以取 80% 即可。
 *
 * 只**封顶**不拉伸：短消息（"好的"）仍按内容宽，只有长回复才吃这个上限。
 */
internal const val BUBBLE_MAX_FRACTION_ASSISTANT = 0.80f
internal const val BUBBLE_MAX_FRACTION_USER = 0.78f
