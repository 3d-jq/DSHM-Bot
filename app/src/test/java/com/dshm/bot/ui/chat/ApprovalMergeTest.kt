package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.BotApproval
import com.dshm.bot.data.remote.DshApprovalRequest
import com.dshm.bot.data.remote.DshMuxState
import com.dshm.bot.data.remote.DshQuestionRequest
import com.dshm.bot.data.remote.DshUserQuestion
import com.dshm.bot.R
import com.dshm.bot.domain.model.ChatApproval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审批卡片的合并规则。
 *
 * 守的是三件事：一次请求只有一张卡；不属于本对话的请求不能在这里被应答；
 * 群聊只能走 DS Bot 的 `approval-answer`（不能和引擎的 `$events/result` 抢同一次应答）。
 */
class ApprovalMergeTest {

    private fun entry(
        id: String,
        sessionId: String,
        botId: String = "bot-1",
        roomId: String? = null,
        answerable: Boolean = false,
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

    private fun host(eventId: String, sessionId: String) = DshApprovalRequest(
        eventId = eventId,
        sessionId = sessionId,
        toolName = "bash",
        reason = "raw reason",
    )

    private fun question(eventId: String, sessionId: String) = DshQuestionRequest(
        eventId = eventId,
        sessionId = sessionId,
        questions = listOf(DshUserQuestion(id = "q1", question = "要哪个版本？")),
    )

    private fun merge(
        botEntries: List<BotApproval> = emptyList(),
        hostApprovals: List<DshApprovalRequest> = emptyList(),
        hostQuestions: List<DshQuestionRequest> = emptyList(),
        isGroup: Boolean = false,
        botId: String = "bot-1",
        sessionId: String? = "session-1",
        hostAnswerable: Boolean = true,
    ) = mergeChatApprovals(
        botEntries,
        hostApprovals,
        hostQuestions,
        isGroup,
        botId,
        sessionId,
        hostAnswerable,
    )

    // ── 正常路径 ─────────────────────────────────────────────────────────────

    @Test
    fun privateChatWithEventIdIsAnswerableOnce() {
        // 回归：以前 DS Bot 的条目（answerable=false）和事件流的卡片会各画一张，
        // 其中一张还是手机上根本走不通的「去 DSH 里答」。
        val cards = merge(
            // 私聊里 DS Bot 只「备注谁在等」，answerable 恒为 false：应答权来自 eventId。
            botEntries = listOf(entry(id = "a1", sessionId = "session-1", answerable = false)),
            hostApprovals = listOf(host(eventId = "e1", sessionId = "session-1")),
        )
        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals(ChatApproval.Action.ANSWER, card.action)
        assertEquals(ChatApproval.Source.DSH_HOST, card.source)
        assertEquals("e1", card.eventId)
        assertEquals("bot:a1", card.key)
    }

    @Test
    fun groupChatAnswersThroughDsBotOnly() {
        val cards = merge(
            botEntries = listOf(entry(id = "g1", sessionId = "hidden-1", botId = "bot-2", roomId = "room-1", answerable = true)),
            hostApprovals = listOf(host(eventId = "e1", sessionId = "hidden-1")),
            isGroup = true,
            botId = "room-1",
            sessionId = null,
        )
        val card = cards.single()
        assertEquals(1, cards.size)
        // 应答权在 DS Bot 手上：即使拿到 eventId 也只能走 approval-answer。
        assertEquals(ChatApproval.Source.DS_BOT, card.source)
        assertEquals(ChatApproval.Action.ANSWER, card.action)
    }

    @Test
    fun questionIsShownNotDropped() {
        val cards = merge(hostQuestions = listOf(question(eventId = "q1", sessionId = "session-1")))
        val card = cards.single()
        assertEquals(ChatApproval.Kind.QUESTION, card.kind)
        assertEquals("要哪个版本？", card.reason)
        // 提问的选项 UI 还没接，先说明去哪儿答，不给假按钮。
        assertEquals(ChatApproval.Action.ANSWER_ELSEWHERE, card.action)
    }

    // ── 边界 ────────────────────────────────────────────────────────────────

    @Test
    fun chatSessionIdIsTheDshSessionNotTheBotId() {
        // Bot 的 DSH 会话 id 是 bot.sessionId（store.js 的 chatOf），和 botId 不同。
        val cards = merge(
            hostApprovals = listOf(host(eventId = "e1", sessionId = "session-1")),
            botId = "bot-1",
            sessionId = "session-1",
        )
        assertEquals(1, cards.size)
        assertEquals(ChatApproval.Action.ANSWER, cards.single().action)
    }

    @Test
    fun hostCardSurvivesBeforeStateRefresh() {
        // state 快照还没到：先按事件流画一张能点的；到了之后合并成一张，不多出来。
        val before = merge(hostApprovals = listOf(host(eventId = "e1", sessionId = "session-1")))
        assertEquals(1, before.size)
        assertEquals("host:e1", before.single().key)

        val after = merge(
            botEntries = listOf(entry(id = "a1", sessionId = "session-1")),
            hostApprovals = listOf(host(eventId = "e1", sessionId = "session-1")),
        )
        assertEquals(1, after.size)
        assertEquals("bot:a1", after.single().key)
    }

    @Test
    fun eventIdStillAttachesWhenChatHistoryIsNotLoadedYet() {
        // load() 还没回来（sessionId 未知），但 DS Bot 的条目已经认出了这条会话：
        // eventId 必须照样挂上，否则私聊卡片会一直是灰的。
        val cards = merge(
            botEntries = listOf(entry(id = "a1", sessionId = "session-1", answerable = false)),
            hostApprovals = listOf(host(eventId = "e1", sessionId = "session-1")),
            sessionId = null,
        )
        assertEquals("e1", cards.single().eventId)
        assertEquals(ChatApproval.Action.ANSWER, cards.single().action)
    }

    @Test
    fun unknownSessionWithoutEntriesIsIgnored() {
        // 历史没加载完 + 会话对不上：先不猜，避免把别人的审批显示在这里。
        val cards = merge(hostApprovals = listOf(host(eventId = "e1", sessionId = "someone-else")), sessionId = null)
        assertTrue(cards.isEmpty())
    }

    @Test
    fun emptyInputsProduceNoCards() {
        assertTrue(merge().isEmpty())
        assertTrue(merge(hostApprovals = listOf(host(eventId = "e1", sessionId = ""))).isEmpty())
    }

    // ── 异常 / 越权 ─────────────────────────────────────────────────────────

    @Test
    fun otherBotsApprovalsNeverLeakIntoThisChat() {
        // 回归：belongsToThisChat 曾经恒为 true —— 别的 Bot 的审批会出现在这个对话里，
        // 点「允许一次」放行的是错误 Bot 的命令。
        val cards = merge(
            botEntries = listOf(entry(id = "a1", sessionId = "session-1", answerable = false)),
            hostApprovals = listOf(host(eventId = "e1", sessionId = "session-1"), host(eventId = "e2", sessionId = "session-2")),
        )
        assertEquals(listOf("bot:a1"), cards.map { it.key })
        assertEquals("e1", cards.single().eventId)
    }

    @Test
    fun groupHostEventsAloneNeverMakeCards() {
        // 群聊里成员会话是隐藏的，对不上 DS Bot 的条目就不该凭空多一张卡。
        val cards = merge(
            hostApprovals = listOf(host(eventId = "e1", sessionId = "hidden-1")),
            isGroup = true,
            botId = "room-1",
        )
        assertTrue(cards.isEmpty())
    }

    @Test
    fun privateEntryWithoutEventIdWaitsForEngine() {
        val cards = merge(botEntries = listOf(entry(id = "a1", sessionId = "session-1", answerable = false)))
        val card = cards.single()
        assertNull(card.eventId)
        assertEquals(ChatApproval.Action.WAIT_ENGINE, card.action)
    }

    @Test
    fun unanswerableGroupEntryPointsElsewhere() {
        val cards = merge(
            botEntries = listOf(entry(id = "g1", sessionId = "hidden-1", botId = "bot-2", roomId = "room-1", answerable = false)),
            isGroup = true,
            botId = "room-1",
        )
        assertEquals(ChatApproval.Action.ANSWER_ELSEWHERE, cards.single().action)
    }

    // ── 灰按钮的说明文案 ────────────────────────────────────────────────────

    @Test
    fun waitingHintDistinguishesLinkStates() {
        assertNotEquals(approvalWaitReason(DshMuxState.CONNECTING), approvalWaitReason(DshMuxState.RECONNECTING))
        assertNotEquals(approvalWaitReason(DshMuxState.UNAUTHORIZED), approvalWaitReason(DshMuxState.RECONNECTING))
        // 连上了却还没有 eventId（或连接已关）：都是「等」，用兜底那句。
        assertEquals(approvalWaitReason(DshMuxState.CONNECTED), approvalWaitReason(DshMuxState.IDLE))
        assertEquals(R.string.approval_wait_engine, approvalWaitReason(DshMuxState.CONNECTED))
    }

    // ── 应答通道断了 ────────────────────────────────────────────────────────

    @Test
    fun privateCardGoesGreyWhenEventStreamIsDown() {
        // 缓存里还有 eventId，但链路断了：`$events/result` 发不出去，按钮必须灰。
        val cards = merge(
            botEntries = listOf(entry(id = "a1", sessionId = "session-1", answerable = false)),
            hostApprovals = listOf(host(eventId = "e1", sessionId = "session-1")),
            hostAnswerable = false,
        )
        assertEquals(ChatApproval.Action.WAIT_ENGINE, cards.single().action)
    }

    @Test
    fun groupCardStaysAnswerableWhenEventStreamIsDown() {
        // 群聊走 approval-answer（普通 HTTP），事件流断了照样能答。
        val cards = merge(
            botEntries = listOf(entry(id = "g1", sessionId = "hidden-1", botId = "bot-2", roomId = "room-1", answerable = true)),
            isGroup = true,
            botId = "room-1",
            hostAnswerable = false,
        )
        assertEquals(ChatApproval.Action.ANSWER, cards.single().action)
    }
}
