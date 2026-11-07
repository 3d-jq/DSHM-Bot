package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.BotApproval
import com.dshm.bot.data.remote.DshQuestionRequest
import com.dshm.bot.data.remote.DshUserQuestion
import com.dshm.bot.domain.model.ChatApproval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审批卡片的合并规则。
 *
 * 守的是两件事：审批只走 DS Bot 的 `approval-answer`；不属于本对话的提问不显示。
 */
class ApprovalMergeTest {

    private fun entry(
        id: String,
        sessionId: String,
        botId: String = "bot-1",
        roomId: String? = null,
        answerable: Boolean = true,
    ) = BotApproval(
        id = id,
        sessionId = sessionId,
        botId = botId,
        roomId = roomId,
        tool = "bash",
        reason = "要跑一条命令",
        detailText = "rm -rf /tmp/x",
        answerable = answerable,
    )

    private fun question(eventId: String, sessionId: String) = DshQuestionRequest(
        eventId = eventId,
        sessionId = sessionId,
        questions = listOf(DshUserQuestion(id = "q1", question = "要哪个版本？")),
    )

    private fun merge(
        botEntries: List<BotApproval> = emptyList(),
        hostQuestions: List<DshQuestionRequest> = emptyList(),
        isGroup: Boolean = false,
        botId: String = "bot-1",
        sessionId: String? = "session-1",
    ) = mergeChatApprovals(
        botEntries,
        hostQuestions,
        isGroup,
        botId,
        sessionId,
    )

    @Test
    fun privateChatAnswersThroughDsBotWithoutEventStream() {
        val card = merge(
            botEntries = listOf(entry(id = "a1", sessionId = "session-1")),
        ).single()
        assertEquals(ChatApproval.Action.ANSWER, card.action)
        assertEquals(ChatApproval.Source.DS_BOT, card.source)
        assertEquals("bot:a1", card.key)
    }

    @Test
    fun groupChatAnswersThroughDsBotOnly() {
        val card = merge(
            botEntries = listOf(entry(id = "g1", sessionId = "hidden-1", roomId = "room-1")),
            isGroup = true,
            botId = "room-1",
            sessionId = null,
        ).single()
        assertEquals(ChatApproval.Source.DS_BOT, card.source)
        assertEquals(ChatApproval.Action.ANSWER, card.action)
    }

    @Test
    fun stalePluginApprovalDoesNotShowFakeButtons() {
        val card = merge(
            botEntries = listOf(entry(id = "a1", sessionId = "session-1", answerable = false)),
        ).single()
        assertEquals(ChatApproval.Action.ANSWER_ELSEWHERE, card.action)
    }

    @Test
    fun questionIsShownNotDropped() {
        val card = merge(hostQuestions = listOf(question(eventId = "q1", sessionId = "session-1"))).single()
        assertEquals(ChatApproval.Kind.QUESTION, card.kind)
        assertEquals("要哪个版本？", card.reason)
        assertEquals(ChatApproval.Action.ANSWER_ELSEWHERE, card.action)
    }

    @Test
    fun otherSessionsQuestionsAreIgnored() {
        assertTrue(merge(hostQuestions = listOf(question(eventId = "q1", sessionId = "someone-else")), sessionId = null).isEmpty())
    }

    @Test
    fun emptyInputsProduceNoCards() {
        assertTrue(merge().isEmpty())
    }
}
