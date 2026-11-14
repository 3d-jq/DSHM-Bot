package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间线行解析器（`docs/phase1-design.md` §2 冻结 schema）：
 * 各 kind 的载荷形状、缺字段回落、payload 形状不对整行丢弃、未知 kind 忽略。
 */
class TimelineRowsTest {

    private fun row(
        key: String,
        kind: String,
        payload: Any?,
        seq: Any? = 7,
        turn: Any? = 2,
        time: Any? = 1_700_000_000_000L,
    ): JSONObject {
        val json = JSONObject().put("key", key).put("kind", kind)
        when (payload) {
            null -> {}
            else -> json.put("payload", payload)
        }
        when (seq) {
            null -> json.put("seq", JSONObject.NULL)
            is Number -> json.put("seq", seq)
        }
        when (turn) {
            null -> json.put("turn", JSONObject.NULL)
            is Number -> json.put("turn", turn)
        }
        when (time) {
            null -> {}
            is Number -> json.put("timeMs", time)
        }
        return json
    }

    @Test
    fun userRowCarriesTextBlocksImagesAndRequestId() {
        val payload = JSONObject()
            .put("text", "看看这张图")
            .put(
                "blocks",
                JSONArray()
                    .put(JSONObject().put("type", "image").put("name", "a.png"))
                    .put(JSONObject().put("type", "text")),
            )
            .put("images", 1)
            .put("requestId", "req-1")
        val parsed = parseTimelineRow(row("user:7", "user", payload))

        assertTrue(parsed != null)
        val user = parsed!!.payload as TimelineRowPayload.User
        assertEquals("看看这张图", user.text)
        assertEquals(2, user.blocks.size)
        assertEquals("image", user.blocks.first().optString("type"))
        assertEquals(1, user.images)
        assertEquals("req-1", user.requestId)
        assertEquals(7L, parsed.seq)
        assertEquals(2, parsed.turn)
        assertEquals(1_700_000_000_000L, parsed.timeMs)
    }

    @Test
    fun botRowCarriesAuthorWholeAndDuration() {
        val payload = JSONObject()
            .put("text", "好了")
            .put("author", "小助手")
            .put("botId", "bot-1")
            .put("durationMs", 3_000L)
            .put("whole", JSONObject().put("length", 9).put("file", JSONObject().put("name", "长文")))
        val parsed = parseTimelineRow(row("bot:8", "bot", payload))

        val bot = parsed!!.payload as TimelineRowPayload.Bot
        assertEquals("好了", bot.text)
        assertEquals("小助手", bot.author)
        assertEquals("bot-1", bot.botId)
        assertEquals(3_000L, bot.durationMs)
        assertEquals("长文", bot.whole?.optJSONObject("file")?.optString("name"))
    }

    @Test
    fun toolRowCarriesStateAndCallId() {
        val running = parseTimelineRow(
            row("tool:9", "tool", JSONObject().put("tool", "present").put("state", "running").put("label", "正在交付 2 个文件…").put("callId", "p1")),
        )
        val tool = running!!.payload as TimelineRowPayload.Tool
        assertEquals("present", tool.tool)
        assertEquals("running", tool.state)
        assertEquals("p1", tool.callId)
    }

    @Test
    fun artifactRowCarriesFilesAndDelivered() {
        val payload = JSONObject()
            .put(
                "files",
                JSONArray()
                    .put(JSONObject().put("path", "/ws/a.docx").put("description", "终稿"))
                    // 缺 path 的文件条目跳过（行不丢）。
                    .put(JSONObject().put("description", "无路径"))
                    .put(JSONObject().put("path", "/ws/b.png")),
            )
            .put("delivered", true)
            .put("turn", 3)
        val parsed = parseTimelineRow(row("present:3", "artifact", payload))

        val artifact = parsed!!.payload as TimelineRowPayload.Artifact
        assertTrue(artifact.delivered)
        assertEquals(listOf("/ws/a.docx", "/ws/b.png"), artifact.files.map { it.path })
        assertEquals("终稿", artifact.files.first().description)
    }

    @Test
    fun eventDividerInboxRowsParse() {
        val event = parseTimelineRow(
            row("retry:11", "event", JSONObject().put("eventKind", "retry").put("code", "rate_limited").put("name", "小助手")),
        )
        val eventPayload = event!!.payload as TimelineRowPayload.Event
        assertEquals("retry", eventPayload.eventKind)
        assertEquals("rate_limited", eventPayload.code)

        val divider = parseTimelineRow(row("divider:0", "divider", JSONObject().put("timeMs", 123L), seq = null))
        assertTrue(divider!!.payload is TimelineRowPayload.Divider)
        assertNull(divider.seq)

        val inbox = parseTimelineRow(
            row("inbox:i-1", "inbox", JSONObject().put("text", "排队消息").put("slot", "next-turn").put("requestId", "req-9").put("itemId", "i-1"), seq = null),
        )
        val inboxPayload = inbox!!.payload as TimelineRowPayload.Inbox
        assertEquals("next-turn", inboxPayload.slot)
        assertEquals("req-9", inboxPayload.requestId)
        assertNull(inbox.seq)
    }

    @Test
    fun viewAndAnsweredRowsKeepRawPayload() {
        val view = parseTimelineRow(row("view:12", "view", JSONObject().put("title", "卡片")))
        val viewPayload = view!!.payload as TimelineRowPayload.View
        assertEquals("卡片", viewPayload.view.optString("title"))

        val answered = parseTimelineRow(row("answer:13", "answered", JSONObject().put("questions", JSONArray())))
        assertTrue(answered!!.payload is TimelineRowPayload.Answered)
    }

    @Test
    fun unknownKindIsIgnoredForForwardCompatibility() {
        assertNull(parseTimelineRow(row("future:1", "quantum", JSONObject().put("x", 1))))
    }

    @Test
    fun malformedRowsAreDroppedNotThrown() {
        // 缺 key。
        assertNull(parseTimelineRow(JSONObject().put("kind", "user").put("payload", JSONObject())))
        // 缺 kind。
        assertNull(parseTimelineRow(JSONObject().put("key", "user:1").put("payload", JSONObject())))
        // payload 是字符串（形状不对）→ 整行丢弃。
        assertNull(parseTimelineRow(row("user:1", "user", "纯文本不合法")))
        // payload 是数字 → 丢。
        assertNull(parseTimelineRow(row("bot:1", "bot", 42)))
        // 行本身不是对象 → 丢。
        assertNull(parseTimelineRow(null))
        // 数组里混入非对象元素 → 跳过，不抛。
        assertTrue(parseTimelineRows(JSONArray().put("不是对象")).isEmpty())
    }

    @Test
    fun missingFieldsFallBackToDefaults() {
        // 全缺字段的 user 行：text 空、blocks 空、images 0、requestId null，行保留。
        val parsed = parseTimelineRow(row("user:2", "user", JSONObject()))
        val user = parsed!!.payload as TimelineRowPayload.User
        assertEquals("", user.text)
        assertTrue(user.blocks.isEmpty())
        assertEquals(0, user.images)
        assertNull(user.requestId)

        // tool 行缺 state 回落 ok。
        val tool = parseTimelineRow(row("tool:3", "tool", JSONObject().put("tool", "write")))!!.payload as TimelineRowPayload.Tool
        assertEquals("ok", tool.state)

        // time 字段回落（timeMs 缺席时认 time）。
        val json = row("bot:4", "bot", JSONObject(), time = null).put("time", 555L)
        assertEquals(555L, parseTimelineRow(json)!!.timeMs)
    }

    @Test
    fun seqTurnTimeTreatAbsentNullAndNegativeAsNull() {
        val absent = parseTimelineRow(row("user:1", "user", JSONObject(), seq = null, turn = null))
        assertNull(absent!!.seq)
        assertNull(absent.turn)
        val negative = parseTimelineRow(row("user:1", "user", JSONObject(), seq = -5, turn = -1))
        assertNull(negative!!.seq)
        assertNull(negative.turn)
    }

    @Test
    fun snapshotParsingCarriesRowsCursorAndRoomState() {
        val snapshot = parseTimelineSnapshot(
            JSONObject()
                .put("sessionId", "s-1")
                .put("seq", 100)
                .put("rows", JSONArray().put(row("user:1", "user", JSONObject().put("text", "hi"))))
                .put("cursor", JSONObject().put("beforeSeq", 40))
                .put("running", false)
                .put("speaking", "member-1"),
        )
        assertTrue(snapshot != null)
        assertEquals("s-1", snapshot!!.sessionId)
        assertEquals(100L, snapshot.seq)
        assertEquals(listOf("user:1"), snapshot.rows.map { it.key })
        assertEquals(40L, snapshot.cursor?.optLong("beforeSeq"))
        assertEquals(false, snapshot.running)
        assertEquals("member-1", snapshot.speaking)

        // 群聊形状：roomId。
        val room = parseTimelineSnapshot(JSONObject().put("roomId", "room-1").put("rows", JSONArray()))
        assertEquals("room-1", room!!.roomId)
        assertNull(room.sessionId)
    }

    @Test
    fun malformedSnapshotReturnsNull() {
        assertNull(parseTimelineSnapshot(null))
        assertNull(parseTimelineSnapshot(JSONObject().put("rows", "不是数组").put("sessionId", JSONArray())))
    }

    @Test
    fun feedFrameParsingHandlesRowsAndReset() {
        val frame = parseTimelineFeedFrame(
            JSONObject()
                .put("botId", "bot-1")
                .put("seq", 9)
                .put("rows", JSONArray().put(row("bot:9", "bot", JSONObject().put("text", "x")))),
        )
        assertEquals(false, frame!!.reset)
        assertEquals("bot-1", frame.botId)
        assertEquals(9L, frame.seq)
        assertEquals(listOf("bot:9"), frame.rows.map { it.key })

        val reset = parseTimelineFeedFrame(JSONObject().put("reset", true))
        assertTrue(reset!!.reset)
        assertTrue(reset.rows.isEmpty())

        assertNull(parseTimelineFeedFrame(null))
        assertNull(parseTimelineFeedFrame(JSONObject().put("rows", JSONObject())))
    }

    @Test
    fun rowListParsingSkipsBrokenRows() {
        val array = JSONArray()
            .put("字符串不是行")
            .put(row("user:1", "user", JSONObject(), seq = 1))
            .put(JSONObject().put("key", "user:2").put("kind", "nope")) // 未知 kind
        val rows = parseTimelineRows(array)
        assertEquals(listOf("user:1"), rows.map { it.key })
        assertEquals(listOf(1L), rows.map { it.seq })
    }
}
