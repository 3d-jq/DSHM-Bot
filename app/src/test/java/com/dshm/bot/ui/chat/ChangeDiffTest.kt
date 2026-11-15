package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.SessionEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四期 4b-2：客户端从事件日志算改动（与 dsh-go 同数据源、同算法）。
 *
 * 载荷形状取自**真机真实会话日志**（DSH 标准模式的 `write`/`edit` 工具结果
 * `meta.diffs` / `meta.operation`）。
 */
class ChangeDiffTest {

    private fun call(seq: Long, name: String, callId: String, args: JSONObject) =
        SessionEvent(
            seq, "tool/call", 1000L,
            JSONObject().put("name", name).put("callId", callId).put("arguments", args),
        )

    private fun result(seq: Long, callId: String, meta: JSONObject?) =
        SessionEvent(
            seq, "tool/result", 1100L,
            JSONObject().put(
                "message",
                JSONObject().put("toolCallId", callId).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "ok"))),
            ).let { if (meta != null) it.put("meta", meta) else it },
        )

    @Test
    fun lineDiffMarksChangedLines() {
        val lines = computeLineDiff("a\nb\nc", "a\nB\nc")
        assertEquals(4, lines.size)
        assertEquals(DiffLineKind.CONTEXT, lines[0].kind)
        assertEquals(DiffLineKind.DELETE, lines[1].kind)
        assertEquals("-b", lines[1].text)
        assertEquals(DiffLineKind.ADD, lines[2].kind)
        assertEquals("+B", lines[2].text)
        assertEquals(DiffLineKind.CONTEXT, lines[3].kind)
    }

    @Test
    fun aWholeNewFileIsAllAdditions() {
        // 真机形状：write 的结果 meta = {operation:"create", path, diffs:[]}
        val events = listOf(
            call(137, "write", "c1", JSONObject().put("file_path", "/w/a.md").put("content", "l1\nl2\nl3")),
            result(138, "c1", JSONObject().put("operation", "create").put("path", "/w/a.md").put("diffs", JSONArray())),
        )
        val changes = changesFromEvents(events)
        assertEquals(1, changes.size)
        val change = changes.single()
        assertEquals("/w/a.md", change.path)
        assertTrue("新建", change.created)
        assertEquals(3, change.added)
        assertEquals(0, change.removed)
        assertEquals(3, change.hunks.single().lines.size)
        assertTrue(change.hunks.single().lines.all { it.kind == DiffLineKind.ADD })
    }

    @Test
    fun anEditUsesTheDiffsTheHostComputed() {
        // 真机形状：edit 的结果 meta = {path, diffs:[{path, oldText, newText}]}
        val events = listOf(
            call(229, "edit", "c2", JSONObject().put("file_path", "/w/b.md").put("old_string", "旧的一行").put("new_string", "新的一行")),
            result(
                230, "c2",
                JSONObject().put("path", "/w/b.md").put(
                    "diffs",
                    JSONArray().put(
                        JSONObject().put("path", "/w/b.md").put("oldText", "旧的一行").put("newText", "新的一行"),
                    ),
                ),
            ),
        )
        val change = changesFromEvents(events).single()
        assertEquals("/w/b.md", change.path)
        assertEquals(1, change.added)
        assertEquals(1, change.removed)
        val lines = change.hunks.single().lines
        assertEquals(DiffLineKind.DELETE, lines.first().kind)
        assertEquals(DiffLineKind.ADD, lines.last().kind)
    }

    @Test
    fun anEditWithoutHostDiffsFallsBackToTheToolArguments() {
        val events = listOf(
            call(229, "edit", "c3", JSONObject().put("file_path", "/w/c.md").put("old_string", "x").put("new_string", "y")),
            result(230, "c3", null),
        )
        val change = changesFromEvents(events).single()
        assertEquals(1, change.added)
        assertEquals(1, change.removed)
    }

    @Test
    fun argumentsGivenAsAStringAreStillUnderstood() {
        // 真机形状：部分模型把 arguments 序列化成 JSON **字符串**。
        val events = listOf(
            SessionEvent(
                1, "tool/call", 1000L,
                JSONObject().put("name", "edit").put("callId", "c4")
                    .put("arguments", """{"file_path":"/w/d.md","old_string":"x","new_string":"y"}"""),
            ),
            result(2, "c4", null),
        )
        val change = changesFromEvents(events).single()
        assertEquals("/w/d.md", change.path)
        assertEquals(1, change.added)
    }

    @Test
    fun aFailedToolIsNotAChange() {
        val events = listOf(
            call(1, "edit", "c5", JSONObject().put("file_path", "/w/e.md").put("old_string", "x").put("new_string", "y")),
            SessionEvent(
                2, "tool/result", 1100L,
                JSONObject().put("message", JSONObject().put("toolCallId", "c5").put("isError", true)),
            ),
        )
        assertTrue("失败的工具不算改动", changesFromEvents(events).isEmpty())
    }

    @Test
    fun aPureInsertIsPlacedAtItsLine() {
        val events = listOf(
            call(1, "str_replace_editor", "c6", JSONObject().put("command", "insert").put("path", "/w/f.md").put("insert_line", 3).put("new_str", "新行")),
            result(2, "c6", null),
        )
        val change = changesFromEvents(events).single()
        assertEquals(1, change.added)
        // insert_line 是 1-based 的插入点，新内容落在它之后 → 新起始行 = line + 1（dsh-go 同口径）
        assertEquals(4, change.hunks.single().newStart)
    }

    @Test
    fun severalEditsToOneFileBecomeOneChange() {
        val events = listOf(
            call(1, "edit", "c7", JSONObject().put("file_path", "/w/g.md").put("old_string", "a").put("new_string", "A")),
            result(2, "c7", null),
            call(3, "edit", "c8", JSONObject().put("file_path", "/w/g.md").put("old_string", "b").put("new_string", "B")),
            result(4, "c8", null),
        )
        val change = changesFromEvents(events).single()
        assertEquals("/w/g.md", change.path)
        assertEquals(2, change.added)
        assertEquals(2, change.removed)
        assertEquals(2, change.hunks.size)
    }

    @Test
    fun toolsThatDoNotTouchFilesAreIgnored() {
        val events = listOf(
            call(1, "bash", "c9", JSONObject().put("command", "ls")),
            result(2, "c9", null),
        )
        assertNotNull(changesFromEvents(events))
        assertTrue(changesFromEvents(events).isEmpty())
    }
}
