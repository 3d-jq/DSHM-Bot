package com.dshm.bot.ui.settings

import com.dshm.bot.domain.model.BotPrefs
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

    /** 乐观更新：只改 patch 里那一项，其它字段原样（点 Switch 不该整页变）。 */
    @Test
    fun applyPatchOnlyTouchesTheGivenField() {
        val current = BotPrefsViewModel.parsePrefs(
            JSONObject().put("theme", "paper").put("accent", "#E08A6D")
                .put("motion", "lively").put("layout", "flat")
                .put("width", 65).put("flatWidth", 90).put("thinking", false),
        )
        val next = BotPrefsViewModel.applyPatch(current, JSONObject().put("thinking", true))
        assertTrue(next.thinking)
        assertEquals("paper", next.theme)
        assertEquals("#E08A6D", next.accent)
        assertEquals("lively", next.motion)
        assertEquals("flat", next.layout)
        assertEquals(65, next.width)
        assertEquals(90, next.flatWidth)
    }

    @Test
    fun applyPatchHandlesNullAsResetToDefault() {
        val current = BotPrefs(theme = "moonlight", accent = "#112233", motion = "quiet", thinking = true)
        val next = BotPrefsViewModel.applyPatch(
            current,
            JSONObject().put("accent", JSONObject.NULL).put("motion", JSONObject.NULL)
                .put("thinking", false),
        )
        assertNull(next.accent)
        assertEquals("normal", next.motion)
        assertFalse(next.thinking)
        assertEquals("moonlight", next.theme)
    }

    /** `set-prefs` 回执是 `{"ok":true,"value":{...prefs...}}`，也能认 `prefs` 包一层。 */
    @Test
    fun prefsFromResponseReadsValueOrPrefs() {
        val value = JSONObject().put("theme", "mono").put("thinking", true)
        assertEquals("mono", BotPrefsViewModel.parsePrefs(
            BotPrefsViewModel.prefsFromResponse(JSONObject().put("ok", true).put("value", value)),
        ).theme)
        assertEquals("mono", BotPrefsViewModel.parsePrefs(
            BotPrefsViewModel.prefsFromResponse(JSONObject().put("prefs", value)),
        ).theme)
        assertNull(BotPrefsViewModel.prefsFromResponse(JSONObject().put("ok", true)))
        assertNull(BotPrefsViewModel.prefsFromResponse(null))
    }
}
