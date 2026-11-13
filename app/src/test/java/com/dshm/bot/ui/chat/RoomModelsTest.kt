package com.dshm.bot.ui.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun groupProgressReadsRunningAndTheSpeakingMember() {
        val idle = parseGroupProgress(JSONArray())
        assertEquals(false to null, idle)

        val running = parseGroupProgress(
            JSONArray()
                .put(JSONObject().put("callId", "call-1").put("speaking", "bot-2"))
                .put(JSONObject().put("callId", "call-2").put("speaking", "bot-3")),
        )
        assertEquals(true to "bot-3", running)

        val noSpeaker = parseGroupProgress(JSONArray().put(JSONObject().put("callId", "call-1")))
        assertEquals(true to null, noSpeaker)

        // 形状不对时给 null：调用方要保留上一次的判断，而不是当成「没在跑」。
        assertNull(parseGroupProgress(JSONObject()))
        assertNull(parseGroupProgress(null))
    }

    /**
     * 单 Bot 在**自己私聊**里干活（`work.bots[id]`，不带 roomId）不该让群聊的
     * 成员 dock 冒出状态。这里**有意偏离**官方 `member-status.js` 的 `memberWork`
     * （官方 `local ?? work.bots[id]` 无条件兜底）——见 `RoomModels.kt` 的注释。
     */
    @Test
    fun privateChatWorkDoesNotShowAsGroupMemberStatus() {
        val state = JSONObject()
            .put("bots", JSONArray().put(JSONObject().put("id", "bot-1").put("name", "总管")))
            .put(
                "work",
                JSONObject()
                    .put("rooms", JSONObject()) // 这个群没有任何活
                    .put(
                        "bots",
                        JSONObject().put(
                            "bot-1",
                            JSONObject().put("tool", "bash").put("since", 1_000L),
                        ),
                    ),
            )
        val status = parseMemberStatus(state, "room-1")
        assertEquals("idle", status["bot-1"]?.state)
        assertFalse("私聊工作不该标成 here", status["bot-1"]?.here == true)
    }

    /** 「在**别的群**里干活」仍应显示（带 `roomId`），这是保留的官方语义。 */
    @Test
    fun workInAnotherGroupStillShowsAsMemberStatus() {
        val state = JSONObject()
            .put("bots", JSONArray().put(JSONObject().put("id", "bot-1").put("name", "总管")))
            .put(
                "work",
                JSONObject()
                    .put("rooms", JSONObject()) // 当前群没活
                    .put(
                        "bots",
                        JSONObject().put(
                            "bot-1",
                            JSONObject().put("tool", "bash").put("roomId", "room-2"),
                        ),
                    ),
            )
        val status = parseMemberStatus(state, "room-1")
        assertEquals("work", status["bot-1"]?.state)
        assertFalse("别的群的活不该标成 here", status["bot-1"]?.here == true)
    }

    @Test
    fun groupLocalWorkStillShowsInMemberStatus() {
        val state = JSONObject()
            .put("bots", JSONArray().put(JSONObject().put("id", "bot-1").put("name", "总管")))
            .put(
                "work",
                JSONObject()
                    .put(
                        "rooms",
                        JSONObject().put(
                            "room-1",
                            JSONArray().put(
                                JSONObject().put("botId", "bot-1").put("tool", "web_search")
                                    .put("speaking", true),
                            ),
                        ),
                    )
                    .put("bots", JSONObject()),
            )
        val status = parseMemberStatus(state, "room-1")
        assertEquals("work", status["bot-1"]?.state)
        assertTrue("本群的活必须是 here", status["bot-1"]?.here == true)
        assertTrue(status["bot-1"]?.speaking == true)
    }
}
