package com.dshm.bot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 读页回执：`evaluateJavascript` 的返回值是「JSON 字符串再编码一层」。 */
class BotBrowserTest {

    @Test
    fun unpacksTheDoubleEncodedPage() {
        val payload = """{"url":"https://example.com","title":"Example","format":"markdown","text":"hi","items":[]}"""
        val encoded = org.json.JSONObject.quote(payload)
        val page = decodeJsJson(encoded)
        assertEquals("https://example.com", page?.optString("url"))
        assertEquals("markdown", page?.optString("format"))
    }

    @Test
    fun treatsNullAndGarbageAsNoPage() {
        assertNull(decodeJsJson(null))
        assertNull(decodeJsJson("null"))
        assertNull(decodeJsJson(""))
        assertNull(decodeJsJson("not json"))
    }
}
