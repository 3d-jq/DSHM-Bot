package com.dshm.bot.ui.chat

import com.dshm.bot.MainDispatcherRule
import com.dshm.bot.data.remote.BotFeedEvent
import com.dshm.bot.data.remote.DshMux
import com.dshm.bot.data.remote.DshMuxLog
import com.dshm.bot.data.remote.DshRemoteApi
import com.dshm.bot.data.remote.FakeMuxLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * earlier 分页（对齐官方 `parts.js partPage` / `client EarlierParts`，docs/timeline-parity.md ⑪）：
 * - 新的一页**插在列表前面**（更早的消息在更上面），不是追加在后面；
 * - 已经见过的条目（按 id）不重复，引擎重复给出也不怕；
 * - 游标推进：`hasEarlier` 跟着 `cursor.hasMore` 走，最后一页收口；
 * - 翻页把更早的一段历史带进来后，那一页范围内的事件行（工具调用）也要补进来
 *   （`mergeSessionEvents` 的 `loaded` 过滤随 `oldest` 边界前移而放行）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BotChatEarlierPageTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val defaultSink = DshMuxLog.sink

    @Before
    fun muteMuxLogs() {
        DshMuxLog.sink = { }
    }

    @After
    fun restoreMuxLogs() {
        DshMuxLog.sink = defaultSink
    }

    /** 一个测试实例：mux + ViewModel + 已连好的 FakeMuxLink（照 BotChatArtifactLiveTest 的最小 harness）。 */
    private class Harness(val scheduler: TestCoroutineScheduler, val api: FakeApi, val links: MutableList<FakeMuxLink>, val scope: CoroutineScope, val mux: DshMux, val viewModel: BotChatViewModel) {
        fun start() {
            scheduler.runCurrent()
            links.single().deliverHostEvent(links.single().eventsStreamId(), ready())
            scheduler.runCurrent()
        }

        fun followStream(): Pair<FakeMuxLink, String> {
            val link = links.single()
            val streamId = link.opened.entries.lastOrNull { it.value == "session/follow" }?.key
                ?: error("单聊没有订阅 session/follow")
            return link to streamId
        }

        fun close() {
            mux.close()
            scope.cancel()
        }

        companion object {
            fun create(rule: MainDispatcherRule): Harness {
                val scheduler = rule.testDispatcher.scheduler
                val api = FakeApi()
                val links = mutableListOf<FakeMuxLink>()
                val scope = CoroutineScope(rule.testDispatcher + SupervisorJob())
                val mux = DshMux(
                    scope = scope,
                    connect = { FakeMuxLink().also { links += it } },
                    readyTimeoutMs = 1_000,
                    retryCapsMs = listOf(10),
                )
                val viewModel = BotChatViewModel(
                    botId = "bot-1",
                    botName = "小助手",
                    isGroup = false,
                    endpoint = null,
                    appContext = null,
                    dependencies = BotChatDependencies(remote = api, transport = null, speech = null, mux = mux),
                )
                return Harness(scheduler, api, links, scope, mux, viewModel)
            }

            private fun ready(clientId: String = "client-1") = JSONObject()
                .put("type", "ready")
                .put("clientId", clientId)
                .put("host", JSONObject().put("home", "/"))
        }
    }

    @Test
    fun earlierPageIsPrependedAndDeduplicated() {
        val harness = Harness.create(mainDispatcherRule)
        try {
            harness.start()

            // 第一页（最新）：user@10 + bot@11，游标 part=0/beforeSeq=10（还有更早的）。
            assertEquals(listOf("u10", "b11"), harness.viewModel.uiState.value.entries.map { it.id })
            assertTrue("游标说还有更早的历史", harness.viewModel.uiState.value.hasEarlier)

            // 翻一页：older:5 插在前面；引擎重复给出的 b11 不该出现第二次。
            harness.viewModel.loadEarlier()
            harness.scheduler.runCurrent()

            val after = harness.viewModel.uiState.value.entries
            assertEquals(
                "更早的一页应插在前面，且重复条目只留一份",
                listOf("older:5", "u10", "b11"),
                after.map { it.id },
            )
            assertFalse("最后一页之后 hasEarlier 应收掉", harness.viewModel.uiState.value.hasEarlier)
        } finally {
            harness.close()
        }
    }

    /** 翻页后，老一段范围内的事件行（工具调用）补进来，并按 seq 落在正确位置。 */
    @Test
    fun earlierPageRangePicksUpItsToolRows() {
        val harness = Harness.create(mainDispatcherRule)
        try {
            harness.start()
            val (link, followId) = harness.followStream()

            // follow 快照带两条 message_bot 调用+结果：result@7 在第一页范围外（oldest=10），result@13 在内。
            link.deliverHostEvent(followId, frame(sessionEvent(6, "tool/call", toolData(1, "c1", "同事"))))
            link.deliverHostEvent(followId, frame(sessionEvent(7, "tool/result", resultData("c1"))))
            link.deliverHostEvent(followId, frame(sessionEvent(12, "tool/call", toolData(1, "c2", "同事"))))
            link.deliverHostEvent(followId, frame(sessionEvent(13, "tool/result", resultData("c2"))))
            harness.scheduler.runCurrent()

            val before = harness.viewModel.uiState.value.entries
            assertTrue("第一页范围内的事件行应已在列表里", before.any { it.id == "tool:13" })
            assertTrue("范围外的事件行不该出现在第一页", before.none { it.id == "tool:7" })

            harness.viewModel.loadEarlier()
            harness.scheduler.runCurrent()

            val after = harness.viewModel.uiState.value.entries
            assertTrue("翻页后老一段的工具行没补进来", after.any { it.id == "tool:7" })
            val atOlder = after.indexOfFirst { it.id == "older:5" }
            val atTool7 = after.indexOfFirst { it.id == "tool:7" }
            val at10 = after.indexOfFirst { it.id == "u10" }
            assertTrue(
                "seq=7 的工具行应插在 older:5(seq=5) 之后、u10(seq=10) 之前：older@$atOlder tool@$atTool7 u10@$at10",
                atOlder < atTool7 && atTool7 < at10,
            )
        } finally {
            harness.close()
        }
    }

    private fun frame(event: JSONObject) = JSONObject()
        .put("type", "event")
        .put("event", event)

    private fun sessionEvent(seq: Long, type: String, data: JSONObject) = JSONObject()
        .put("type", type)
        .put("seq", seq)
        .put("time", 1_700_000_000_000L + seq)
        .put("data", data)

    private fun toolData(turn: Int, callId: String, to: String) = JSONObject()
        .put("turn", turn)
        .put("callId", callId)
        .put("name", "message_bot")
        .put("arguments", JSONObject().put("to", to).put("text", "你好"))

    private fun resultData(callId: String) = JSONObject()
        .put(
            "message",
            JSONObject()
                .put("toolCallId", callId)
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "ok"))),
        )

    private class FakeApi : DshRemoteApi {
        private val feedEvents = MutableSharedFlow<BotFeedEvent>(extraBufferCapacity = 16)

        override suspend fun bot(endpoint: String, payload: JSONObject): JSONObject = when (endpoint) {
            // 有游标 = 更早的一页：older:5 + 引擎重复给出的 b11；没有游标 = 第一页 + 游标。
            "chat", "room-chat" -> when {
                payload.has("cursor") -> JSONObject()
                    .put("sessionId", "s-1")
                    .put(
                        "items",
                        JSONArray()
                            .put(item("bot", "很久之前的回复", 5, "older:5"))
                            .put(item("bot", "回复 11", 11, "b11")),
                    )

                else -> JSONObject()
                    .put("sessionId", "s-1")
                    .put(
                        "items",
                        JSONArray().put(item("user", "提问", 10, "u10")).put(item("bot", "回复 11", 11, "b11")),
                    )
                    .put("cursor", JSONObject().put("part", 0).put("beforeSeq", 10))
            }

            else -> JSONObject()
        }

        override suspend fun botValue(endpoint: String, payload: JSONObject): Any? = JSONObject()

        override suspend fun rpc(method: String, args: JSONObject, timeoutMs: Int): JSONObject = JSONObject()

        override fun feed(): Flow<BotFeedEvent> = feedEvents

        private fun item(kind: String, text: String, seq: Long, id: String) = JSONObject()
            .put("kind", kind)
            .put("text", text)
            .put("seq", seq)
            .put("id", id)
    }
}
