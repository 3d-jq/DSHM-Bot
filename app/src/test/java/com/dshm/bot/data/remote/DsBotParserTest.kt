package com.dshm.bot.data.remote

import com.dshm.bot.domain.model.AvatarGlyph
import com.dshm.bot.domain.model.BotInks
import com.dshm.bot.domain.model.BotShapeDefaults
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DS Bot `state` → 列表行：形状、颜色、隐藏项、缺省值都不能悄悄漂移。 */
class DsBotParserTest {

    /** 群里的工作不能点亮 Bot 自己的行（官方按会话状态：`useSessionStatus`）。 */
    @Test
    fun groupWorkDoesNotLightTheBotsOwnRow() {
        val state = JSONObject(
            """
            {
              "bots": [{"id": "bot-a", "name": "A"}, {"id": "bot-b", "name": "B"}],
              "rooms": [{"id": "room-1", "name": "群", "members": ["bot-a", "bot-b"]}],
              "work": {
                "bots": {
                  "bot-a": {"since": 1},
                  "bot-b": {"since": 1, "roomId": "room-1"}
                },
                "rooms": {"room-1": [{"botId": "bot-b", "since": 1}]}
              }
            }
            """.trimIndent(),
        )

        val rows = DsBotParser.parseState(state)
        val botA = rows.first { it.id == "bot-a" }
        val botB = rows.first { it.id == "bot-b" }
        val room = rows.first { it.isGroup }

        assertEquals("自己的会话在跑 → 亮", "running", botA.activity)
        assertNull("仅在群里跑（work.bots 带 roomId）→ 不该亮", botB.activity)
        assertEquals("群行自己亮", "running", room.activity)
    }

    @Test
    fun groupRoundMembersAloneDoNotLightTheBotRow() {
        val state = JSONObject(
            """
            {
              "bots": [{"id": "bot-a", "name": "A"}],
              "rooms": [{"id": "room-1", "name": "群", "members": ["bot-a"]}],
              "work": {"bots": {}, "rooms": {"room-1": [{"botId": "bot-a", "since": 1}]}}
            }
            """.trimIndent(),
        )

        val rows = DsBotParser.parseState(state)
        assertNull("群回合成员不算该 Bot 自己在跑", rows.first { it.id == "bot-a" }.activity)
        assertEquals("running", rows.first { it.isGroup }.activity)
    }

    @Test
    fun fallsBackToTheIdDefaultWhenABotHasNoColour() {
        val state = JSONObject(
            """
            {
              "bots": [
                {"id": "bot-pink", "name": "小助手", "color": "", "avatar": null},
                {"id": "bot-red", "name": "红", "color": "red"}
              ],
              "rooms": [{"id": "room-1", "name": "群", "members": ["bot-pink", "bot-red"]}]
            }
            """.trimIndent(),
        )

        val rows = DsBotParser.parseState(state)
        // DS Bot `colorOf`：没手选颜色时按 id 取默认色，不是灰色。
        assertEquals(BotInks.defaultColorId("bot-pink"), rows[0].inkId)
        assertEquals("red", rows[1].inkId)
        // 群成员用的是同一份身份表：群头像里的颜色要和行里一致。
        val members = rows.first { it.isGroup }.roomMembers
        assertEquals(BotInks.defaultColorId("bot-pink"), members[0].inkId)
        assertEquals("red", members[1].inkId)
        assertEquals(
            BotInks.defaultColorId("bot-pink"),
            DsBotParser.teamMembers(state).first { it.id == "bot-pink" }.inkId,
        )
    }

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
    fun unknownColorsFallBackToTheIdDefaultAndKnownFormsAreKept() {
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
        // 认不出来的颜色（和没写颜色）都按 id 取默认色 —— DS Bot `colorOf` 的口径。
        assertEquals(BotInks.defaultColorId("a"), rows[0].inkId)
        assertEquals("#AABBCC", rows[1].inkId)
        assertEquals("kimi", rows[2].inkId)
        assertEquals(BotInks.defaultColorId("d"), rows[3].inkId)
    }

    @Test
    fun shapeFallsBackToDeterministicDefaultPerId() {
        val rows = DsBotParser.parseState(JSONObject("""{"bots":[{"id":"bot-42"}]}"""))
        val shape = rows.single().avatarShape
        assertEquals(BotShapeDefaults.shapeFor("bot-42"), shape)
        assertTrue(shape != null && shape in BotShapeDefaults.shapes)
    }
}
