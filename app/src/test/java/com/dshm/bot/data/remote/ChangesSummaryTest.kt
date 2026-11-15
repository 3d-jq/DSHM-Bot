package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/api/changes.summary` 响应 → [ChangesSummary] 的纯函数解析。
 *
 * 形状来自 DSH `dsh-client-ui-deliverables`（宿主半直接 `Response.json({turn, files,
 * total, added, deleted})` —— 裸 JSON）；`{ok,value}` 信封只在变体宿主兜底。
 * 覆盖 binary/oversized/空 files/缺字段等边界。
 */
class ChangesSummaryTest {

    @Test
    fun parsesBareJsonWithFilesTotalsAndFlags() {
        val json = JSONObject()
            .put("turn", 2)
            .put("total", 3)
            .put("added", 12)
            .put("deleted", 4)
            .put(
                "files",
                JSONArray()
                    .put(JSONObject().put("path", "src/a.js").put("display", "src/a.js").put("added", 10).put("deleted", 2))
                    .put(
                        JSONObject().put("path", "assets/b.png").put("display", "assets/b.png")
                            .put("added", 0).put("deleted", 0).put("binary", true),
                    )
                    .put(
                        JSONObject().put("path", "big.bin").put("display", "big.bin")
                            .put("added", 2).put("deleted", 2).put("oversized", true),
                    ),
            )
        val summary = parseChangesSummary(json)!!
        assertEquals(3, summary.total)
        assertEquals(12, summary.added)
        assertEquals(4, summary.deleted)
        assertEquals(listOf("src/a.js", "assets/b.png", "big.bin"), summary.files.map { it.path })
        assertEquals(10, summary.files[0].added)
        assertEquals(2, summary.files[0].deleted)
        assertFalse(summary.files[0].binary)
        assertTrue(summary.files[1].binary)
        assertFalse(summary.files[1].oversized)
        assertTrue(summary.files[2].oversized)
        assertNull("total != 1 时不暴露单文件", summary.singleFile)
    }

    @Test
    fun singleFileSummaryExposesItForTheTitle() {
        val summary = parseChangesSummary(
            JSONObject()
                .put("turn", 5)
                .put("total", 1)
                .put("added", 1)
                .put("deleted", 0)
                .put(
                    "files",
                    JSONArray().put(
                        JSONObject().put("path", "/ws/only.md").put("display", "only.md")
                            .put("added", 1).put("deleted", 0),
                    ),
                ),
        )!!
        assertEquals("only.md", summary.singleFile?.display)
    }

    @Test
    fun emptyFilesIsAValidEmptySummary() {
        val summary = parseChangesSummary(JSONObject().put("total", 0).put("files", JSONArray()))!!
        assertTrue(summary.files.isEmpty())
        assertEquals(0, summary.total)
        assertEquals(0, summary.added)
        assertEquals(0, summary.deleted)
        assertNull(summary.singleFile)
    }

    @Test
    fun missingFieldsFallBackAndBadEntriesDrop() {
        // files 缺席 → 空表；total 回落文件数（0）；added/deleted 回落 0。
        val bare = parseChangesSummary(JSONObject())!!
        assertTrue(bare.files.isEmpty())
        assertEquals(0, bare.total)
        assertEquals(0, bare.added)
        assertEquals(0, bare.deleted)

        // 缺 path 的条目丢弃；display 缺席回落 path；total 缺席回落「列出的文件数」。
        val summary = parseChangesSummary(
            JSONObject().put(
                "files",
                JSONArray()
                    .put(JSONObject().put("display", "无路径"))
                    .put(JSONObject().put("path", "/ws/x.txt")),
            ),
        )!!
        assertEquals(listOf("/ws/x.txt"), summary.files.map { it.path })
        assertEquals("/ws/x.txt", summary.files.single().display)
        assertEquals(1, summary.total)
    }

    @Test
    fun acceptsAnOkValueEnvelopeAsAFallbackAndRejectsNull() {
        val enveloped = JSONObject()
            .put("ok", true)
            .put(
                "value",
                JSONObject().put("total", 1)
                    .put("files", JSONArray().put(JSONObject().put("path", "a.js").put("display", "a.js"))),
            )
        assertEquals(1, parseChangesSummary(enveloped)!!.total)
        assertNull(parseChangesSummary(null))
    }
}
