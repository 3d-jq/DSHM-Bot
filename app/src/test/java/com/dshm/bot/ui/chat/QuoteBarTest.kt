package com.dshm.bot.ui.chat

import androidx.compose.ui.graphics.Color
import com.dshm.bot.core.design.DshmColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引用竖线的取色规则：**竖线必须与所在层的底色对比**。
 *
 * 真机踩过：写死 `accent` —— 用户气泡底色就是 accent，竖线融进去看不见。
 */
class QuoteBarTest {

    @Test
    fun theUserBubbleGetsAReadableBarInsteadOfTheAccentItself() {
        // 用户气泡：底色 == accent（#4D6BFE）→ 必须换色，否则等于没画。
        val bar = quoteBarColorOn(
            background = DshmColors.Light.bubbleUser,
            textColor = DshmColors.Light.onBubbleUser,
            accent = DshmColors.Light.accent,
        )
        assertEquals(
            "用户气泡的底色和 accent 是同一个颜色 —— 这条前提变了就该重新考虑规则",
            DshmColors.Light.bubbleUser,
            DshmColors.Light.accent,
        )
        assertNotEquals("竖线不能和气泡底色相同（会看不见）", DshmColors.Light.bubbleUser, bar)
        assertTrue("竖线要用气泡上的文字色派生", bar.alpha < 1f && bar.alpha > 0f)
    }

    @Test
    fun theAssistantBubbleKeepsTheAccent() {
        val bar = quoteBarColorOn(
            background = DshmColors.Light.bubbleAssistant,
            textColor = DshmColors.Light.textPrimary,
            accent = DshmColors.Light.accent,
        )
        assertEquals(DshmColors.Light.accent, bar)
        assertNotEquals("助手气泡底色不是 accent，竖线要看得见", bar, DshmColors.Light.bubbleAssistant)
    }

    @Test
    fun theStripAboveTheComposerKeepsTheAccent() {
        // 引用条自己有 surfaceMuted 底色，accent 在上面清楚。
        val bar = quoteBarColorOn(
            background = DshmColors.Light.surfaceMuted,
            textColor = DshmColors.Light.textPrimary,
            accent = DshmColors.Light.accent,
        )
        assertEquals(DshmColors.Light.accent, bar)
    }

    @Test
    fun everyBubbleBackgroundGetsABarThatDiffersFromIt() {
        // 两种主题 × 两种气泡：竖线都不能与底色相同。
        for (palette in listOf(DshmColors.Light, DshmColors.Dark)) {
            for (bubble in listOf(palette.bubbleUser to palette.onBubbleUser, palette.bubbleAssistant to palette.textPrimary)) {
                val bar = quoteBarColorOn(bubble.first, bubble.second, palette.accent)
                assertNotEquals("竖线不能融进气泡底色", bubble.first, bar)
                // 对比度粗检：两条颜色至少要有一个通道差得够远。
                val delta = maxOf(
                    kotlin.math.abs(bar.red - bubble.first.red),
                    kotlin.math.abs(bar.green - bubble.first.green),
                    kotlin.math.abs(bar.blue - bubble.first.blue),
                )
                assertTrue("竖线与底色的通道差太小（$delta），看不清", delta > 0.2f)
            }
        }
    }

    @Test
    fun aCustomAccentThatMatchesTheBubbleStillYieldsAContrastingBar() {
        // 主题换色后用户气泡与 accent 不再相同 → 回到 accent（也仍然与底色不同）。
        val otherAccent = Color(0xFF00A36C)
        val bar = quoteBarColorOn(DshmColors.Light.bubbleUser, DshmColors.Light.onBubbleUser, otherAccent)
        assertEquals(otherAccent, bar)
        assertNotEquals(DshmColors.Light.bubbleUser, bar)
    }
}
