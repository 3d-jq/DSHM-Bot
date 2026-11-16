package com.dshm.bot.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 气泡宽度上限的护栏。
 *
 * 具体数值是**可调的产品选择**（官方 80/78，用户 2026-11-16 试 85/85），所以这里
 * 不锁死数值，只锁**不能退化的性质**：必须真的留出可见间距，也不能窄到影响阅读。
 */
class BubbleWidthTest {

    @Test
    fun bothSidesLeaveAVisibleGapAndStayWideEnoughToRead() {
        for ((name, fraction) in listOf(
            "助手气泡" to BUBBLE_MAX_FRACTION_ASSISTANT,
            "用户气泡" to BUBBLE_MAX_FRACTION_USER,
        )) {
            val gap = 1f - fraction
            assertTrue("$name 太贴边（留白 ${gap * 100}%），看不出间隙", gap > 0.10f)
            assertTrue("$name 太窄（上限 ${fraction * 100}%），长回复会过早折行", fraction >= 0.70f)
            assertTrue("$name 上限不能 ≥100%（那就没留白了）", fraction < 1f)
        }
    }

    @Test
    fun theTwoGapsAreAlmostTheSame() {
        val delta = kotlin.math.abs(BUBBLE_MAX_FRACTION_ASSISTANT - BUBBLE_MAX_FRACTION_USER)
        assertTrue("两侧留白差太多（$delta），看起来不对称", delta <= 0.05f)
    }
}
