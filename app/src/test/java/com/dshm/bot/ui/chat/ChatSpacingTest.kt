package com.dshm.bot.ui.chat

import com.dshm.bot.core.design.DshmSpacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 气泡间距 / 分组：流式态与正式条目必须算出同一个 top padding，
 * 否则落地时会抖一下（Bug 1）。
 */
class ChatSpacingTest {

    private val spacing = DshmSpacing()

    private fun top(
        kind: ChatEntryKind,
        botId: String? = null,
        olderKind: ChatEntryKind? = null,
        olderBotId: String? = null,
        isGroup: Boolean = false,
    ) = chatItemSpacing(spacing, kind, botId, olderKind, olderBotId, isGroup).top

    @Test
    fun userThenBotIsATurnChange() {
        // 用户 → Bot：换人，12dp。流式气泡（BOT + 本 Bot id）算出来必须是同一个值。
        val final = top(ChatEntryKind.BOT, "w1", ChatEntryKind.USER, null)
        val streaming = top(ChatEntryKind.BOT, "w1", ChatEntryKind.USER, null)
        assertEquals(spacing.turnGap, final)
        assertEquals(final, streaming)
    }

    @Test
    fun sameSpeakerStaysTight() {
        assertEquals(
            spacing.xs,
            top(ChatEntryKind.BOT, "w1", ChatEntryKind.BOT, "w1"),
        )
        assertEquals(
            spacing.xs,
            top(ChatEntryKind.USER, null, ChatEntryKind.USER, null),
        )
    }

    @Test
    fun artifactsAlwaysStartANewBlock() {
        assertEquals(
            spacing.turnGap,
            top(ChatEntryKind.ARTIFACTS, null, ChatEntryKind.ARTIFACTS, null),
        )
    }

    @Test
    fun groupChatStacksTheSameBot() {
        val first = chatItemSpacing(spacing, ChatEntryKind.BOT, "w1", ChatEntryKind.USER, null, true)
        assertTrue(first.groupStart)
        assertEquals(spacing.turnGap, first.top)

        val second = chatItemSpacing(spacing, ChatEntryKind.BOT, "w1", ChatEntryKind.BOT, "w1", true)
        assertFalse(second.groupStart)
        assertTrue(second.sameBotAsOlder)
        assertEquals(spacing.xs, second.top)

        val otherBot = chatItemSpacing(spacing, ChatEntryKind.BOT, "w2", ChatEntryKind.BOT, "w1", true)
        assertTrue(otherBot.groupStart)
        assertEquals(spacing.turnGap, otherBot.top)
    }

    @Test
    fun theFirstMessageInAChatHasNothingAboveIt() {
        // 单聊第一条：上面没有东西，按「同人接着说」的紧间距处理（和改动前一致）。
        val single = chatItemSpacing(spacing, ChatEntryKind.BOT, "w1", null, null, false)
        assertEquals(spacing.xs, single.top)
        // 群聊第一条是一组的开头：要显示头像 + 名字，间距用换人档。
        val group = chatItemSpacing(spacing, ChatEntryKind.BOT, "w1", null, null, true)
        assertTrue(group.groupStart)
        assertEquals(spacing.turnGap, group.top)
    }
}
