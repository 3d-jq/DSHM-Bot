package com.dshm.bot.ui.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomModelsTest {

    private fun state() = JSONObject()
        .put(
            "rooms",
            JSONArray().put(
                JSONObject()
                    .put("id", "room-1")
                    .put("name", "产品群")
                    .put("named", true)
                    .put("notice", "先查资料")
                    .put("mode", "everyone")
                    .put("admin", "bot-1")
                    .put("members", JSONArray().put("bot-1").put("bot-2"))
                    .put("permission", "read-only")
                    .put("relayLimit", 8)
                    .put("relayLimitDefault", 5)
                    .put("pinned", true),
            ),
        )
        .put(
            "bots",
            JSONArray()
                .put(JSONObject().put("id", "bot-1").put("name", "总管"))
                .put(JSONObject().put("id", "bot-2").put("name", "助手"))
                .put(JSONObject().put("id", "bot-3").put("name", "备选")),
        )
        .put(
            "permissions",
            JSONObject().put(
                "options",
                JSONArray().put(
                    JSONObject().put("value", "read-only").put("name", "只读"),
                ),
            ),
        )
        .put("memory", false)

    @Test
    fun parsesRoomFieldsInsteadOfBotFields() {
        val ui = parseRoomSettings(state(), "room-1")
        assertEquals("产品群", ui.info?.name)
        assertEquals("everyone", ui.info?.mode)
        assertEquals(listOf("bot-1", "bot-2"), ui.info?.members)
        assertEquals("bot-1", ui.info?.admin)
        assertEquals("先查资料", ui.info?.notice)
        assertEquals(8, ui.info?.relayLimit)
        assertTrue(ui.info?.pinned == true)
        assertFalse(ui.memoryEnabled)
        assertEquals(3, ui.bots.size)
        assertEquals("只读", ui.permissions.single().name)
    }

    @Test
    fun missingRoomIsAnError() {
        val ui = parseRoomSettings(state(), "missing")
        assertTrue(ui.info == null)
        assertTrue(ui.error != null)
    }

    @Test
    fun stringListKeepsOnlyNonEmptyValues() {
        val array = JSONArray().put("a").put("").put("b")
        assertEquals(listOf("a", "b"), array.toStringList())
    }
}
