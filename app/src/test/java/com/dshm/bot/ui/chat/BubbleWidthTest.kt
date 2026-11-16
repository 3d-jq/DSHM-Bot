package com.dshm.bot.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 气泡宽度上限的护栏。
 *
 * 具体数值是**产品选择**：官方 80/78，用户 2026-11-16 拍板 **90/90**（留白 10%）。
 * 所以这里不锁死数值，只锁**不能退化的性质** —— 留白不得低于 8%（再往上收就贴边了）、
 * 上限不得低于 70%（太窄长回复会一直折行）、两侧留白差 ≤5%（保对称）。
 */
class BubbleWidthTest {

    @Test
    fun bothSidesLeaveAVisibleGapAndStayWideEnoughToRead() {
        for ((name, fraction) in listOf(
            "助手气泡" to BUBBLE_MAX_FRACTION_ASSISTANT,
            "用户气泡" to BUBBLE_MAX_FRACTION_USER,
        )) {
            val gap = 1f - fraction
            assertTrue("$name 太贴边（留白 ${gap * 100}%），看不出间隙", gap >= 0.08f)
            assertTrue("$name 太窄（上限 ${fraction * 100}%），长回复会过早折行", fraction >= 0.70f)
            assertTrue("$name 上限不能 ≥100%（那就没留白了）", fraction < 1f)
        }
    }

    @Test
    fun theTwoGapsAreAlmostTheSame() {
        val delta = kotlin.math.abs(BUBBLE_MAX_FRACTION_ASSISTANT - BUBBLE_MAX_FRACTION_USER)
        assertTrue("两侧留白差太多（$delta），看起来不对称", delta <= 0.05f)
    }

    /**
     * 卡片（产物卡 / 代码产物卡）与助手气泡同宽：它们同属左列。
     * 卡片比气泡宽 → 一屏里「气泡缩进、卡片铺满」的错位（用户 2026-11-16 指出）。
     */
    @Test
    fun theCardsNeverOutgrowTheAssistantBubble() {
        assertTrue(
            "卡片 ${CARD_MAX_FRACTION * 100}% 比助手气泡 ${BUBBLE_MAX_FRACTION_ASSISTANT * 100}% 宽，会错位",
            CARD_MAX_FRACTION <= BUBBLE_MAX_FRACTION_ASSISTANT,
        )
        assertTrue("卡片也不能铺满（那就没留白了）", CARD_MAX_FRACTION < 1f)
        assertTrue("卡片太窄（上限 ${CARD_MAX_FRACTION * 100}%），文件名会过早折行", CARD_MAX_FRACTION >= 0.70f)
    }
}
