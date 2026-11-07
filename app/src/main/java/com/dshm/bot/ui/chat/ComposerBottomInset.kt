package com.dshm.bot.ui.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 悬浮输入框在「输入法 / 小白条」之间的落点，抽成纯函数便于回归。
 *
 * 父容器是 `imePadding()`（内容区被输入法顶上去），输入框自己还要留一段
 * `bottomPadding` 才不会压住小白条。IME 收起是**逐帧**的：
 *
 * ```
 * 输入框离物理底边 = imeInset + bottomPadding(imeInset)
 * ```
 *
 * 目标是让这个和 `= maxOf(imeInset + composerImeGap, 静止位)`：
 * 键盘高时贴着键盘走，回落到静止位（`navigationBars + composerBottomInset`）
 * 就**钉住**，之后 IME 再怎么收，输入框都不动 —— 正是主流 App
 * 「回落到那个地方一下子固定住」的手感。
 *
 * 反例（本次修掉的抖动）：用 `if (imeInset > navigationBars)` 在两种 padding
 * 之间二选一，分支翻转那一帧 padding 从 `composerImeGap` 跳到
 * `navigationBars + composerBottomInset`，输入框在下落途中被凭空抬起，于是抖一下。
 */
internal fun composerBottomPadding(
    imeInset: Dp,
    navigationBars: Dp,
    composerImeGap: Dp,
    composerBottomInset: Dp,
): Dp = (navigationBars + composerBottomInset - imeInset).coerceAtLeast(composerImeGap)

/**
 * 由两个 inset 直接算「输入框离物理底边」的距离，用来验证落点连续、单调、能钉住。
 */
internal fun composerAbsoluteBottom(
    imeInset: Dp,
    navigationBars: Dp,
    composerImeGap: Dp,
    composerBottomInset: Dp,
): Dp = imeInset + composerBottomPadding(imeInset, navigationBars, composerImeGap, composerBottomInset)
