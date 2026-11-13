package com.dshm.bot.service

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 列表 collect 的循环逻辑：翻页、去重、提前停止、页数上限。 */
class PhoneCollectTest {

    @Test
    fun collectsAcrossPagesAndDeduplicates() = runBlocking {
        val pages = listOf(
            listOf("A", "B"),
            listOf("B", "C"),
            listOf("C", "D"),
            listOf("D"), // 无新内容，第二次收口
        )
        var current = 0
        val out = PhoneCollect.collect(
            request = JSONObject().put("pages", 10),
            findList = { android.graphics.Rect(0, 0, 100, 500) },
            readPage = { pages.getOrElse(current) { emptyList() } },
            nextPage = { current++; current < pages.size },
        )
        assertEquals(listOf("A", "B", "C", "D"), out.text.lines().drop(1).filter { it.isNotBlank() }.map { it.removePrefix("1. ").removePrefix("2. ").removePrefix("3. ").removePrefix("4. ") })
        assertTrue(out.text.contains("收集 4 项"))
    }

    @Test
    fun stopsOnUntilMatch() = runBlocking {
        val out = PhoneCollect.collect(
            request = JSONObject().put("pages", 10).put("until", "目标"),
            findList = { android.graphics.Rect(0, 0, 100, 500) },
            readPage = { listOf("第一项", "目标项", "第三项") },
            nextPage = { true },
        )
        assertTrue(out.text.contains("命中「目标」"))
        assertTrue("命中后不该继续翻页", out.pages == 1)
    }

    @Test
    fun respectsPageLimit() = runBlocking {
        var page = 0
        val out = PhoneCollect.collect(
            request = JSONObject().put("pages", 3),
            findList = { android.graphics.Rect(0, 0, 100, 500) },
            readPage = { page++; listOf("项目 $page") },
            nextPage = { true },
        )
        assertEquals(3, out.pages)
        assertTrue(out.text.contains("达到页数上限"))
    }

    @Test
    fun noListReturnsHint() = runBlocking {
        val out = PhoneCollect.collect(
            request = JSONObject(),
            findList = { null },
            readPage = { emptyList() },
            nextPage = { false },
        )
        assertTrue(out.text.contains("没有找到可滚动的列表"))
        assertEquals("no-list", out.stop)
    }
}
