package com.dshm.bot.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotShapeDefaultsTest {

    @Test
    fun `matches DS Bot deterministic shapes`() {
        // Expected values computed with ds-bot/client.js defaultShape().
        assertEquals("gumdrop", BotShapeDefaults.shapeFor("abc"))
        assertEquals("gumdrop", BotShapeDefaults.shapeFor("bot-1"))
        assertEquals(
            "moon",
            BotShapeDefaults.shapeFor("session-bc4d1c8c-e0de-49df-9af4-f3986c5b9860"),
        )
    }

    @Test
    fun `always returns one of the seven default shapes`() {
        for (id in listOf("", "a", "session-1", "某个中文 Bot", "0123456789abcdef")) {
            assertTrue(BotShapeDefaults.shapeFor(id) in BotShapeDefaults.shapes)
        }
        assertEquals(7, BotShapeDefaults.shapes.size)
    }
}
