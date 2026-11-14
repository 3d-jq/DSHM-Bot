package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.resolveSessionPath
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 产物推导：对齐 dsh-Go `deriveArtifacts` 的来源与「轮闭合才出卡、位置固定轮末」规则。 */
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
    fun `没有 present 时按官方 produced 口径补变更过的文件`() {
        val events = buildList {
            addAll(finishedTurn(2, 1, 20))
            add(toolCall(2, 2, "c1", "write", JSONObject().put("file_path", "report.pptx")))
            add(toolResult(3, 2, "c1", JSONObject().put("operation", "create").put("path", "/root/.dsh/bot/workspace/report.pptx")))
            // 编辑 / 覆盖也算（官方 produced：这轮改过的文件全算，不看是不是新建）。
            add(toolCall(4, 2, "c2", "write", JSONObject().put("file_path", "old.md")))
            add(toolResult(5, 2, "c2", JSONObject().put("operation", "update").put("path", "/root/.dsh/bot/workspace/old.md")))
        }

        val out = deriveArtifacts(events, turnTraces(events), cwd = "/root/.dsh/bot/workspace")

        assertEquals(
            listOf("/root/.dsh/bot/workspace/report.pptx", "/root/.dsh/bot/workspace/old.md"),
            out.single().files.map { it.path },
        )
        assertEquals(ArtifactOrigin.WRITTEN, out.single().files.first().origin)
    }

    @Test
    fun `md 文档也算交付物，不再按扩展名过滤`() {
        val events = buildList {
            addAll(finishedTurn(4, 1, 40))
            add(
                toolCall(
                    2, 4, "c1", "str_replace_editor",
                    JSONObject().put("command", "create").put("path", "/root/.dsh/bot/workspace/report.md"),
                ),
            )
            add(toolResult(3, 4, "c1", meta = null))
            // view 是读不算变更。
            add(
                toolCall(
                    4, 4, "c2", "str_replace_editor",
                    JSONObject().put("command", "view").put("path", "/root/.dsh/bot/workspace/other.md"),
                ),
            )
            add(toolResult(5, 4, "c2", meta = null))
        }

        val out = deriveArtifacts(events, turnTraces(events), cwd = "/root/.dsh/bot/workspace")

        assertEquals(listOf("/root/.dsh/bot/workspace/report.md"), out.single().files.map { it.path })
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
    fun `已声明交付但还没 end 的轮不出卡`() {
        // 官方口径：轮闭合才画卡，轮内交付只有工具行（present 一到就出卡的做法已撤销）。
        val events = listOf(
            event("turn/start", 1, JSONObject().put("turn", 5)),
            presented(9, 5, "/root/.dsh/bot/workspace/x.docx" to null),
        )

        val out = deriveArtifacts(events, turnTraces(events), cwd = null)
        assertTrue("轮没闭合不该出卡：$out", out.isEmpty())
    }

    @Test
    fun `已交付的轮 end 后出卡且位置固定在轮末`() {
        val events = buildList {
            add(event("turn/start", 1, JSONObject().put("turn", 6)))
            add(presented(9, 6, "/root/.dsh/bot/workspace/x.docx" to null))
            add(event("turn/end", 20, JSONObject().put("turn", 6)))
        }

        val out = deriveArtifacts(events, turnTraces(events), cwd = null)
        assertEquals(1, out.size)
        assertEquals(20L, out.single().seq) // 位置固定 = turn/end，不再贴 present 处
        assertEquals(listOf("/root/.dsh/bot/workspace/x.docx"), out.single().files.map { it.path })
        assertTrue(out.single().delivered)
    }

    @Test
    fun `同轮多次 present 聚合一张卡，同路径首现定序、描述取最新`() {
        val events = buildList {
            add(event("turn/start", 1, JSONObject().put("turn", 7)))
            add(presented(5, 7, "/root/.dsh/bot/workspace/a.docx" to "初稿", "/root/.dsh/bot/workspace/b.png" to null))
            add(presented(8, 7, "/root/.dsh/bot/workspace/a.docx" to "终稿"))
            add(event("turn/end", 12, JSONObject().put("turn", 7)))
        }

        val out = deriveArtifacts(events, turnTraces(events), cwd = null)
        assertEquals(1, out.size)
        assertEquals(12L, out.single().seq)
        assertEquals(
            listOf("/root/.dsh/bot/workspace/a.docx", "/root/.dsh/bot/workspace/b.png"),
            out.single().files.map { it.path },
        )
        assertEquals("终稿", out.single().files.first().description)
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
