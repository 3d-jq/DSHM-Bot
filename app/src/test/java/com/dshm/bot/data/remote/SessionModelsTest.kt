package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会话 wire 模型必须 tolerant：已知类型精确解析，未知类型保留 raw 不丢弃。 */
class SessionModelsTest {

    @Test
    fun parsesEveryKnownPartType() {
        val parts = DshPart.listFromJson(
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", "hi"))
                .put(JSONObject().put("type", "reasoning").put("text", "think"))
                .put(
                    JSONObject().put("type", "image")
                        .put("attachment", JSONObject().put("attachmentId", "a1").put("mediaType", "image/png"))
                        .put("offloaded", true),
                )
                .put(JSONObject().put("type", "file").put("attachment", JSONObject().put("name", "a.txt")))
                .put(
                    JSONObject().put("type", "tool-call")
                        .put("id", "c1").put("name", "bash").put("arguments", "{}"),
                ),
        )

        assertEquals(5, parts.size)
        assertEquals(DshPart.Text("hi"), parts[0])
        assertEquals(DshPart.Reasoning("think"), parts[1])
        val image = parts[2] as DshPart.Image
        assertEquals("a1", image.attachment.attachmentId)
        assertEquals("image/png", image.attachment.mediaType)
        assertTrue(image.offloaded)
        assertEquals("a.txt", (parts[3] as DshPart.File).attachment.name)
        assertEquals(DshPart.ToolCall("c1", "bash", "{}"), parts[4])
    }

    @Test
    fun unknownPartsKeepTheirRawPayload() {
        val part = DshPart.fromJson(JSONObject().put("type", "widget").put("id", "w1"))
        assertTrue(part is DshPart.Unknown)
        val unknown = part as DshPart.Unknown
        assertEquals("widget", unknown.type)
        assertEquals("w1", unknown.raw.optString("id"))
    }

    @Test
    fun partListToleratesMissingOrWrongShapes() {
        assertTrue(DshPart.listFromJson(null).isEmpty())
        assertTrue(DshPart.listFromJson(JSONObject()).isEmpty())
        // 没有 type 的块也要保留成 Unknown，不能静默丢掉。
        assertEquals(1, DshPart.listFromJson(JSONArray().put(JSONObject())).size)
    }

    @Test
    fun attachmentMissingFieldsBecomeNullNotZero() {
        val empty = DshAttachment.fromJson(JSONObject())
        assertNull(empty.attachmentId)
        assertNull(empty.bytes)
        assertNull(empty.width)
        val partial = DshAttachment.fromJson(JSONObject().put("bytes", 12).put("width", 4))
        assertEquals(12, partial.bytes!!)
        assertEquals(4, partial.width!!)
        assertNull(partial.height)
    }

    @Test
    fun parsesStreamChunks() {
        val start = DshStreamChunk.fromJson(
            JSONObject().put("type", "block-start").put("index", 2).put("blockType", "text"),
        )
        assertEquals(DshStreamChunk.BlockStart(2, "text"), start)

        val delta = DshStreamChunk.fromJson(
            JSONObject().put("type", "tool-call-delta")
                .put("index", 1).put("id", "c").put("argumentsDelta", "{\"a\""),
        ) as DshStreamChunk.ToolCallDelta
        assertEquals("{\"a\"", delta.argumentsDelta)
        assertNull(delta.name)

        val end = DshStreamChunk.fromJson(
            JSONObject().put("type", "block-end").put("index", 1)
                .put("block", JSONObject().put("type", "text").put("text", "done")),
        ) as DshStreamChunk.BlockEnd
        assertEquals(DshPart.Text("done"), end.block)

        assertTrue(DshStreamChunk.fromJson(JSONObject().put("type", "future")) is DshStreamChunk.Unknown)
    }

    @Test
    fun logEventKeepsSeqTimeTypeAndData() {
        val event = DshLogEvent.fromJson(
            JSONObject().put("seq", 7).put("time", 1700000000000L)
                .put("type", "approval/asked").put("data", JSONObject().put("id", "ap1")),
        )
        assertEquals(7L, event.seq)
        assertEquals(1700000000000L, event.time)
        assertEquals("approval/asked", event.type)
        assertEquals("ap1", event.data.optString("id"))
    }

    @Test
    fun logEventUsesSafeDefaultsForMissingFields() {
        val event = DshLogEvent.fromJson(null)
        assertEquals(-1L, event.seq)
        assertEquals(0L, event.time)
        assertEquals("", event.type)
        assertTrue(DshLogEvent.listFromJson(null).isEmpty())
    }

    @Test
    fun parsesFollowFramesIncludingStreamingChunks() {
        assertTrue(DshFollowFrame.fromJson(JSONObject().put("type", "snapshot")) is DshFollowFrame.Snapshot)

        val eventFrame = DshFollowFrame.fromJson(
            JSONObject().put("type", "event")
                .put("event", JSONObject().put("type", "user/message").put("seq", 3)),
        ) as DshFollowFrame.Event
        assertEquals("user/message", eventFrame.event.type)
        assertEquals(3L, eventFrame.event.seq)

        val stream = DshFollowFrame.fromJson(
            JSONObject().put("type", "assistant-stream")
                .put(
                    "frame",
                    JSONObject().put("type", "chunk").put("attemptId", "at1")
                        .put("chunk", JSONObject().put("type", "text-delta").put("index", 0).put("text", "你")),
                ),
        ) as DshFollowFrame.AssistantStream
        assertEquals("at1", stream.attemptId)
        assertEquals("chunk", stream.phase)
        assertEquals("你", (stream.chunk as DshStreamChunk.TextDelta).text)

        assertTrue(DshFollowFrame.fromJson(JSONObject().put("type", "future")) is DshFollowFrame.Unknown)
    }

    @Test
    fun sessionSummaryFallsBackBetweenWireFieldNames() {
        val sessions = DshSessionSummary.listFromJson(
            JSONArray()
                .put(JSONObject().put("sessionId", "s1").put("title", "T"))
                .put(JSONObject().put("id", "s2").put("name", "N")),
        )
        assertEquals("s1", sessions[0].id)
        assertEquals("T", sessions[0].title)
        assertEquals("s2", sessions[1].id)
        assertEquals("N", sessions[1].title)
    }
}
