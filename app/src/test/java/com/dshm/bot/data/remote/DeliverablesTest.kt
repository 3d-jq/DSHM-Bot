package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliverablesTest {

    private fun event(data: Any?, seq: Long = 7L) = JSONObject().apply {
        put("type", DELIVERABLES_PRESENTED_EVENT)
        put("seq", seq)
        if (data != null) put("data", data)
    }

    private fun data(turn: Any, callId: Any, files: Any) = JSONObject().apply {
        put("turn", turn)
        put("callId", callId)
        put("files", files)
    }

    @Test
    fun `keeps the declaration order and the description`() {
        val files = JSONArray()
            .put(JSONObject().put("path", "/root/out/报告.pptx").put("description", "季度汇总"))
            .put(JSONObject().put("path", "trend.png"))
        val parsed = presentedFilesOf(event(data(2, "call-1", files), seq = 11L))

        assertEquals(2, parsed?.size)
        assertEquals("/root/out/报告.pptx", parsed!![0].path)
        assertEquals("季度汇总", parsed[0].description)
        assertEquals(0, parsed[0].index)
        assertEquals(2, parsed[0].turn)
        assertEquals("call-1", parsed[0].callId)
        assertEquals(11L, parsed[0].seq)
        assertEquals("trend.png", parsed[1].path)
        assertNull(parsed[1].description)
        assertEquals(1, parsed[1].index)
    }

    @Test
    fun `skips malformed entries but keeps the others`() {
        val files = JSONArray()
            .put(JSONObject().put("description", "没有路径"))
            .put(JSONObject().put("path", "   "))
            .put(JSONObject().put("path", "ok.pdf"))
            .put("not an object")
        val parsed = presentedFilesOf(event(data(1, "call-2", files)))

        assertEquals(1, parsed?.size)
        assertEquals("ok.pdf", parsed!![0].path)
        assertEquals(2, parsed[0].index)
    }

    @Test
    fun `rejects the whole event when turn or callId is wrong`() {
        val files = JSONArray().put(JSONObject().put("path", "a.txt"))
        assertNull(presentedFilesOf(event(data(0, "call-3", files))))
        assertNull(presentedFilesOf(event(data(1.5, "call-4", files))))
        assertNull(presentedFilesOf(event(data("two", "call-5", files))))
        assertNull(presentedFilesOf(event(data(1, "", files))))
        assertNull(presentedFilesOf(event(data(1, "call-6", "files"))))
        assertNull(presentedFilesOf(event(null)))
    }

    @Test
    fun `ignores other event types`() {
        val other = JSONObject().put("type", "tool/result").put("seq", 3L)
        assertNull(presentedFilesOf(other))
    }

    @Test
    fun `an empty file list still parses to nothing to draw`() {
        val parsed = presentedFilesOf(event(data(1, "call-7", JSONArray())))
        assertTrue(parsed!!.isEmpty())
    }

    @Test
    fun `file names come from both separators`() {
        assertEquals("报告.pptx", fileNameOf("/root/out/报告.pptx"))
        assertEquals("报告.pptx", fileNameOf("out\\报告.pptx"))
        assertEquals("报告.pptx", fileNameOf("报告.pptx"))
        assertEquals("pptx", fileExtensionOf("/root/out/报告.pptx"))
        assertEquals("", fileExtensionOf("/root/out/README"))
        assertEquals("", fileExtensionOf("/root/out/.gitignore"))
    }
}
