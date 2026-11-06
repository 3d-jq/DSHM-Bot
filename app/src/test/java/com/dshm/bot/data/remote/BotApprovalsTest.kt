package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审批卡片（DS Bot 的 `state.work.approvals`）。
 */
class BotApprovalsTest {

    private fun stateJson(answerable: Boolean = true) = JSONObject().put(
        "work",
        JSONObject().put(
            "approvals",
            JSONArray().put(
                JSONObject()
                    .put("id", "ap-1")
                    .put("sessionId", "s-1")
                    .put("botId", "w1")
                    .put("roomId", "g1")
                    .put("tool", "bash")
                    .put("reason", "要跑一条命令")
                    .put(
                        "detail",
                        JSONObject().put("text", "ls -la").put("code", "E_PERM"),
                    )
                    .put("at", 1_700_000_000_000L)
                    .put("answerable", answerable),
            ),
        ),
    )

    @Test
    fun anApprovalIsParsedFromTheStateWork() {
        val approvals = parseBotApprovals(stateJson())
        assertEquals(1, approvals.size)
        val approval = approvals.first()
        assertEquals("ap-1", approval.id)
        assertEquals("w1", approval.botId)
        assertEquals("g1", approval.roomId)
        assertEquals("bash", approval.tool)
        assertEquals("要跑一条命令", approval.reason)
        assertEquals("ls -la", approval.detailText)
        assertEquals("E_PERM", approval.detailCode)
        assertTrue(approval.answerable)
    }

    @Test
    fun anApprovalWeCannotAnswerIsMarked() {
        val approval = parseBotApprovals(stateJson(answerable = false)).first()
        assertFalse(approval.answerable)
    }

    @Test
    fun noApprovalsIsAnEmptyList() {
        assertTrue(parseBotApprovals(null).isEmpty())
        assertTrue(parseBotApprovals(JSONObject()).isEmpty())
        assertTrue(parseBotApprovals(JSONObject().put("work", JSONObject())).isEmpty())
    }

    @Test
    fun anApprovalWithoutDetailIsStillUsable() {
        val approval = parseBotApprovals(
            JSONObject().put(
                "work",
                JSONObject().put(
                    "approvals",
                    JSONArray().put(JSONObject().put("id", "ap-2").put("tool", "read")),
                ),
            ),
        ).first()
        assertNull(approval.detailText)
        assertNull(approval.detailCode)
        assertEquals("read", approval.tool)
    }
}
