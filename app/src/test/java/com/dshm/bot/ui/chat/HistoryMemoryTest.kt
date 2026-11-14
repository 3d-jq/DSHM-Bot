package com.dshm.bot.ui.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话历史内存侧的增量字节记账（P1 修复的核心）：
 * 追加/去重/裁剪都不再整表 `toString()` 量长度，落盘快照按增量维护的字节数裁。
 */
class HistoryMemoryTest {

    private fun item(id: String, text: String, seq: Long = -1L): JSONObject {
        val entry = JSONObject().put("id", id).put("kind", "bot").put("text", text)
        if (seq >= 0) entry.put("seq", seq)
        return entry
    }

    @Test
    fun `追加即得落盘快照且内容完整`() {
        val memory = HistoryMemory(initialRaw = null, initialSessionId = "sess-1")
        val snapshot = memory.append(item("m-1", "你好"))
        assertTrue(snapshot.items.contains("m-1"))
        assertTrue(snapshot.items.contains("你好"))
        assertEquals("sess-1", snapshot.sessionId)
    }

    @Test
    fun `同 id 与同 seq 的旧条去重`() {
        val memory = HistoryMemory(initialRaw = null, initialSessionId = "")
        memory.append(item("m-1", "第一次", seq = 7))
        memory.append(item("m-1", "回显覆盖", seq = 7))
        val snapshot = memory.append(item("m-2", "第二条"))
        val items = JSONArray(snapshot.items)
        assertEquals("同 id/seq 的回显必须替换而不是追加", 2, items.length())
        assertEquals("回显覆盖", items.optJSONObject(0)?.optString("text"))
    }

    @Test
    fun `空会话 id 在 load 里回 null`() {
        val memory = HistoryMemory(initialRaw = null, initialSessionId = "")
        memory.append(item("m-1", "你好"))
        val snapshot = memory.load()
        assertTrue(snapshot != null)
        assertNull(snapshot?.sessionId)
    }

    @Test
    fun `整表替换覆盖旧副本`() {
        val memory = HistoryMemory(initialRaw = null, initialSessionId = "old")
        memory.append(item("m-1", "旧的"))
        memory.replaceAll(JSONArray().put(item("m-2", "新的一条")), "new-sess")
        val snapshot = memory.append(item("m-3", "再来一条"))
        val items = JSONArray(snapshot.items)
        assertEquals(2, items.length())
        assertEquals("new-sess", snapshot.sessionId)
        assertTrue(!snapshot.items.contains("旧的"))
    }

    @Test
    fun `超限时从最老的开始裁并保留最新一段`() {
        // 每条序列化约 83 字符；上限 200 → 两条放得下（166），第三条进来时最老的被裁掉。
        val filler = "x".repeat(50)
        val memory = HistoryMemory(initialRaw = null, initialSessionId = "s", maxChars = 200)
        memory.append(item("a", filler))
        memory.append(item("b", filler))
        val snapshot = memory.append(item("c", filler))
        val items = JSONArray(snapshot.items)
        assertEquals("最老的一条要被裁掉", 2, items.length())
        assertEquals("b", items.optJSONObject(0)?.optString("id"))
        assertEquals("c", items.optJSONObject(1)?.optString("id"))
    }

    @Test
    fun `初始快照超上限整体放弃`() {
        val huge = JSONObject().put("id", "big").put("text", "y".repeat(HistoryMemory.MAX_CHARS + 1)).toString()
        val memory = HistoryMemory(initialRaw = huge, initialSessionId = "s")
        assertNull(memory.load())
    }
}
