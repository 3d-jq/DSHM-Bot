package com.dshm.bot.data.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 引擎的 `state.prefs` → 偏好；以及 store 的读写。 */
class BotPrefsStoreTest {

    @Test
    fun parsesEveryField() {
        val prefs = BotPrefsStore.parse(
            JSONObject()
                .put("theme", "paper")
                .put("accent", "#E08A6D")
                .put("motion", "lively")
                .put("layout", "flat")
                .put("width", 65)
                .put("flatWidth", 90)
                .put("thinking", true),
        )
        assertEquals("paper", prefs.theme)
        assertEquals("#E08A6D", prefs.accent)
        assertEquals("lively", prefs.motion)
        assertEquals("flat", prefs.layout)
        assertEquals(65, prefs.width)
        assertEquals(90, prefs.flatWidth)
        assertTrue(prefs.thinking)
    }

    @Test
    fun missingPrefsGiveTheDefaults() {
        val prefs = BotPrefsStore.parse(null)
        assertEquals("deepseek", prefs.theme)
        assertNull(prefs.accent)
        assertEquals("normal", prefs.motion)
        assertEquals("bubble", prefs.layout)
        assertEquals(80, prefs.width)
        assertEquals(100, prefs.flatWidth)
        assertEquals(false, prefs.thinking)
    }

    @Test
    fun unknownValuesFallBack() {
        val prefs = BotPrefsStore.parse(
            JSONObject()
                .put("theme", "neon")
                .put("motion", "wild")
                .put("layout", "grid")
                .put("width", 500)
                .put("flatWidth", 10)
                .put("accent", "blue"),
        )
        assertEquals("deepseek", prefs.theme)
        assertEquals("normal", prefs.motion)
        assertEquals("bubble", prefs.layout)
        assertEquals(100, prefs.width)
        assertEquals(50, prefs.flatWidth)
        assertNull(prefs.accent)
    }

    @Test
    fun storeHoldsTheLatestValueAndClears() {
        BotPrefsStore.clear()
        assertEquals("deepseek", BotPrefsStore.prefs.value.theme)
        BotPrefsStore.set(BotPrefsStore.parse(JSONObject().put("theme", "mono")))
        assertEquals("mono", BotPrefsStore.prefs.value.theme)
        BotPrefsStore.clear()
        assertEquals("deepseek", BotPrefsStore.prefs.value.theme)
    }
}
