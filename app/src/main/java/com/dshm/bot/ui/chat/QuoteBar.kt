package com.dshm.bot.ui.chat

import androidx.compose.ui.graphics.Color

/**
 * 引用竖线的颜色。
 *
 * 规则一句话：**竖线必须与它所在那层底色对比**。
 *
 * 踩过的坑：一开始写死 `accent`。助手气泡是浅/深灰底，accent 看得见；但**用户气泡的底色
 * 就是 `accent` 本身**（`bubbleUser == accent == #4D6BFE`）—— 竖线融进气泡里，等于没画。
 * 所以底色与 accent 相同时，换成**该气泡上的文字色**降透明度（对齐 dsh-Go `BubbleQuote`
 * 用 `onUserBubble` 25% 的做法，这里取 45% 让手机上看清）。
 *
 * 纯函数，便于 JVM 单测锁住这条规则。
 */
internal fun quoteBarColorOn(
    /** 竖线所在那层的底色（气泡底 / 引用条自身的底）。 */
    background: Color,
    /** 该层上正文的颜色（底色撞 accent 时用它派生竖线）。 */
    textColor: Color,
    accent: Color,
): Color = if (background == accent) textColor.copy(alpha = 0.45f) else accent
