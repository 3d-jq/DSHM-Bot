package com.dshm.bot.ui.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对齐 DS Bot `ActivityIndicator` 的状态行解析。 */
class ChatActivityTest {

    private fun state(
        running: Boolean = true,
        doing: JSONObject = JSONObject(),
        approvals: JSONArray = JSONArray(),
        turns: JSONObject = JSONObject(),
    ) = JSONObject().put(
        "work",
        JSONObject()
            .put("bots", JSONObject().put("bot-1", doing))
            .put("approvals", approvals)
            .put("turns", turns),
    )

    private fun activity(raw: JSONObject, running: Boolean = true) = parseChatActivity(
        state = raw,
        botId = "bot-1",
        sessionId = "session-1",
        isGroup = false,
        running = running,
    )

    @Test
    fun groupRunningFollowsTheRoundAndStopsWhenItIsOver() {
        // round 在跑 → 显示
        assertTrue(groupRunIsRunning(optimistic = false, roundRunning = true))
        // 刚发出消息、还没等到回执 → 先乐观显示
        assertTrue(groupRunIsRunning(optimistic = true, roundRunning = false))
        // 回执到了、round 结束 → 立刻收（乐观状态按回执回收，不是等超时）
        assertFalse(groupRunIsRunning(optimistic = false, roundRunning = false))
    }

    @Test
    fun botRunningPrefersTheHostOverLocalGuesses() {
        // 乐观 → 先显示
        assertTrue(botRunIsRunning(optimistic = true, sessionStatus = null, lastTurnEnded = false, workRunning = false))
        // 会话状态是权威：说停就停，哪怕本地乐观还在
        assertFalse(botRunIsRunning(optimistic = true, sessionStatus = false, lastTurnEnded = false, workRunning = true))
        assertTrue(botRunIsRunning(optimistic = false, sessionStatus = true, lastTurnEnded = true, workRunning = false))
        // 没有会话状态时：回合边界说结束就不算在跑，否则看 work
        assertFalse(botRunIsRunning(optimistic = false, sessionStatus = null, lastTurnEnded = true, workRunning = true))
        assertTrue(botRunIsRunning(optimistic = false, sessionStatus = null, lastTurnEnded = false, workRunning = true))
    }

    @Test
    fun groupFallsBackToTheSpeakingMemberReportedByRoomChat() {
        // `work.rooms` 里没有发言记录时（round 刚开始 / 刚结束），用 `room-chat` 报的发言人，
        // 这样状态行不会退回群头像。
        val raw = JSONObject().put(
            "work",
            JSONObject().put("rooms", JSONObject().put("room-1", JSONArray())),
        )
        val parsed = parseChatActivity(
            state = raw,
            botId = "room-1",
            sessionId = null,
            isGroup = true,
            running = true,
            groupSpeakingId = "bot-2",
        )
        assertEquals("bot-2", parsed?.memberId)
        assertEquals(ChatActivityKind.THINKING, parsed?.kind)
    }

    @Test
    fun groupPrefersTheMemberThatIsActuallySpeaking() {
        val raw = JSONObject().put(
            "work",
            JSONObject().put(
                "rooms",
                JSONObject().put(
                    "room-1",
                    JSONArray()
                        .put(JSONObject().put("botId", "bot-1"))
                        .put(JSONObject().put("botId", "bot-2").put("speaking", true)),
                ),
            ),
        )
        val parsed = parseChatActivity(
            state = raw,
            botId = "room-1",
            sessionId = null,
            isGroup = true,
            running = true,
            groupSpeakingId = "bot-1",
        )
        assertEquals("bot-2", parsed?.memberId)
    }

    @Test
    fun idleTurnHasNoActivityRow() {
        assertNull(activity(state(), running = false))
    }

    @Test
    fun runningTurnWithoutToolUsesThinkingPhraseAndTurnClock() {
        val parsed = activity(
            state(
                doing = JSONObject().put("since", 1_700_000_000_000L),
                turns = JSONObject().put("session-1", 1_699_999_999_000L),
            ),
        )
        assertEquals(ChatActivityKind.THINKING, parsed?.kind)
        assertNull(parsed?.label)
        assertEquals(1_700_000_000_000L, parsed?.startedAtMs)
        assertEquals("想一想…", chatActivityLabel(parsed, 0))
        assertEquals("理一下思路…", chatActivityLabel(parsed, 2))
    }

    @Test
    fun toolNameAndTargetProduceTheSameWorkingLabel() {
        val parsed = activity(
            state(
                doing = JSONObject()
                    .put("tool", "read")
                    .put("target", "build.gradle.kts"),
            ),
        )
        assertEquals(ChatActivityKind.SEARCHING, parsed?.kind)
        assertEquals("打开文件 · build.gradle.kts…", chatActivityLabel(parsed, 0))
    }

    @Test
    fun ownStepWinsOverToolPhrase() {
        val parsed = activity(
            state(
                doing = JSONObject()
                    .put("tool", "bash")
                    .put("said", "先看一眼项目结构。"),
            ),
        )
        assertEquals("先看一眼项目结构…", chatActivityLabel(parsed, 0))
    }

    @Test
    fun waitingApprovalShowsAlertEvenWithAToolRunning() {
        val parsed = activity(
            state(
                doing = JSONObject().put("tool", "bash"),
                approvals = JSONArray().put(
                    JSONObject().put("botId", "bot-1").put("sessionId", "session-1"),
                ),
            ),
        )
        assertEquals(ChatActivityKind.ALERT, parsed?.kind)
        assertEquals(true, parsed?.waitingForApproval)
        assertEquals("等你批准…", chatActivityLabel(parsed, 0))
    }

    @Test
    fun elapsedLabelMatchesDsBot() {
        assertEquals("1s", chatActivityElapsed(0))
        assertEquals("1m 0s", chatActivityElapsed(60_000))
        assertEquals("1h 2m 3s", chatActivityElapsed(3_723_000))
    }
}
