package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.resolveSessionPath
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 产物推导：对齐 dsh-Go `deriveArtifacts` 的四种来源与「只给完成轮」规则。 */
class ArtifactDerivationTest {

    private fun event(type: String, seq: Long, data: JSONObject, time: Long = 1_000L * seq) =
        JSONObject()
            .put("type", type)
            .put("seq", seq)
            .put("time", time)
            .put("data", data)

    private fun toolCall(seq: Long, turn: Int, callId: String, name: String, args: JSONObject) =
        event("tool/call", seq, JSONObject().put("turn", turn).put("callId", callId).put("name", name).put("arguments", args))

    private fun toolResult(seq: Long, turn: Int, callId: String, meta: JSONObject?, isError: Boolean = false, text: String = "ok") =
        event(
            "tool/result",
            seq,
            JSONObject()
                .put("turn", turn)
                .put("message", JSONObject().put("toolCallId", callId).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))))
                .put("isError", isError)
                .also { if (meta != null) it.put("meta", meta) },
        )

    private fun assistant(seq: Long, turn: Int, text: String) =
        event(
            "assistant/message",
            seq,
            JSONObject().put("turn", turn).put(
                "message",
                JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))),
            ),
        )

    private fun presented(seq: Long, turn: Int, vararg files: Pair<String, String?>) =
        event(
            "deliverables/presented",
            seq,
            JSONObject().put("turn", turn).put("callId", "call-$turn").put(
                "files",
                JSONArray().apply {
                    files.forEach { (path, description) ->
                        put(JSONObject().put("path", path).also { if (description != null) it.put("description", description) })
                    }
                },
            ),
        )

    private fun finishedTurn(turn: Int, startSeq: Long, endSeq: Long) = listOf(
        event("turn/start", startSeq, JSONObject().put("turn", turn)),
        event("turn/end", endSeq, JSONObject().put("turn", turn)),
    )

    @Test
    fun `有 present 声明时只用交付物`() {
        val events = buildList {
            addAll(finishedTurn(1, 1, 10))
            add(toolCall(2, 1, "c1", "write", JSONObject().put("file_path", "notes.bin")))
            add(toolResult(3, 1, "c1", JSONObject().put("operation", "create").put("path", "/root/.dsh/bot/workspace/notes.bin")))
            add(presented(9, 1, "/root/.dsh/bot/workspace/final.docx" to "定稿"))
        }

        val out = deriveArtifacts(events, turnTraces(events), cwd = "/root/.dsh/bot/workspace")

        assertEquals(1, out.size)
        assertEquals(10L, out.single().seq)
        assertTrue(out.single().delivered)
        assertEquals(listOf("/root/.dsh/bot/workspace/final.docx"), out.single().files.map { it.path })
        assertEquals(ArtifactOrigin.DELIVERED, out.single().files.single().origin)
        assertEquals("定稿", out.single().files.single().description)
    }

    @Test
    fun `没有 present 时用工具结果里的 meta path 补新建文件`() {
        val events = buildList {
            addAll(finishedTurn(2, 1, 20))
            add(toolCall(2, 2, "c1", "write", JSONObject().put("file_path", "report.pptx")))
            add(toolResult(3, 2, "c1", JSONObject().put("operation", "create").put("path", "/root/.dsh/bot/workspace/report.pptx")))
            // 覆盖写（没有 operation=create）不算新建。
            add(toolCall(4, 2, "c2", "write", JSONObject().put("file_path", "old.md")))
            add(toolResult(5, 2, "c2", JSONObject().put("operation", "update").put("path", "/root/.dsh/bot/workspace/old.md")))
        }

        val out = deriveArtifacts(events, turnTraces(events), cwd = "/root/.dsh/bot/workspace")

        assertEquals(listOf("/root/.dsh/bot/workspace/report.pptx"), out.single().files.map { it.path })
        assertEquals(ArtifactOrigin.WRITTEN, out.single().files.single().origin)
    }

    @Test
    fun `回复里链接的文件要被本轮工具提到过才算产物`() {
        val linked = "/root/.dsh/bot/workspace/chart.png"
        val notLinked = "/root/.dsh/bot/workspace/other.png"
        val events = buildList {
            addAll(finishedTurn(3, 1, 30))
            add(toolCall(2, 3, "c1", "bash", JSONObject().put("command", "python draw.py chart.png")))
            add(toolResult(3, 3, "c1", meta = null, text = "wrote chart.png"))
            add(assistant(4, 3, "图好了：![chart](chart.png)\n还有 ![other](other.png)"))
        }

        val out = deriveArtifacts(events, turnTraces(events), cwd = "/root/.dsh/bot/workspace")

        assertEquals(listOf(linked), out.single().files.map { it.path })
        assertEquals(ArtifactOrigin.LINKED, out.single().files.single().origin)
    }

    @Test
    fun `输出目录里的文件按本轮时间窗兜底`() {
        val events = buildList {
            addAll(finishedTurn(4, 1, 40))
        }
        val outputs = listOf(
            OutputFile("/root/.dsh/bot/workspace/in_window.png", modifiedMs = 2_500L),
            OutputFile("/root/.dsh/bot/workspace/too_late.png", modifiedMs = 99_999L),
        )

        val out = deriveArtifacts(events, turnTraces(events), cwd = "/root/.dsh/bot/workspace", outputs = outputs)

        assertEquals(listOf("/root/.dsh/bot/workspace/in_window.png"), out.single().files.map { it.path })
        assertEquals(ArtifactOrigin.OUTPUT, out.single().files.single().origin)
    }

    @Test
    fun `已声明交付但还没 end 的轮也出卡，贴 present 处`() {
        val events = listOf(
            event("turn/start", 1, JSONObject().put("turn", 5)),
            presented(9, 5, "/root/.dsh/bot/workspace/x.docx" to null),
        )

        val out = deriveArtifacts(events, turnTraces(events), cwd = null)
        assertEquals(1, out.size)
        assertEquals(9L, out.single().seq) // 卡先出现在 present 位置
        assertEquals(listOf("/root/.dsh/bot/workspace/x.docx"), out.single().files.map { it.path })
    }

    @Test
    fun `已交付的轮 end 后卡位置前移到 turn end`() {
        val events = buildList {
            add(event("turn/start", 1, JSONObject().put("turn", 6)))
            add(presented(9, 6, "/root/.dsh/bot/workspace/x.docx" to null))
            add(event("turn/end", 20, JSONObject().put("turn", 6)))
        }

        val out = deriveArtifacts(events, turnTraces(events), cwd = null)
        assertEquals(1, out.size)
        assertEquals(20L, out.single().seq) // end 之后卡落到轮末
    }

    @Test
    fun `多轮按 turn end 排序`() {
        val events = buildList {
            addAll(finishedTurn(2, 20, 25))
            add(presented(24, 2, "/root/.dsh/bot/workspace/b.png" to null))
            addAll(finishedTurn(1, 1, 10))
            add(presented(9, 1, "/root/.dsh/bot/workspace/a.png" to null))
        }

        val out = deriveArtifacts(events, turnTraces(events), cwd = "/root/.dsh/bot/workspace")

        assertEquals(listOf("/root/.dsh/bot/workspace/a.png", "/root/.dsh/bot/workspace/b.png"), out.flatMap { it.files.map { f -> f.path } })
        assertEquals(listOf(10L, 25L), out.map { it.seq })
    }

    @Test
    fun `相对路径按会话工作目录解析`() {
        assertEquals("/root/.dsh/bot/workspace/a/b.png", resolveSessionPath("a/b.png", "/root/.dsh/bot/workspace"))
        assertEquals("/root/.dsh/bot/workspace/a.png", resolveSessionPath("./a.png", "/root/.dsh/bot/workspace"))
        assertEquals("/root/ab/c.png", resolveSessionPath("a/../ab/c.png", "/root"))
        assertEquals("C:\\tmp\\x.png", resolveSessionPath("C:\\tmp\\x.png", "/root"))
    }
}
