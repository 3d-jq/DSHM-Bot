package com.dshm.bot.data.remote

import com.dshm.bot.domain.model.AvatarGlyph
import com.dshm.bot.domain.model.BotShapeDefaults
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DS Bot `state` → 列表行：形状、颜色、隐藏项、缺省值都不能悄悄漂移。 */
class DsBotParserTest {

    @Test
    fun mapsBotsAndRoomsIntoRows() {
        val state = JSONObject(
            """
            {
              "bots": [{
                "id": "bot-1", "name": "小秘书", "role": "  Chief of Staff  ",
                "color": "red", "pinned": true, "createdAt": 1700000000000,
                "avatar": {"image": "data:image/png;base64,AAA", "shape": "whale"}
              }],
              "rooms": [{
                "id": "room-1", "name": "项目群", "preview": "最后一句",
                "createdAt": 1700000001000
              }]
            }
            """.trimIndent(),
        )

        val rows = DsBotParser.parseState(state)
        assertEquals(2, rows.size)

        val bot = rows[0]
        assertEquals("bot-1", bot.id)
        assertEquals("小秘书", bot.name)
        assertEquals("Chief of Staff", bot.preview)
        assertEquals("red", bot.inkId)
        assertEquals(AvatarGlyph.BOT, bot.glyph)
        assertEquals("whale", bot.avatarShape)
        assertEquals("data:image/png;base64,AAA", bot.avatarImage)
        assertTrue(bot.pinned)
        assertFalse(bot.isGroup)
        assertEquals(1700000000000L, bot.lastActiveAt.toEpochMilli())

        val room = rows[1]
        assertEquals("room-1", room.id)
        assertEquals("最后一句", room.preview)
        assertEquals(AvatarGlyph.GROUP, room.glyph)
        assertTrue(room.isGroup)
        assertNull(room.avatarShape)
    }

    @Test
    fun emptyOrHiddenOrIdLessItemsAreSkipped() {
        assertTrue(DsBotParser.parseState(JSONObject()).isEmpty())
        assertTrue(DsBotParser.parseState(JSONObject("""{"bots":[],"rooms":[]}""")).isEmpty())

        val rows = DsBotParser.parseState(
            JSONObject(
                """
                {
                  "bots": [
                    {"id": "hidden", "name": "隐身", "hidden": true},
                    {"name": "没有 id"},
                    {"id": "", "name": "空 id"},
                    {"id": "ok"}
                  ],
                  "rooms": [{"id": "hidden-room", "hidden": true}]
                }
                """.trimIndent(),
            ),
        )
        assertEquals(1, rows.size)
        assertEquals("ok", rows.single().id)
        assertEquals("Bot", rows.single().name)
    }

    @Test
    fun missingNamesUsePerTypeDefaults() {
        val rows = DsBotParser.parseState(
            JSONObject("""{"bots":[{"id":"b"}],"rooms":[{"id":"r"}]}"""),
        )
        assertEquals("Bot", rows[0].name)
        assertEquals("Group", rows[1].name)
    }

    @Test
    fun invalidMissingOrZeroTimestampFallsBackToNow() {
        val before = System.currentTimeMillis()
        val rows = DsBotParser.parseState(
            JSONObject("""{"bots":[{"id":"zero","createdAt":0},{"id":"negative","createdAt":-1}]}"""),
        )
        val after = System.currentTimeMillis()
        rows.forEach { row ->
            assertTrue(
                "${row.id} 的时间戳应落在调用窗口内",
                row.lastActiveAt.toEpochMilli() in before..after,
            )
        }
    }

    @Test
    fun invalidColorsAreDroppedAndKnownFormsAreKept() {
        val rows = DsBotParser.parseState(
            JSONObject(
                """
                {"bots": [
                  {"id": "a", "color": "not-a-color"},
                  {"id": "b", "color": "#AABBCC"},
                  {"id": "c", "color": "kimi"},
                  {"id": "d"}
                ]}
                """.trimIndent(),
            ),
        )
        assertNull(rows[0].inkId)
        assertEquals("#AABBCC", rows[1].inkId)
        assertEquals("kimi", rows[2].inkId)
        assertNull(rows[3].inkId)
    }

    @Test
    fun shapeFallsBackToDeterministicDefaultPerId() {
        val rows = DsBotParser.parseState(JSONObject("""{"bots":[{"id":"bot-42"}]}"""))
        val shape = rows.single().avatarShape
        assertEquals(BotShapeDefaults.shapeFor("bot-42"), shape)
        assertTrue(shape != null && shape in BotShapeDefaults.shapes)
    }
}
