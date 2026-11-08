package com.dshm.bot.ui.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 对话里的浏览器事件行：Bot 自己在开页面 / 点页面时要看得见。 */
class BrowserToolEventTest {

    private fun line(
        tool: String,
        args: JSONObject = JSONObject(),
        result: String = "",
        isError: Boolean = false,
    ) = toolEventLine(tool, args, result, isError, selfName = "大总管")

    @Test
    fun openNamesTheSite() {
        assertEquals(
            "打开了 example.com",
            line("browser_open", JSONObject().put("url", "https://www.example.com/a"))?.label,
        )
        assertEquals(
            "打开了 example.com",
            line("browser_open", JSONObject().put("url", "example.com"))?.label,
        )
        // 搜索词没有站点名，就只说「打开了网页」。
        assertEquals(
            "打开了网页",
            line("browser_open", JSONObject().put("url", "deepseek 最新"))?.label,
        )
        assertEquals(
            "没能打开网页",
            line("browser_open", JSONObject().put("url", "example.com"), isError = true)?.label,
        )
    }

    @Test
    fun openFallsBackToTheAddressThePageLandedOn() {
        val result = """{"url":"https://news.example.org/x","title":"News"}"""
        assertEquals("打开了 news.example.org", line("browser_open", result = result)?.label)
    }

    @Test
    fun eachActionHasItsLine() {
        assertEquals("读了网页", line("read_browser")?.label)
        assertEquals("点了页面上的元素", line("browser_click", JSONObject().put("selector", "#go"))?.label)
        assertEquals("填了网页上的输入框", line("browser_type")?.label)
        assertEquals("滚动了网页", line("browser_scroll")?.label)
        assertEquals("网页后退了", line("browser_back")?.label)
        assertEquals("网页前进了", line("browser_forward")?.label)
        assertEquals("刷新了网页", line("browser_reload")?.label)
        assertEquals("关了一个浏览器标签页", line("browser_close_tab")?.label)
    }
}
