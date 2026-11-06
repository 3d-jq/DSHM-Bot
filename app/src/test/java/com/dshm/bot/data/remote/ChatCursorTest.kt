package com.dshm.bot.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天历史的分页游标（对齐 ds-bot `src/host/parts.js` 的 `chatPage` / `partPage`）。
 */
class ChatCursorTest {

    @Test
    fun cursorInsideOnePartCarriesBeforeSeq() {
        val cursor = ChatCursor.fromJson(JSONObject().put("part", 0).put("beforeSeq", 123))
        assertEquals(123L, cursor?.beforeSeq)
        assertEquals(0, cursor?.part)
        assertTrue(cursor?.hasMore == true)
    }

    @Test
    fun cursorForThePreviousPartHasOnlyPart() {
        val cursor = ChatCursor.fromJson(JSONObject().put("part", 2))
        assertNull(cursor?.beforeSeq)
        assertEquals(2, cursor?.part)
        assertTrue(cursor?.hasMore == true)
    }

    @Test
    fun noCursorMeansNoEarlierMessages() {
        assertNull(ChatCursor.fromJson(null))
        assertNull(ChatCursor.fromJson(JSONObject()))
        assertNull(ChatCursor.fromJson(JSONObject().put("part", JSONObject.NULL)))
    }

    @Test
    fun cursorIsSentBackInTheEnginesShape() {
        val json = ChatCursor(beforeSeq = 42, part = 1).toJson()
        assertEquals(42L, json?.getLong("beforeSeq"))
        assertEquals(1, json?.getInt("part"))
        // 两个都没有就不传游标。
        assertNull(ChatCursor().toJson())
    }

    @Test
    fun chatPageIsParsedWithItsCursor() {
        val page = parseChatPage(
            JSONObject()
                .put("sessionId", "s-1")
                .put("items", org.json.JSONArray())
                .put("cursor", JSONObject().put("part", 0).put("beforeSeq", 9)),
        )
        assertEquals("s-1", page.sessionId)
        assertEquals(9L, page.cursor?.beforeSeq)
        assertTrue(page.cursor?.hasMore == true)
    }

    @Test
    fun chatPageWithoutCursorHasNothingMore() {
        val page = parseChatPage(JSONObject().put("sessionId", "s-1"))
        assertEquals("s-1", page.sessionId)
        assertFalse(page.cursor?.hasMore == true)
        assertNull(parseChatPage(null).cursor)
    }
}
