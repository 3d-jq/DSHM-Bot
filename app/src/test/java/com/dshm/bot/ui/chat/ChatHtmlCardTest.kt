package com.dshm.bot.ui.chat

import com.dshm.bot.core.design.DshmColors
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `html` 组件的沙箱文档：CSP 在标记之前、主题变量两套名字都在。 */
class ChatHtmlCardTest {

    private val snippet = "<div class=\"bar\" style=\"color:var(--view-accent)\">789.5%</div>"

    @Test
    fun wrapsTheSnippetInASandboxedDocument() {
        val doc = chatHtmlDocument(snippet, DshmColors.Light)

        assertTrue(doc.startsWith("<!doctype html>"))
        assertTrue(doc.contains("Content-Security-Policy"))
        assertTrue(doc.contains("default-src 'none'"))
        assertTrue(doc.contains("script-src 'unsafe-inline'"))
        // 主题变量：新名字与旧蓝图用的 --bt-* 都要有，模型写哪套都能上色。
        assertTrue(doc.contains("--view-accent:#"))
        assertTrue(doc.contains("--bt-accent:#"))
        assertTrue(doc.contains("--view-ink:#"))
        assertTrue(doc.contains("--bt-ink:#"))
        // CSP 必须在模型标记之前，否则脚本先跑起来了。
        assertTrue(doc.indexOf("Content-Security-Policy") < doc.indexOf(snippet))
        assertTrue(doc.indexOf(snippet) < doc.indexOf("</body>"))
    }

    @Test
    fun reportsHeightThroughTheBridge() {
        val doc = chatHtmlDocument(snippet, DshmColors.Light)
        assertTrue(doc.contains("DshmView.report"))
        assertTrue(doc.contains("ResizeObserver"))
    }

    @Test
    fun readsTheOptionalHeight() {
        assertEquals(220f, chatHtmlHeight(JSONObject().put("height", 220)))
        assertNull(chatHtmlHeight(JSONObject().put("summary", "没有高度")))
        assertNull(chatHtmlHeight(JSONObject().put("height", -3)))
    }

    @Test
    fun keepsTheThemeOutOfTheMarkup() {
        // 注入的颜色必须是我们自己的，不能由模型片段决定。
        val doc = chatHtmlDocument("<style>:root{--view-accent:red}</style>$snippet", DshmColors.Light)
        val first = doc.indexOf("--view-accent:")
        assertTrue(first > 0)
        assertTrue(first < doc.indexOf("<body>"))
    }
}
