package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.SessionEvent
import com.dshm.bot.data.remote.SessionEventLog
import com.dshm.bot.data.remote.TimelineRow
import com.dshm.bot.data.remote.TimelineRowPayload
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 四期 4c 影子双轨的比对器：只有它可信，4c 切换才敢做。 */
class ShadowDiffTest {

    private fun userRow(key: String, seq: Long, text: String, images: Int = 0, requestId: String? = null) =
        TimelineRow(
            key = key,
            kind = TimelineRow.KIND_USER,
            seq = seq,
            turn = null,
            timeMs = null,
            payload = TimelineRowPayload.User(text, emptyList(), images, requestId, false),
        )

    private fun botRow(key: String, seq: Long, text: String) = TimelineRow(
        key = key,
        kind = TimelineRow.KIND_BOT,
        seq = seq,
        turn = null,
        timeMs = null,
        payload = TimelineRowPayload.Bot(text, null, null, null, null, false),
    )

    @Test
    fun identicalRowsReportNoDifference() {
        val rows = listOf(userRow("user:2", 2, "你好"), botRow("bot:3", 3, "在的"))
        val result = ShadowDiff.compare(rows, rows)
        assertTrue(result.identical)
        assertTrue(result.summary().contains("一致"))
    }

    @Test
    fun aRowOnlyTheHostHasIsReportedMissing() {
        val result = ShadowDiff.compare(listOf(userRow("user:2", 2, "你好")), emptyList())
        assertFalse(result.identical)
        assertEquals(listOf("user:2"), result.missingInClient)
    }

    @Test
    fun aRowOnlyTheClientHasIsReportedExtra() {
        val result = ShadowDiff.compare(emptyList(), listOf(userRow("user:2", 2, "你好")))
        assertFalse(result.identical)
        assertEquals(listOf("user:2"), result.extraInClient)
    }

    @Test
    fun differentTextIsReportedAsPayloadMismatch() {
        val result = ShadowDiff.compare(listOf(botRow("bot:3", 3, "在的")), listOf(botRow("bot:3", 3, "在吗")))
        assertFalse(result.identical)
        assertTrue(result.payloadMismatch.containsKey("bot:3"))
        assertTrue(result.payloadMismatch["bot:3"]!!.contains("text"))
    }

    @Test
    fun rowsOutsideTheComparedKindsAreIgnored() {
        // inbox（排队行）来自 agent inbox、不是事件；divider 来自 part 边界——
        // 都不在客户端折叠范围内，比对时必须忽略，否则全是假差异。
        val inbox = TimelineRow(
            key = "inbox:1",
            kind = TimelineRow.KIND_INBOX,
            seq = null,
            turn = null,
            timeMs = null,
            payload = TimelineRowPayload.Inbox("排队", "next-turn", null, "1", "user", null, null),
        )
        val divider = TimelineRow(
            key = "divider:part:1",
            kind = TimelineRow.KIND_DIVIDER,
            seq = null,
            turn = null,
            timeMs = null,
            payload = TimelineRowPayload.Divider(1000),
        )
        val result = ShadowDiff.compare(listOf(inbox, divider), emptyList())
        assertTrue("inbox/divider 不该算差异", result.identical)
    }

    @Test
    fun aRangeWithAHoleIsNotCompared() {
        val log = SessionEventLog()
        log.put(SessionEvent(10, "user/message", null, null))
        log.put(SessionEvent(12, "user/message", null, null))
        // 10..12 中间缺 11 → 比出来必然是假差异，直接不比。
        assertNull(ShadowDiff.eventsForRange(log, 10, 12))
        // 连续的一段才可比（单点也是连续）。
        assertEquals(1, ShadowDiff.eventsForRange(log, 10, 10)?.size)
        assertEquals(1, ShadowDiff.eventsForRange(log, 12, 12)?.size)
    }

    @Test
    fun aCompleteRangeIsReturned() {
        val log = SessionEventLog()
        for (seq in 10L..14L) log.put(SessionEvent(seq, "user/message", null, null))
        val events = ShadowDiff.eventsForRange(log, 10, 14)
        assertEquals(5, events?.size)
        assertEquals(10L, events?.first()?.seq)
        assertEquals(14L, events?.last()?.seq)
    }
}
