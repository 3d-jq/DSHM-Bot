package com.dshm.bot.ui.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
