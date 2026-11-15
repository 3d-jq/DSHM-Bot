package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.SessionEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 四期 4b：客户端折叠（[foldPrivateChat]）与宿主 `displayItems` 的 **oracle 差分**。
 *
 * 期望值由 `app/src/main/assets/ds-bot/test/gen-display-items.mjs` 用宿主的真实折叠
 * 生成，逐场景硬编码在这里。任何一侧语义改动导致不一致，这条测试就红——这是「地基
 * 硬」的自证：折叠对不对不靠肉眼，靠两个独立实现对同一批事件逐行相等。
 */
class PrivateChatFoldTest {

    private fun text(value: String) = JSONObject().put("type", "text").put("text", value)

    private fun event(
        type: String,
        seq: Long,
        data: JSONObject?,
        timeMs: Long? = null,
    ) = SessionEvent(seq, type, timeMs, data)

    private fun userRpc(rpcId: String, vararg blocks: JSONObject) = JSONObject()
        .put("source", JSONObject().put("kind", "user").put("rpcId", rpcId))
        .put("content", JSONArray().apply { blocks.forEach(::put) })

    private fun assistant(turn: Int, vararg blocks: JSONObject) = JSONObject()
        .put("turn", turn)
        .put("message", JSONObject().put("content", JSONArray().apply { blocks.forEach(::put) }))

    private fun FoldRow.toPair() = listOf(kind, text, seq, turn, images, requestId)

    private fun assertMatches(events: List<SessionEvent>, expected: List<List<Any?>>, hidden: Set<Int> = emptySet()) {
        val rows = foldPrivateChat(events, hidden)
        assertEquals(expected.size, rows.size)
        expected.forEachIndexed { index, want ->
            assertEquals("row $index", want, rows[index].toPair())
        }
    }

    @Test
    fun basicTurnProducesUserThenBotBubbles() {
        assertMatches(
            listOf(
                event("turn/start", 1, JSONObject().put("turn", 1), 1000),
                event("user/message", 2, userRpc("u-1", text("你好")), 1100),
                event("assistant/message", 3, assistant(1, text("在的")), 1200),
                event("turn/end", 4, JSONObject().put("turn", 1), 1300),
            ),
            listOf(
                listOf("user", "你好", 2L, 1, 0, "u-1"),
                listOf("bot", "在的", 3L, 1, 0, null),
            ),
        )
    }

    @Test
    fun aBlankTextBlockMakesNoRowAndMultipleBlocksMakeOneRowEach() {
        assertMatches(
            listOf(
                event("turn/start", 1, JSONObject().put("turn", 3), 1000),
                event("assistant/message", 2, assistant(3, text("第一段"), text("   "), text("第二段")), 1100),
            ),
            listOf(
                listOf("bot", "第一段", 2L, 3, 0, null),
                listOf("bot", "第二段", 2L, 3, 0, null),
            ),
        )
    }

    @Test
    fun hiddenExchangeTurnsHideAssistantRolesButNotUserMessages() {
        assertMatches(
            listOf(
                event("turn/start", 1, JSONObject().put("turn", 2), 1000),
                event("user/message", 2, userRpc("u-2", text("用户消息")), 1100),
                event("assistant/message", 3, assistant(2, text("不该出现")), 1200),
            ),
            listOf(listOf("user", "用户消息", 2L, 2, 0, "u-2")),
            hidden = setOf(2),
        )
    }

    @Test
    fun imagesAreCountedAndRpcIdIsKeptForEchoClaiming() {
        assertMatches(
            listOf(
                event("turn/start", 1, JSONObject().put("turn", 1), 1000),
                event("user/message", 2, userRpc("r-9", text("看这两张"), JSONObject().put("type", "image"), JSONObject().put("type", "image")), 1100),
            ),
            listOf(listOf("user", "看这两张", 2L, 1, 2, "r-9")),
        )
    }

    @Test
    fun multipleTextBlocksJoinWithNewlineAndTrim() {
        assertMatches(
            listOf(
                event("turn/start", 1, JSONObject().put("turn", 1), 1000),
                event("user/message", 2, userRpc("u", text("  "), text("实义")), 1100),
            ),
            // 空白块 trim 掉（textOf 把 text 块 `\n` 连接后整体 trim，"实义"无前导空白）。
            listOf(listOf("user", "实义", 2L, 1, 0, "u")),
        )
        assertMatches(
            listOf(
                event("turn/start", 1, JSONObject().put("turn", 1), 1000),
                event("user/message", 2, userRpc("u", text("第一行"), text("第二行")), 1100),
            ),
            listOf(listOf("user", "第一行\n第二行", 2L, 1, 0, "u")),
        )
    }

    @Test
    fun aNonUserSourceMakesNoRow() {
        val rows = foldPrivateChat(
            listOf(
                event(
                    "user/message", 2,
                    JSONObject().put("source", JSONObject().put("kind", "schedule")).put("content", JSONArray().put(text("提醒"))),
                    1100,
                ),
            ),
        )
        assertEquals(0, rows.size)
    }

    @Test
    fun foldIgnoresNonBubbleEventTypesSilently() {
        val rows = foldPrivateChat(
            listOf(
                event("llm/retry", 1, JSONObject().put("retry", 1), 1000),
                event("deliverables/presented", 2, JSONObject(), 1100),
            ),
        )
        assertEquals(0, rows.size)
    }

    @Test
    fun seqAndTurnAreNullWhenAbsent() {
        val rows = foldPrivateChat(
            listOf(
                event("assistant/message", 5, assistant(0, text("没有轮号")), 1000),
            ),
        )
        assertEquals(1, rows.size)
        assertNull("无轮号时 turn 为 null", rows[0].turn)
    }
}