package com.dshm.bot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 气泡宽度上限：值直接抄官方网页端 CSS，且必须真的留出可见间距。
 *
 * 依据 `ds-bot/src/client/styles.js`：
 * - `.bt-bubble`（助手）`min(80%, 560px, calc(100% - 82px))`
 * - `.bt-ububble`（用户）`min(560px, 78%)`
 */
class BubbleWidthTest {

    @Test
    fun theFractionsAreTheOfficialOnes() {
        assertEquals(0.80f, BUBBLE_MAX_FRACTION_ASSISTANT, 0.0001f)
        assertEquals(0.78f, BUBBLE_MAX_FRACTION_USER, 0.0001f)
    }

    @Test
    fun bothSidesLeaveAVisibleGapAndStayWideEnoughToRead() {
        for ((name, fraction) in listOf(
            "助手气泡" to BUBBLE_MAX_FRACTION_ASSISTANT,
            "用户气泡" to BUBBLE_MAX_FRACTION_USER,
        )) {
            val gap = 1f - fraction
            assertTrue("$name 太贴边（留白 ${gap * 100}%），看不出间隙", gap >= 0.15f)
            assertTrue("$name 太窄（上限 ${fraction * 100}%），长回复会过早折行", fraction >= 0.70f)
        }
    }

    @Test
    fun theTwoGapsAreAlmostTheSame() {
        // 官方就是取两个几乎一样的数，所以左右留白看起来一样宽。
        val delta = kotlin.math.abs(BUBBLE_MAX_FRACTION_ASSISTANT - BUBBLE_MAX_FRACTION_USER)
        assertTrue("两侧留白差太多（$delta），看起来不对称", delta <= 0.05f)
    }
}
