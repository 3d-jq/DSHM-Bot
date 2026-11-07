package com.dshm.bot.ui.chat

import androidx.compose.ui.unit.Dp

/**
 * 倒序消息列表的视觉底部留白：只预留「悬浮输入框 + 它的落点 + 气泡间隙」一次。
 *
 * 回归：曾把 `contentPadding.top/bottom` 对调后又给两侧各加一份输入框高度，
 * 最新气泡被推到离输入框很远的位置。
 */
internal fun chatListBottomPadding(
    composerHeight: Dp,
    composerBottomPadding: Dp,
    composerClearance: Dp,
): Dp = composerHeight + composerBottomPadding + composerClearance
