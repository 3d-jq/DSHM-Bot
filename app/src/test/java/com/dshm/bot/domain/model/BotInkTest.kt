package com.dshm.bot.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotInkTest {

    @Test
    fun fnv1aMatchesReferenceImplementation() {
        // 参考值由 dsh-Go 的 Dart 实现（与 DS Bot 同算法）计算得出。
        assertEquals(3391207752L, BotInks.fnv1a("bot-1"))
    }

    @Test
    fun defaultColorMatchesReferenceVectors() {
        val expected = mapOf(
            "bot-1" to "blue",
            "Chief" to "blue",
            "办公专家" to "cyan",
            "" to "cyan",
            "group-42" to "orange",
            "bot-2" to "violet",
            "a" to "cyan",
            "z9" to "green",
            "Main Bot" to "cyan",
        )
        expected.forEach { (id, color) ->
            assertEquals("defaultColor($id)", color, BotInks.defaultColorId(id))
        }
    }

    @Test
    fun defaultColorIsStableForLongIds() {
        val id = "bot-" + "x".repeat(512)
        assertEquals(BotInks.defaultColorId(id), BotInks.defaultColorId(id))
    }

    @Test
    fun recognisesHexAndIdsAndLegacyIds() {
        assertTrue(BotInks.isColor("#1f4fd8"))
        assertTrue(BotInks.isColor("#1F4FD8"))
        assertTrue(BotInks.isColor("red"))
        assertTrue(BotInks.isColor("kimi"))
        assertFalse(BotInks.isColor("nope"))
        assertFalse(BotInks.isColor(null))
        assertFalse(BotInks.isColor("#12345"))
    }

    @Test
    fun legacyIdsMigrateToCurrentIds() {
        assertEquals("charcoal", BotInks.currentColor("kimi"))
        assertEquals("rose", BotInks.currentColor("minimax"))
        assertEquals("red", BotInks.currentColor("red"))
    }

    @Test
    fun resolvesInkForLightAndDarkTheme() {
        assertEquals(0xFFFF3E51L, BotInks.color("red", dark = false))
        assertEquals(0xFFE02135L, BotInks.color("red", dark = true))
        assertEquals(0xFF141416L, BotInks.color("kimi", dark = false))
        assertEquals(0xFFF1F2F4L, BotInks.color("kimi", dark = true))
    }

    @Test
    fun hexColorsAreReturnedAsGiven() {
        assertEquals(0xFF1F4FD8L, BotInks.color("#1F4FD8", dark = false))
    }

    @Test
    fun unknownColorFallsBackToGray() {
        assertEquals(0xFF959595L, BotInks.color("missing", dark = false))
        assertEquals(0xFF777777L, BotInks.color(null, dark = true))
    }

    @Test
    fun pickerOrderStartsWithDsBotOrder() {
        assertEquals(
            listOf("red", "orange", "yellow", "green", "cyan", "blue", "violet", "magenta", "gray", "deepseek"),
            BotInks.pickerOrder,
        )
    }
}
