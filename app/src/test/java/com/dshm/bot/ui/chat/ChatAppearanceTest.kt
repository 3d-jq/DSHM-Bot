package com.dshm.bot.ui.chat

import com.dshm.bot.domain.model.BotPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 团队偏好 → 对话区外观（扁平布局 / 内容宽度 / 思考过程）。 */
class ChatAppearanceTest {

    @Test
    fun bubbleLayoutUsesTheBubbleWidth() {
        val appearance = ChatAppearance.of(BotPrefs(layout = "bubble", width = 65, flatWidth = 100))
        assertFalse(appearance.flat)
        assertEquals(0.65f, appearance.contentFraction, 0.001f)
    }

    @Test
    fun flatLayoutUsesTheFlatWidth() {
        val appearance = ChatAppearance.of(BotPrefs(layout = "flat", width = 65, flatWidth = 90))
        assertTrue(appearance.flat)
        assertEquals(0.9f, appearance.contentFraction, 0.001f)
    }

    @Test
    fun widthIsClampedToTheUsableRange() {
        assertEquals(
            0.5f,
            ChatAppearance.of(BotPrefs(layout = "bubble", width = 5)).contentFraction,
            0.001f,
        )
        assertEquals(
            1f,
            ChatAppearance.of(BotPrefs(layout = "bubble", width = 400)).contentFraction,
            0.001f,
        )
    }

    @Test
    fun thinkingFollowsTheTeamPref() {
        assertTrue(ChatAppearance.of(BotPrefs(thinking = true)).showThinking)
        assertFalse(ChatAppearance.of(BotPrefs(thinking = false)).showThinking)
    }

    @Test
    fun unknownLayoutFallsBackToBubbles() {
        val appearance = ChatAppearance.of(BotPrefs(layout = "grid", width = 70, flatWidth = 100))
        assertFalse(appearance.flat)
        assertEquals(0.7f, appearance.contentFraction, 0.001f)
    }
}
