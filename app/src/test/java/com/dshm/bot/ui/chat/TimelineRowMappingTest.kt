package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.ChangedFile
import com.dshm.bot.data.remote.ChangesSummary
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
    fun changesRowMapsToChangesEntryOnlyOnceTheSummaryArrives() {
        val row = TimelineRow(
            "changes:6", "changes", 6L, 2, 100L,
            TimelineRowPayload.Changes(turn = 2, raw = JSONObject().put("turn", 2)),
        )
        // 摘要还没拉到：不出行（官方也是「摘到且非空才画」），避免空卡。
        assertNull(chatEntryOf(row, botName = "小助手"))
        // 摘要到了：映射成 CHANGES 条目，seq 带过去（按 seq 缓存对得上）。
        val summary = ChangesSummary(
            files = listOf(
                ChangedFile(path = "/ws/a.md", display = "a.md", added = 3, deleted = 1),
                ChangedFile(path = "/ws/b.png", display = "b.png", added = 0, deleted = 0, binary = true),
            ),
            total = 2, added = 3, deleted = 1,
        )
        val mapped = chatEntryOf(row, botName = "小助手", changesSummary = summary)!!
        assertEquals(ChatEntryKind.CHANGES, mapped.kind)
        assertEquals("changes:6", mapped.id)
        assertEquals(6L, mapped.seq)
        assertEquals(summary, mapped.changes)
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

    // ---- echo 插回位置（群聊气泡错位的回归）------------------------------------

    private fun entry(id: String, seq: Long?, timeMs: Long? = null) = ChatEntry(
        id = id,
        kind = ChatEntryKind.BOT,
        text = id,
        seq = seq,
        timeMs = timeMs,
    )

    /**
     * 群聊：尾部是 relay 回复（id 是 `<callId>:<index>`，没有数字 seq）。
     * 发送时刻晚于它们 → 新消息必须排在它们**下面**（列表末尾）。
     *
     * 以前从后往前跳过所有无 seq 的行，插到它们上面 —— 用户报的「群聊发消息气泡错位」。
     */
    @Test
    fun groupEchoGoesBelowTheRelayRepliesThatCameBeforeTheSend() {
        val entries = listOf(
            entry("user:10", seq = 10L, timeMs = 1_000L),
            entry("event:a:0", seq = null, timeMs = 1_200L),
            entry("event:a:1", seq = null, timeMs = 1_400L),
        )
        assertEquals(
            entries.size,
            echoInsertIndex(entries, anchor = null, anchorTimeMs = 2_000L),
        )
    }

    /** 群聊：发送之后才到的回复 → 新消息留在它们上面。 */
    @Test
    fun groupEchoStaysAboveRepliesThatArriveAfterTheSend() {
        val entries = listOf(
            entry("user:10", seq = 10L, timeMs = 1_000L),
            entry("event:b:0", seq = null, timeMs = 3_000L),
        )
        assertEquals(
            1,
            echoInsertIndex(entries, anchor = null, anchorTimeMs = 2_000L),
        )
    }

    /** 私聊：游标 seq 优先 —— 落定回答比发送游标新时，echo 在它上面。 */
    @Test
    fun privateEchoStaysAboveTheAnswerThatLandedAfterTheSend() {
        val entries = listOf(
            entry("user:10", seq = 10L, timeMs = 1_000L),
            entry("bot:11", seq = 11L, timeMs = 1_100L),
        )
        assertEquals(1, echoInsertIndex(entries, anchor = 10L, anchorTimeMs = 1_050L))
        // 回答在游标之前（历史行）：echo 排在最后。
        assertEquals(2, echoInsertIndex(entries, anchor = 11L, anchorTimeMs = 1_500L))
    }

    /** 全部比发送新（比如整批历史刚补进来）：echo 属于最上面。 */
    @Test
    fun echoGoesToTheTopWhenEverythingIsNewer() {
        val entries = listOf(
            entry("bot:20", seq = 20L, timeMs = 5_000L),
            entry("bot:21", seq = 21L, timeMs = 5_100L),
        )
        assertEquals(0, echoInsertIndex(entries, anchor = 10L, anchorTimeMs = 1_000L))
    }

    /** 空列表 / 老路径（没有任何锚）：追加到末尾。 */
    @Test
    fun emptyListAndMissingAnchorsFallBackToTheEnd() {
        assertEquals(0, echoInsertIndex(emptyList(), anchor = null, anchorTimeMs = null))
        val entries = listOf(entry("user:1", seq = 1L, timeMs = 100L))
        assertEquals(1, echoInsertIndex(entries, anchor = null, anchorTimeMs = null))
    }
}
