package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 主机事件帧 + 消息行映射：卡片必须在映射层就存在，不能到 UI 才被丢掉。 */
class SessionPresentationTest {

    private fun logEvent(type: String, data: JSONObject, seq: Long = 1L) = DshLogEvent.fromJson(
        JSONObject().put("seq", seq).put("time", 1000L).put("type", type).put("data", data),
    )

    @Test
    fun parsesEveryHostEventFrame() {
        val ready = DshHostEvent.fromFrame(
            JSONObject().put("type", "ready").put("clientId", "c1")
                .put("host", JSONObject().put("home", "/root")),
        ) as DshHostEvent.Ready
        assertEquals("c1", ready.clientId)
        assertEquals("/root", ready.home)

        val emit = DshHostEvent.fromFrame(
            JSONObject().put("type", "emit").put("event", "settings/document-updated")
                .put("args", JSONArray().put("ui-chat")),
        ) as DshHostEvent.Emit
        assertEquals("settings/document-updated", emit.event)
        assertEquals("ui-chat", emit.args.optString(0))

        val cancelled = DshHostEvent.fromFrame(
            JSONObject().put("type", "cancel").put("eventId", "ev9"),
        ) as DshHostEvent.Cancelled
        assertEquals("ev9", cancelled.eventId)

        assertTrue(DshHostEvent.fromFrame(JSONObject().put("type", "future")) is DshHostEvent.Unknown)
    }

    @Test
    fun waterfallFramesCarryIdsAndRequest() {
        val approval = DshHostEvent.fromFrame(
            JSONObject().put("type", "waterfall").put("event", DshHostEvent.APPROVAL_REQUEST)
                .put("eventId", "ev1").put("agentId", "s1")
                .put("request", JSONObject().put("toolName", "bash")),
        ) as DshHostEvent.Waterfall
        assertEquals(DshWaterfallKind.APPROVAL, approval.kind)
        assertEquals("ev1", approval.eventId)
        assertEquals("s1", approval.agentId)
        assertEquals("bash", approval.request.optString("toolName"))

        val question = DshHostEvent.fromFrame(
            JSONObject().put("type", "waterfall").put("event", DshHostEvent.USER_QUESTIONS_REQUEST)
                .put("eventId", "ev2").put("agentId", "s1"),
        ) as DshHostEvent.Waterfall
        assertEquals(DshWaterfallKind.USER_QUESTIONS, question.kind)

        val other = DshHostEvent.fromFrame(
            JSONObject().put("type", "waterfall").put("event", "plugin/ask"),
        ) as DshHostEvent.Waterfall
        assertEquals(DshWaterfallKind.OTHER, other.kind)
    }

    @Test
    fun reasoningIsSplitOutOfTheAssistantMessage() {
        val parts = JSONArray()
            .put(JSONObject().put("type", "reasoning").put("text", "先想一下"))
            .put(JSONObject().put("type", "text").put("text", "答案是 42"))
        val data = JSONObject().put("message", JSONObject().put("parts", parts))
            .put("usage", JSONObject().put("inputTokens", 10))
            .put("interrupted", true)

        val rows = DshChatRow.fromEvent(logEvent("assistant/message", data))

        assertEquals(2, rows.size)
        assertEquals(DshChatRow.Reasoning(1L, "先想一下"), rows[0])
        val message = rows[1] as DshChatRow.AssistantMessage
        assertEquals(listOf(DshPart.Text("答案是 42")), message.parts)
        assertEquals(10, message.usage!!.optInt("inputTokens"))
        assertTrue(message.interrupted)
    }

    @Test
    fun userToolApprovalQuestionAndUnknownRowsAreAllKept() {
        val user = DshChatRow.fromEvent(
            logEvent("user/message", JSONObject().put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", "hi")))),
        )
        assertEquals(DshChatRow.UserMessage(1L, listOf(DshPart.Text("hi"))), user.single())

        val tool = DshChatRow.fromEvent(
            logEvent("tool/call", JSONObject().put("callId", "c1").put("name", "bash").put("arguments", "{}")),
        )
        assertEquals(DshChatRow.ToolCall(1L, "c1", "bash", "{}"), tool.single())

        val approval = DshChatRow.fromEvent(
            logEvent("approval/asked", JSONObject().put("id", "ap1").put("toolName", "bash").put("reason", "需要确认")),
        )
        assertEquals("ap1", (approval.single() as DshChatRow.ApprovalAsked).approvalId)

        val question = DshChatRow.fromEvent(
            logEvent("user-questions/request", JSONObject().put("questions", JSONArray())),
        )
        assertTrue(question.single() is DshChatRow.Question)

        val unknown = DshChatRow.fromEvent(logEvent("brand/new-card", JSONObject().put("x", 1)))
        val unknownRow = unknown.single() as DshChatRow.Unknown
        assertEquals("brand/new-card", unknownRow.type)
        assertEquals(1, unknownRow.raw.optJSONObject("data")!!.optInt("x"))
    }

    @Test
    fun foldedEventsBecomeEventRowsAndFlattenInOrder() {
        val rows = DshChatRow.fromEvents(
            listOf(
                logEvent("turn/start", JSONObject(), seq = 1),
                logEvent("user/message", JSONObject(), seq = 2),
                logEvent("turn/end", JSONObject(), seq = 3),
            ),
        )
        assertTrue(rows[0] is DshChatRow.Event)
        assertTrue(rows[1] is DshChatRow.UserMessage)
        assertEquals("turn/end", (rows[2] as DshChatRow.Event).type)
    }

    @Test
    fun previewTextSkipsReasoningAndLabelsNonTextParts() {
        val parts = listOf(
            DshPart.Text("看这个 "),
            DshPart.Reasoning("内部思考"),
            DshPart.ToolCall("c", "bash", "{}"),
            DshPart.Unknown("widget", JSONObject()),
        )
        assertEquals("看这个 [工具 bash]", parts.previewText())
        assertFalse(parts.previewText().contains("内部思考"))
    }
}
