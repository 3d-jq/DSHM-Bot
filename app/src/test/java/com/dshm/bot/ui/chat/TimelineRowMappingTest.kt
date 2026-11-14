package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.TimelineFile
import com.dshm.bot.data.remote.TimelineRow
import com.dshm.bot.data.remote.TimelineRowPayload
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TimelineRow → ChatEntry 的映射（Phase 1b 主管线切换的渲染面）。 */
class TimelineRowMappingTest {

    private fun userRow(
        key: String = "user:5",
        seq: Long? = 5L,
        text: String = "你好",
        requestId: String? = null,
        images: Int = 0,
        live: Boolean = false,
    ) = TimelineRow(
        key = key, kind = "user", seq = seq, turn = 1, timeMs = 100L,
        payload = TimelineRowPayload.User(text, emptyList(), images, requestId, live),
    )

    @Test
    fun userRowMapsWithAttachmentsAndRenderKey() {
        val mapped = chatEntryOf(userRow(), botName = "小助手")!!
        assertEquals(ChatEntryKind.USER, mapped.kind)
        assertEquals("user:5", mapped.id)
        assertEquals("你好", mapped.text)
        assertEquals(5L, mapped.seq)

        // 认领：渲染键保留 p:<requestId>（元素原地过渡），本地附件预览搬过来。
        val claimed = chatEntryOf(
            userRow(requestId = "req-1"),
            botName = "小助手",
            renderKey = "p:req-1",
            attachmentItems = listOf(ChatAttachmentItem("a.png", "/cache/a.png", isImage = true)),
        )!!
        assertEquals("p:req-1", claimed.id)
        assertEquals("a.png", claimed.attachmentItems.single().name)
    }

    @Test
    fun botRowMapsAuthorFallbackWholeAndLive() {
        val whole = JSONObject().put("length", 9).put("file", JSONObject().put("name", "长文"))
        val plain = chatEntryOf(
            TimelineRow("bot:6", "bot", 6L, 1, 100L, TimelineRowPayload.Bot("好了", null, null, 3_000L, null, live = false)),
            botName = "小助手",
        )!!
        assertEquals("小助手", plain.author)
        assertEquals(3_000L, plain.durationMs)
        assertNull(plain.whole)
        assertFalse(plain.live)

        val live = chatEntryOf(
            TimelineRow("bot:relay-9:0", "bot", null, 1, 100L, TimelineRowPayload.Bot("在想", "研究员", "bot-2", null, whole, live = true)),
            botName = "小助手",
        )!!
        assertEquals("研究员", live.author)
        assertEquals("bot-2", live.botId)
        assertEquals("长文", live.whole?.name)
        assertTrue(live.live)
        assertNull("群 relay 行 seq=null", live.seq)
    }

    @Test
    fun toolRowBecomesEventLineWithTarget() {
        val mapped = chatEntryOf(
            TimelineRow("tool:9", "tool", 9L, 1, 100L, TimelineRowPayload.Tool("present", "ok", "已交付 2 个文件", null, "p1")),
            botName = "小助手",
        )!!
        assertEquals(ChatEntryKind.EVENT, mapped.kind)
        assertEquals("已交付 2 个文件", mapped.text)
        assertEquals("present", mapped.tool)
        assertEquals("tool:9", mapped.id)
    }

    @Test
    fun artifactRowKeepsGroupRoomKeyAndDeliveredFlag() {
        val mapped = chatEntryOf(
            TimelineRow(
                "present:room-1:3", "artifact", 40L, 3, 100L,
                TimelineRowPayload.Artifact(
                    listOf(TimelineFile("/ws/a.docx", "终稿"), TimelineFile("/ws/b.png", null)),
                    delivered = true, turn = 3,
                ),
            ),
            botName = "小助手",
        )!!
        assertEquals(ChatEntryKind.ARTIFACTS, mapped.kind)
        assertEquals("present:room-1:3", mapped.id)
        assertTrue(mapped.artifactsDelivered)
        assertEquals(listOf("/ws/a.docx", "/ws/b.png"), mapped.artifacts.map { it.path })
        assertEquals("终稿", mapped.artifacts.first().description)
    }

    @Test
    fun eventRowMapsKindVocabularyAndRawText() {
        val mapped = chatEntryOf(
            TimelineRow(
                "event:12", "event", 12L, 1, 100L,
                TimelineRowPayload.Event(
                    eventKind = "failed", code = "rate_limited", botId = "bot-9", name = "研究员",
                    raw = JSONObject().put("text", "没能回答"),
                ),
            ),
            botName = "小助手",
        )!!
        assertEquals(ChatEntryKind.EVENT, mapped.kind)
        assertEquals("failed", mapped.eventKind)
        assertEquals("rate_limited", mapped.eventCode)
        assertEquals("研究员", mapped.author)
        assertEquals("bot-9", mapped.botId)
        assertEquals("没能回答", mapped.text)
    }

    @Test
    fun dividerViewAnsweredInboxRowsMap() {
        val divider = chatEntryOf(
            TimelineRow("divider:0", "divider", null, null, null, TimelineRowPayload.Divider(123L)),
            botName = "小助手",
        )!!
        assertEquals(ChatEntryKind.DIVIDER, divider.kind)
        assertEquals(123L, divider.timeMs)

        val view = chatEntryOf(
            TimelineRow(
                "view:14", "view", 14L, 1, 100L,
                TimelineRowPayload.View(JSONObject().put("title", "卡").put("callId", "c1").put("view", JSONObject().put("x", 1))),
            ),
            botName = "小助手",
        )!!
        assertEquals(ChatEntryKind.VIEW, view.kind)
        assertEquals("c1", view.view?.id)

        val answered = chatEntryOf(
            TimelineRow(
                "answer:15", "answered", 15L, 1, 100L,
                TimelineRowPayload.Answered(
                    JSONObject()
                        .put("questions", JSONArray().put(JSONObject().put("question", "选哪个").put("id", "q1")))
                        .put("answers", JSONArray().put(JSONObject().put("id", "q1").put("selected", JSONArray().put("甲")))),
                ),
            ),
            botName = "小助手",
        )!!
        assertEquals(ChatEntryKind.ANSWERED, answered.kind)
        assertEquals("选哪个", answered.answered.single().question)
        assertEquals(listOf("甲"), answered.answered.single().answers)

        val inbox = chatEntryOf(
            TimelineRow(
                "inbox:i-1", "inbox", null, null, null,
                TimelineRowPayload.Inbox("排队消息", "next-turn", "req-9", "i-1", sourceKind = "user", botId = null, name = null),
            ),
            botName = "小助手",
        )!!
        assertEquals(ChatEntryKind.PENDING, inbox.kind)
        assertEquals("next-turn", inbox.pendingSlot)
        assertEquals("req-9", inbox.pendingRequestId)
        assertEquals("user", inbox.pendingSourceKind)
        assertNull(inbox.seq)
    }

    /** 群聊排队行：key `inbox:<id>`，项带 sourceKind 与「谁在排队」。 */
    @Test
    fun groupInboxRowCarriesStableIdSourceKindAndSpeaker() {
        val row = TimelineRow(
            "inbox:roomq:room-1:3", "inbox", null, null, null,
            TimelineRowPayload.Inbox(
                text = "排队发言",
                slot = "next-turn",
                requestId = null,
                itemId = "roomq:room-1:3",
                sourceKind = "user",
                botId = "bot-9",
                name = "研究员",
            ),
        )
        val mapped = chatEntryOf(row, botName = "小助手")!!
        assertEquals(ChatEntryKind.PENDING, mapped.kind)
        assertEquals("inbox:roomq:room-1:3", mapped.id)
        assertEquals("user", mapped.pendingSourceKind)
        assertEquals("研究员", mapped.pendingName)
        assertEquals("bot-9", mapped.botId)
    }

    @Test
    fun unexpandedKindsMapToNull() {
        assertNull(
            chatEntryOf(
                TimelineRow("turntail:1", "turntail", 1L, 1, 100L, TimelineRowPayload.Raw(JSONObject())),
                botName = "小助手",
            ),
        )
    }

}
