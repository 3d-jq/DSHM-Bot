package com.dshm.bot.ui.chat

/**
 * 气泡宽度上限（占**可用宽**的比例）。
 *
 * 官方网页端 CSS（`ds-bot/src/client/styles.js`）是：
 * - 助手气泡 `.bt-bubble`：`max-width: min(80%, 560px, calc(100% - 82px))` → 80%
 * - 用户气泡 `.bt-ububble`：`max-width: min(560px, 78%)` → 78%
 *
 * ⚠️ **当前值是 90% / 90%，是用户 2026-11-16 定的试值**（比官方松一点，长回复少折行）。
 * 想回到官方观感把这两个数改回 0.80 / 0.78 即可（只此一处生效）。
 *
 * 只**封顶**不拉伸：短消息（"好的"）仍按内容宽，只有长回复才吃这个上限。
 */
internal const val BUBBLE_MAX_FRACTION_ASSISTANT = 0.90f
internal const val BUBBLE_MAX_FRACTION_USER = 0.90f
