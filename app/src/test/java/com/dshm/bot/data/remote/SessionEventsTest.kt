package com.dshm.bot.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 四期 4a：durable 事件日志的连续性语义（对齐 dsh-Go `_log` / `_onFrame` / `_fill`）。 */
class SessionEventsTest {

    private fun event(seq: Long, type: String = "user/message") = SessionEvent(seq, type, null, null)

    @Test
    fun contiguousEventsAdvanceTheCursor() {
        val log = SessionEventLog()
        assertNull("正好接上不算缺口", log.put(event(10)))
        assertEquals(10L, log.cursor)
        assertNull(log.put(event(11)))
        assertEquals(11L, log.cursor)
    }

    @Test
    fun duplicateIsIdempotentAndDoesNotMoveTheCursor() {
        val log = SessionEventLog()
        log.put(event(10))
        log.put(event(10))
        assertEquals(1, log.size)
        assertEquals(10L, log.cursor)
    }

    @Test
    fun aJumpReportsTheGapAndHoldsTheCursor() {
        val log = SessionEventLog()
        log.put(event(10))
        val gap = log.put(event(15))
        assertEquals(11L..14L, gap)
        assertEquals("缺口期间游标不能前进（否则带洞渲染）", 10L, log.cursor)
        assertEquals("跳号的事件仍然收下，等补洞后一起前进", 2, log.size)
    }

    @Test
    fun fillingTheGapAdvancesThroughTheHeldEvent() {
        val log = SessionEventLog()
        log.put(event(10))
        log.put(event(15))
        log.merge(listOf(event(11), event(12), event(13), event(14)))
        assertEquals("补洞后应一路推进到已缓存的 15", 15L, log.cursor)
    }

    @Test
    fun anOutOfOrderOldEventDoesNotMoveTheCursor() {
        val log = SessionEventLog()
        log.put(event(10))
        log.put(event(11))
        assertNull(log.put(event(5)))
        assertEquals(11L, log.cursor)
        assertEquals(3, log.size)
    }

    @Test
    fun resetSeedsFromTheGivenWindow() {
        val log = SessionEventLog()
        log.put(event(10))
        log.put(event(15))
        log.reset(listOf(event(100), event(101)))
        assertEquals(101L, log.cursor)
        assertEquals(2, log.size)
    }

    @Test
    fun eventsInReturnsAnAscendingRange() {
        val log = SessionEventLog()
        log.merge(listOf(event(3), event(1), event(2), event(5)))
        assertEquals(listOf(1L, 2L, 3L), log.eventsIn(1, 3).map { it.seq })
        assertEquals(listOf(5L), log.eventsIn(4, null).map { it.seq })
        assertEquals(emptyList<Long>(), log.eventsIn(10, 20).map { it.seq })
    }

    @Test
    fun frameParserKeepsDataOnlyWhenPresent() {
        val frame = parseSessionEventFrame(
            JSONObject()
                .put("sessionId", "s-1")
                .put("seq", 7)
                .put("type", "user/message")
                .put("time", 1000)
                .put("data", JSONObject().put("x", 1)),
        )
        assertNotNull(frame)
        assertEquals("s-1", frame!!.sessionId)
        assertEquals(7L, frame.event.seq)
        assertEquals("user/message", frame.event.type)
        assertEquals(1000L, frame.event.timeMs)
        assertNotNull("折叠需要的事件要带 data", frame.event.data)

        val placeholder = parseSessionEventFrame(
            JSONObject().put("sessionId", "s-1").put("seq", 8).put("type", "thinking/delta"),
        )
        assertNotNull(placeholder)
        assertNull("占位事件不带 data", placeholder!!.event.data)
    }

    @Test
    fun aFrameWithoutSeqOrSessionIsRejected() {
        assertNull(parseSessionEventFrame(JSONObject().put("sessionId", "s-1").put("type", "user/message")))
        assertNull(parseSessionEventFrame(JSONObject().put("seq", 3)))
        assertNull(parseSessionEventFrame(null))
    }

    @Test
    fun theEventsResponseIsParsedAscending() {
        val json = JSONObject().put(
            "events",
            org.json.JSONArray()
                .put(JSONObject().put("seq", 12).put("type", "assistant/message"))
                .put(JSONObject().put("seq", 11).put("type", "user/message"))
                .put(JSONObject().put("type", "malformed")),
        )
        assertEquals(listOf(11L, 12L), parseSessionEventsResponse(json).map { it.seq })
    }
}
