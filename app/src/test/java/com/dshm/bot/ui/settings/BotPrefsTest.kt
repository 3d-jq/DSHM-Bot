package com.dshm.bot.ui.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `state.prefs` / `set-prefs` 回执的字段解析（DS Bot GeneralPage 同款默认值）。 */
class BotPrefsTest {

    @Test
    fun defaultsWhenPrefsEmpty() {
        val prefs = BotPrefsViewModel.parsePrefs(JSONObject())
        assertEquals("deepseek", prefs.theme)
        assertNull(prefs.accent)
        assertEquals("normal", prefs.motion)
        assertEquals("bubble", prefs.layout)
        assertEquals(80, prefs.width)
        assertEquals(100, prefs.flatWidth)
        assertFalse(prefs.thinking)
    }

    @Test
    fun readsAllFields() {
        val raw = JSONObject()
            .put("theme", "paper")
            .put("accent", "#E08A6D")
            .put("motion", "lively")
            .put("layout", "flat")
            .put("width", 65)
            .put("flatWidth", 90)
            .put("thinking", true)
        val prefs = BotPrefsViewModel.parsePrefs(raw)
        assertEquals("paper", prefs.theme)
        assertEquals("#E08A6D", prefs.accent)
        assertEquals("lively", prefs.motion)
        assertEquals("flat", prefs.layout)
        assertEquals(65, prefs.width)
        assertEquals(90, prefs.flatWidth)
        assertTrue(prefs.thinking)
    }

    @Test
    fun invalidValuesFallBack() {
        val raw = JSONObject()
            .put("motion", "wild")
            .put("layout", "grid")
            .put("width", 500)
            .put("accent", "blue")
        val prefs = BotPrefsViewModel.parsePrefs(raw)
        assertEquals("normal", prefs.motion)
        assertEquals("bubble", prefs.layout)
        assertEquals(100, prefs.width)
        assertNull(prefs.accent)
    }
}
