package com.dshm.bot.ui.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 动作脚本：引用值要转义干净，未知动作要被挡住。 */
class BrowserActionScriptsTest {

    @Test
    fun quotesSelectorsThatCarryQuotes() {
        val script = BrowserActionScripts.click("a[title='x']")
        // 选择器里的单引号必须被转义，否则 JS 字符串会被截断。
        assertTrue(script.contains("""var selector = 'a[title=\'x\']';"""))
    }

    @Test
    fun escapesNewlinesInTypedText() {
        val script = BrowserActionScripts.type("#q", "a\nb")
        assertTrue(script.contains("""var value = 'a\nb';"""))
    }

    @Test
    fun clampsScrollAmountAndDirection() {
        val script = BrowserActionScripts.script(
            "scroll",
            JSONObject().put("amount", 99999).put("direction", "bottom"),
        )
        assertTrue(script!!.contains("var amount = 5000;"))
        assertTrue(script.contains("""var dir = 'bottom';"""))
    }

    @Test
    fun unknownActionsAreRejected() {
        assertNull(BrowserActionScripts.script("hover", JSONObject()))
    }

    @Test
    fun buildsEachActionFromTheRequest() {
        val click = BrowserActionScripts.script("click", JSONObject().put("selector", "#go"))
        assertTrue(click!!.contains("""var selector = '#go';"""))
        val type = BrowserActionScripts.script(
            "type",
            JSONObject().put("selector", "#q").put("text", "hi"),
        )
        assertTrue(type!!.contains("""var value = 'hi';"""))
        assertEquals(true, type.contains("dispatchEvent(new Event('input'"))
    }
}
