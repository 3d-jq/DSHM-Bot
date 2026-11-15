package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.SessionEvent
import com.dshm.bot.data.remote.TimelineRow
import com.dshm.bot.data.remote.TimelineRowPayload
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四期 4b oracle 差分：客户端折叠（[TimelineFold.fold]）必须与**宿主真实现**逐行相等。
 *
 * 期望值来自 `fixtures/timeline-fold-oracle.json` —— 由
 * `app/src/main/assets/ds-bot/test/gen-display-items.mjs` 直接跑宿主的
 * `displayItems` + `composeTimelineRows` 生成（不是手抄的期望值）。
 * 宿主改了折叠语义 → 重跑生成器 → 这条测试立刻指出差异。
 */
class TimelineFoldTest {

    private val text = { value: String -> JSONObject().put("type", "text").put("text", value) }

    private fun block(type: String, extra: JSONObject = JSONObject()) = JSONObject().put("type", type).let {
        extra.keys().forEach { key -> it.put(key, extra.get(key)) }
        it
    }

    private fun event(type: String, seq: Long, data: JSONObject?, timeMs: Long? = null) =
        SessionEvent(seq, type, timeMs, data)

    private fun userData(rpcId: String?, vararg blocks: JSONObject): JSONObject {
        val source = JSONObject().put("kind", "user")
        if (rpcId != null) source.put("rpcId", rpcId)
        return JSONObject().put("source", source).put("content", JSONArray().apply { blocks.forEach(::put) })
    }

    private fun assistantData(turn: Int, vararg blocks: JSONObject) = JSONObject()
        .put("turn", turn)
        .put("message", JSONObject().put("content", JSONArray().apply { blocks.forEach(::put) }))

    // ---- 场景（与生成器里的同名场景一一对应）----

    private val basicTurn = listOf(
        event("turn/start", 1, JSONObject().put("turn", 1), 1000),
        event("user/message", 2, userData(null, text("你好")), 1100),
        event("assistant/message", 3, assistantData(1, text("在的")), 1200),
        event("turn/end", 4, JSONObject().put("turn", 1), 1300),
    )

    private val multiBlock = listOf(
        event("turn/start", 1, JSONObject().put("turn", 3), 1000),
        event(
            "assistant/message", 2,
            assistantData(3, text("第一段"), text("   "), block("reasoning", JSONObject().put("text", "想一想")), text("第二段")),
            1100,
        ),
    )

    private val hiddenTurn = listOf(
        event("turn/start", 1, JSONObject().put("turn", 2), 1000),
        event("user/message", 2, userData(null, text("用户消息")), 1100),
        event("assistant/message", 3, assistantData(2, text("不该出现")), 1200),
    )

    private val withAttachments = listOf(
        event("turn/start", 1, JSONObject().put("turn", 1), 1000),
        event(
            "user/message", 2,
            userData(
                "r-9", text("看这两张"), block("image"),
                block("image", JSONObject().put("attachment", JSONObject().put("name", "a.png"))),
            ),
            1100,
        ),
    )

    private val multiTextUser = listOf(
        event("turn/start", 1, JSONObject().put("turn", 1), 1000),
        event("user/message", 2, userData(null, text("第一行"), text("第二行")), 1100),
    )

    private val trimmedUser = listOf(
        event("turn/start", 1, JSONObject().put("turn", 1), 1000),
        event("user/message", 2, userData(null, text("  "), text("实义")), 1100),
    )

    private val retryRow = listOf(
        event("turn/start", 1, JSONObject().put("turn", 1), 1000),
        event(
            "llm/retry", 2,
            JSONObject().put("turn", 1).put("retry", 2).put("maxRetries", 5)
                .put("failure", JSONObject().put("code", "rate_limit").put("message", "限流")),
            1100,
        ),
    )

    private val turnFailed = listOf(
        event("turn/start", 1, JSONObject().put("turn", 1), 1000),
        event(
            "turn/end", 2,
            JSONObject().put("turn", 1).put("reason", JSONObject().put("kind", "error").put("error", JSONObject().put("code", "boom").put("message", "后续步骤失败"))),
            1100,
        ),
    )

    private val changesRow = listOf(
        event("turn/start", 1, JSONObject().put("turn", 1), 1000),
        event("workspace/changes", 2, JSONObject().put("turn", 1), 1100),
    )

    private val deliverableCard = listOf(
        event("turn/start", 1, JSONObject().put("turn", 1), 1000),
        event("user/message", 2, userData(null, text("做一份 PPT")), 1100),
        event(
            "deliverables/presented", 3,
            JSONObject().put("turn", 1).put("callId", "p1")
                .put("files", JSONArray().put(JSONObject().put("path", "报告.pptx").put("description", "定稿"))),
            1200,
        ),
        event("assistant/message", 4, assistantData(1, text("做好了")), 1300),
        event("turn/end", 5, JSONObject().put("turn", 1), 1400),
    )

    private val toolRow = listOf(
        event("turn/start", 1, JSONObject().put("turn", 1), 1000),
        event("tool/call", 2, JSONObject().put("turn", 1).put("callId", "c1").put("name", "message_bot").put("arguments", JSONObject().put("to", "小助手")), 1100),
        event("tool/result", 3, JSONObject().put("turn", 1).put("message", JSONObject().put("toolCallId", "c1").put("content", JSONArray().put(text("ok")))), 1200),
    )

    // produced 口径：该轮没有 present 声明，工具写过的文件上卡（描述为空、路径按 cwd）。
    private val producedCard = listOf(
        event("turn/start", 1, JSONObject().put("turn", 1), 1000),
        event("user/message", 2, userData(null, text("写个文件")), 1100),
        event("tool/call", 3, JSONObject().put("turn", 1).put("callId", "w1").put("name", "write").put("arguments", JSONObject().put("file_path", "纪要.md").put("content", "# 纪要")), 1200),
        event("tool/result", 4, JSONObject().put("turn", 1).put("message", JSONObject().put("toolCallId", "w1").put("content", JSONArray().put(text("ok")))).put("meta", JSONObject().put("path", "/root/w/纪要.md")), 1300),
        event("assistant/message", 5, assistantData(1, text("写好了")), 1400),
        event("turn/end", 6, JSONObject().put("turn", 1), 1500),
    )

    // produced 口径 + 相对路径（没有 meta.path）：按 cwd 解析参数路径。
    private val producedRelativePath = listOf(
        event("turn/start", 1, JSONObject().put("turn", 1), 1000),
        event("tool/call", 2, JSONObject().put("turn", 1).put("callId", "w1").put("name", "write").put("arguments", JSONObject().put("file_path", "笔记.md")), 1100),
        event("tool/result", 3, JSONObject().put("turn", 1).put("message", JSONObject().put("toolCallId", "w1").put("content", JSONArray().put(text("ok")))), 1200),
        event("turn/end", 4, JSONObject().put("turn", 1), 1300),
    )

    // 声明落在闭合那一句之后 → 不上卡（`presentedForClosing` 边界）。
    private val presentedAfterClosing = listOf(
        event("turn/start", 1, JSONObject().put("turn", 1), 1000),
        event("assistant/message", 2, assistantData(1, text("好了")), 1100),
        event(
            "deliverables/presented", 3,
            JSONObject().put("turn", 1).put("callId", "p1")
                .put("files", JSONArray().put(JSONObject().put("path", "晚到的.pdf").put("description", "太晚"))),
            1200,
        ),
        event("turn/end", 4, JSONObject().put("turn", 1), 1300),
    )

    /** 宿主折出来的期望行（从 fixture 读）。 */
    private fun oracle(name: String): List<JSONObject> {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/timeline-fold-oracle.json")
            ?: error("读不到 oracle fixture（fixtures/timeline-fold-oracle.json）")
        val root = JSONObject(stream.readBytes().decodeToString())
        val array = root.optJSONArray(name) ?: error("fixture 里没有场景 $name")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    /** 把客户端折出的行转成与 fixture 同形状的 JSON，便于逐字段比对。 */
    private fun render(row: TimelineRow): JSONObject {
        val payload = JSONObject()
        when (val p = row.payload) {
            is TimelineRowPayload.User -> {
                payload.put("text", p.text)
                payload.put("images", p.images)
                if (p.blocks.isNotEmpty()) payload.put("blocks", JSONArray(p.blocks))
                if (p.requestId != null) payload.put("requestId", p.requestId)
            }

            is TimelineRowPayload.Bot -> {
                payload.put("text", p.text)
            }

            is TimelineRowPayload.Tool -> {
                payload.put("tool", p.tool)
                payload.put("state", p.state)
                payload.put("label", p.label)
                if (p.target != null) payload.put("target", p.target)
                if (p.callId != null) payload.put("callId", p.callId)
            }

            is TimelineRowPayload.Event -> {
                payload.put("eventKind", p.eventKind)
                if (p.code != null) payload.put("code", p.code)
                if (p.botId != null) payload.put("botId", p.botId)
                if (p.name != null) payload.put("name", p.name)
                payload.put("text", p.raw.optString("text"))
            }

            is TimelineRowPayload.Changes -> {
                // 宿主的 changes 行载荷是**空的**（摘要在事件之外）。
            }

            is TimelineRowPayload.Artifact -> {
                payload.put(
                    "files",
                    JSONArray().apply {
                        p.files.forEach { file ->
                            put(
                                JSONObject()
                                    .put("path", file.path)
                                    .put("description", file.description ?: JSONObject.NULL),
                            )
                        }
                    },
                )
                payload.put("delivered", p.delivered)
                payload.put("collapsedCount", 4)
            }

            else -> Unit
        }
        return JSONObject()
            .put("key", row.key)
            .put("kind", row.kind)
            .put("seq", row.seq ?: JSONObject.NULL)
            .put("turn", row.turn ?: JSONObject.NULL)
            .put("timeMs", row.timeMs ?: JSONObject.NULL)
            .put("payload", payload)
    }

    /** JSON 数字统一成 Double 再比（Integer/Long 类型不同但值必须相同）。 */
    private fun numberOf(value: Any?): Double? = when (value) {
        null, JSONObject.NULL -> null
        is Number -> value.toDouble()
        else -> null
    }

    /** 逐行比对：key/kind/seq/turn/timeMs + payload 的全部字段。 */
    private fun assertMatchesOracle(name: String, events: List<SessionEvent>, hidden: Set<Int> = emptySet()) {
        val expected = oracle(name)
        val actual = TimelineFold.fold(
            events = events,
            exchangeTurns = hidden,
            cwd = "/root/w",
            botName = "小助手",
            resolveBot = { if (it == "bot-1") "小助手" else it },
        ).map(::render)

        assertEquals("场景 $name 的行数", expected.size, actual.size)
        expected.forEachIndexed { index, want ->
            val got = actual[index]
            assertEquals("场景 $name 第 ${index + 1} 行 key", want.optString("key"), got.optString("key"))
            assertEquals("场景 $name 第 ${index + 1} 行 kind", want.optString("kind"), got.optString("kind"))
            // seq/turn/timeMs 按**数值**比：fixture 的 JSON 数字是 Integer，
            // 客户端是 Long，类型不同但值必须相同。
            assertEquals("场景 $name 第 ${index + 1} 行 seq", numberOf(want.opt("seq")), numberOf(got.opt("seq")))
            assertEquals("场景 $name 第 ${index + 1} 行 turn", numberOf(want.opt("turn")), numberOf(got.opt("turn")))
            assertEquals("场景 $name 第 ${index + 1} 行 timeMs", numberOf(want.opt("timeMs")), numberOf(got.opt("timeMs")))
            assertEquals(
                "场景 $name 第 ${index + 1} 行 payload",
                want.optJSONObject("payload").toString(),
                got.optJSONObject("payload").toString(),
            )
        }
    }

    @Test
    fun basicTurnMatchesTheHostFold() = assertMatchesOracle("basicTurn", basicTurn)

    @Test
    fun multipleTextBlocksMakeOneRowEach() = assertMatchesOracle("multiBlock", multiBlock)

    @Test
    fun hiddenExchangeTurnsAreNotDrawn() = assertMatchesOracle("hiddenTurn", hiddenTurn, hidden = setOf(2))

    @Test
    fun attachmentsAreCountedAndNamed() = assertMatchesOracle("withAttachments", withAttachments)

    @Test
    fun multipleTextBlocksJoinWithNewline() = assertMatchesOracle("multiTextUser", multiTextUser)

    @Test
    fun blankBlocksAreTrimmedAway() = assertMatchesOracle("trimmedUser", trimmedUser)

    @Test
    fun retryRowMatchesTheHostFold() = assertMatchesOracle("retryRow", retryRow)

    @Test
    fun turnFailureRowMatchesTheHostFold() = assertMatchesOracle("turnFailed", turnFailed)

    @Test
    fun changesRowMatchesTheHostFold() = assertMatchesOracle("changesRow", changesRow)

    @Test
    fun deliverableCardMatchesTheHostFold() = assertMatchesOracle("deliverableCard", deliverableCard)

    @Test
    fun toolRowMatchesTheHostFold() = assertMatchesOracle("toolRow", toolRow)

    @Test
    fun producedCardListsFilesTheToolsWrote() = assertMatchesOracle("producedCard", producedCard)

    @Test
    fun producedCardResolvesRelativePathsAgainstCwd() = assertMatchesOracle("producedRelativePath", producedRelativePath)

    @Test
    fun aDeclarationAfterTheClosingReplyIsNotOnTheCard() = assertMatchesOracle("presentedAfterClosing", presentedAfterClosing)

    @Test
    fun theOracleFixtureCoversEveryScenario() {
        // fixture 必须覆盖上面每个场景（漏一个场景 = 少一层保护）。
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/timeline-fold-oracle.json")
            ?: error("读不到 oracle fixture")
        val root = JSONObject(stream.readBytes().decodeToString())
        val names = root.keys().asSequence().toSet()
        val expected = setOf(
            "basicTurn", "multiBlock", "hiddenTurn", "withAttachments", "multiTextUser",
            "trimmedUser", "retryRow", "turnFailed", "changesRow", "deliverableCard", "toolRow",
            "producedCard", "producedRelativePath", "presentedAfterClosing",
        )
        assertTrue("fixture 缺少场景：${expected - names}", expected.all { it in names })
    }
}
