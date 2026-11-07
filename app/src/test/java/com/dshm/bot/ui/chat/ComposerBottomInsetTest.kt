package com.dshm.bot.ui.chat

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮输入框在 IME 收起时的落点。
 *
 * 回归：曾经用 `if (imeInset > navigationBars)` 在两种 bottom padding 之间二选一，
 * 分支翻转那一帧输入框被凭空抬起 ≈28dp，回落途中抖一下。
 */
class ComposerBottomInsetTest {

    private val navigationBars = 16.dp
    private val composerImeGap = 4.dp
    private val composerBottomInset = 16.dp
    private val resting = navigationBars + composerBottomInset

    private fun padding(ime: Int) = composerBottomPadding(
        imeInset = ime.dp,
        navigationBars = navigationBars,
        composerImeGap = composerImeGap,
        composerBottomInset = composerBottomInset,
    )

    private fun bottom(ime: Int) = composerAbsoluteBottom(
        imeInset = ime.dp,
        navigationBars = navigationBars,
        composerImeGap = composerImeGap,
        composerBottomInset = composerBottomInset,
    )

    @Test
    fun keyboardUpStaysImeGapAboveKeyboard() {
        // 键盘完全抬起：padding 只剩贴键间隙，输入框跟着 imePadding() 走。
        assertEquals(composerImeGap, padding(900))
        assertEquals(904.dp, bottom(900))
    }

    @Test
    fun composerPinsAtRestingPositionWhileImeCollapses() {
        // 关键手感：回落到静止位就钉住，之后 IME 再怎么收都不动。
        val pinAt = resting - composerImeGap
        assertEquals(resting, bottom(pinAt.value.toInt()))
        assertEquals(resting, bottom(20))
        assertEquals(resting, bottom(16))
        assertEquals(resting, bottom(8))
        assertEquals(resting, bottom(0))
    }

    @Test
    fun landingIsContinuousAcrossTheWholeCollapse() {
        // 逐帧收起：相邻两帧的落点差不超过 1dp，且单调不增 —— 不会有任何一帧
        // 向上弹（那一下抖动就是相邻帧差突然变成负的大值）。
        val samples = (900 downTo 0 step 5).map { bottom(it).value }
        samples.zipWithNext { previous, next ->
            assertTrue("落点必须单调回落: $previous -> $next", next <= previous + 0.01f)
            assertTrue("相邻帧不能跳变: $previous -> $next", previous - next <= 5.01f)
        }
    }

    @Test
    fun oldBranchWouldJumpAtTheThreshold() {
        // 旧实现在 imeInset 跨过 navigationBars 时 padding 从 4dp 跳到 32dp：
        // 输入框离底边从 20dp 跳到 48dp。新旧对比锁住这个回归。
        val oldBefore = 16.dp + composerImeGap
        val oldAfter = 16.dp + resting
        assertEquals(20.dp, oldBefore)
        assertEquals(48.dp, oldAfter)
        assertTrue((oldAfter - oldBefore).value >= 28f)
        // 新实现同一处两侧都钉在静止位。
        assertEquals(resting, bottom(17))
        assertEquals(resting, bottom(16))
    }

    @Test
    fun boundsHoldForWeirdInsets() {
        // 导航条为 0（三键导航/老设备）或 IME 为负/超大时都不出界。
        assertEquals(4.dp, composerBottomPadding(900.dp, 0.dp, 4.dp, 16.dp))
        assertEquals(16.dp, composerBottomPadding(0.dp, 0.dp, 4.dp, 16.dp))
        assertEquals(16.dp, composerAbsoluteBottom(0.dp, 0.dp, 4.dp, 16.dp))
        assertEquals(96.dp, composerBottomPadding(0.dp, 80.dp, 4.dp, 16.dp))
    }
}
