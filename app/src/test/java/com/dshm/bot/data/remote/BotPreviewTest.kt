package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主页 Bot 行的预览（大模型最新输出）。
 */
class BotPreviewTest {

    @Test
    fun theLastModelOutputIsThePreview() {
        val raw = JSONObject().put(
            "items",
            JSONArray()
                .put(JSONObject().put("kind", "user").put("text", "在吗"))
                .put(JSONObject().put("kind", "bot").put("text", "在的，有什么可以帮你"))
                .put(JSONObject().put("kind", "user").put("text", "帮我查下资料")),
        )
        // 只要模型输出 —— 用户自己说的话不算。
        assertEquals("在的，有什么可以帮你", BotPreview.fromChatPage(raw))
    }

    @Test
    fun theNewestModelOutputWins() {
        val raw = JSONObject().put(
            "items",
            JSONArray()
                .put(JSONObject().put("kind", "bot").put("text", "第一段"))
                .put(JSONObject().put("kind", "bot").put("text", "第二段")),
        )
        assertEquals("第二段", BotPreview.fromChatPage(raw))
    }

    @Test
    fun noModelOutputMeansNoPreview() {
        assertNull(BotPreview.fromChatPage(null))
        assertNull(BotPreview.fromChatPage(JSONObject()))
        assertNull(
            BotPreview.fromChatPage(
                JSONObject().put("items", JSONArray().put(JSONObject().put("kind", "user").put("text", "hi"))),
            ),
        )
    }

    @Test
    fun thePreviewIsFlattenedToOneLine() {
        assertEquals("第一行 第二行", BotPreview.oneLine("第一行\n第二行"))
        assertEquals("一段 话", BotPreview.oneLine("  一段   话  "))
        val long = "x".repeat(200)
        val cut = BotPreview.oneLine(long, maxLength = 10)
        assertEquals(10, cut.length)
        assertTrue(cut.endsWith("…"))
    }

    @Test
    fun activeBotsComeFromTheUsageOwners() {
        val raw = JSONObject().put(
            "owners",
            JSONArray()
                .put(JSONObject().put("kind", "bot").put("id", "w1"))
                .put(JSONObject().put("kind", "room").put("id", "g1"))
                .put(JSONObject().put("kind", "agent").put("id", "a1")),
        )
        assertEquals(setOf("w1"), BotPreview.activeBotIds(raw))
        assertTrue(BotPreview.activeBotIds(null).isEmpty())
    }
}
