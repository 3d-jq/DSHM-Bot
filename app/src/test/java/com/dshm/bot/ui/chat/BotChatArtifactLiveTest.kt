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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 产物卡与交付行在实时会话流下的行为（官方口径：轮闭合才出卡）。
 *
 * - `deliverables/presented` 只代表「声明了交付」，轮内只画工具行
 *   （「正在交付/已交付 N 个文件」，对齐官方 Delivering / Delivered）；
 * - 产物卡等 `turn/end`，位置固定在轮末 seq、出卡后不再移动；
 * - 同一轮多次 present 聚合一张卡。
 *
 * 另覆盖 follow 断流自动重订：流结束后按退避重开，永久失败不重试。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BotChatArtifactLiveTest {

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

    /** 一个测试实例：mux + ViewModel + 已连好的 FakeMuxLink。 */
    private class Harness(
        val scheduler: TestCoroutineScheduler,
        val api: FakeApi,
        val links: MutableList<FakeMuxLink>,
        val scope: CoroutineScope,
        val mux: DshMux,
        val viewModel: BotChatViewModel,
    ) {
        fun start() {
            scheduler.runCurrent()
            val link = links.single()
            link.deliverHostEvent(link.eventsStreamId(), ready())
            scheduler.runCurrent()
        }

        /** 当前 session/follow 流：断言它已订阅并返回链路与 streamId。 */
        fun followStream(): Pair<FakeMuxLink, String> {
            val link = links.single()
            val streamId = link.opened.entries.lastOrNull { it.value == "session/follow" }?.key
                ?: error("单聊没有订阅 session/follow")
            return link to streamId
        }

        fun followStreamCount(): Int =
            links.sumOf { link -> link.opened.values.count { it == "session/follow" } }

        fun close() {
            mux.close()
            scope.cancel()
        }

        companion object {
            /** [rule] 提供测试调度器（Dispatchers.Main 被替换成它）。 */
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
    fun presentDeclaredInThisTurnShowsTheArtifactCard() {
        val harness = Harness.create(mainDispatcherRule)
        try {
            harness.start()
            val (link, followId) = harness.followStream()

            // 一轮：turn/start → present 声明交付 → turn/end。
            link.deliverHostEvent(followId, frame(sessionEvent(10, "turn/start", JSONObject().put("turn", 1))))
            link.deliverHostEvent(
                followId,
                frame(presented(11, turn = 1, callId = "call-1", files = listOf("/workspace/报告.pptx" to "幻灯片"))),
            )
            link.deliverHostEvent(followId, frame(sessionEvent(12, "turn/end", JSONObject().put("turn", 1))))
            harness.scheduler.runCurrent()

            val cards = harness.viewModel.uiState.value.entries.filter { it.kind == ChatEntryKind.ARTIFACTS }
            assertTrue(
                "turn/end 已到没推出产物卡（现有条目：" +
                    harness.viewModel.uiState.value.entries.joinToString { "${it.kind}@${it.seq}" } + "）",
                cards.isNotEmpty(),
            )
            assertEquals("卡的位置应固定在这一轮 turn/end（seq=12）", 12L, cards.single().seq)
            assertEquals(listOf("/workspace/报告.pptx"), cards.single().artifacts.map { it.path })
            assertTrue(cards.single().artifactsDelivered)
        } finally {
            harness.close()
        }
    }

    /** 回归点（新口径）：只给 turn/start + present 不出卡；turn/end 一到立刻出。 */
    @Test
    fun presentAloneDoesNotShowCardUntilTurnEnd() {
        val harness = Harness.create(mainDispatcherRule)
        try {
            harness.start()
            val (link, followId) = harness.followStream()

            link.deliverHostEvent(followId, frame(sessionEvent(30, "turn/start", JSONObject().put("turn", 2))))
            link.deliverHostEvent(
                followId,
                frame(presented(31, turn = 2, callId = "call-2", files = listOf("/workspace/图.png" to null))),
            )
            harness.scheduler.runCurrent()

            assertTrue(
                "轮没闭合就出了卡（应等 turn/end）：" +
                    harness.viewModel.uiState.value.entries.joinToString { "${it.kind}@${it.seq}" },
                harness.viewModel.uiState.value.entries.none { it.kind == ChatEntryKind.ARTIFACTS },
            )

            link.deliverHostEvent(followId, frame(sessionEvent(35, "turn/end", JSONObject().put("turn", 2))))
            harness.scheduler.runCurrent()

            val cards = harness.viewModel.uiState.value.entries.filter { it.kind == ChatEntryKind.ARTIFACTS }
            assertEquals("turn/end 到了该出一张卡", 1, cards.size)
            assertEquals("卡应固定贴在 turn/end（seq=35）", 35L, cards.single().seq)
        } finally {
            harness.close()
        }
    }

    /** 同一轮多次 present：聚合成一张卡，同路径首现定序、描述取最新。 */
    @Test
    fun twoPresentsInOneTurnAggregateIntoOneCard() {
        val harness = Harness.create(mainDispatcherRule)
        try {
            harness.start()
            val (link, followId) = harness.followStream()

            link.deliverHostEvent(followId, frame(sessionEvent(40, "turn/start", JSONObject().put("turn", 3))))
            link.deliverHostEvent(
                followId,
                frame(
                    presented(
                        41,
                        turn = 3,
                        callId = "call-3",
                        files = listOf("/workspace/a.docx" to "初稿", "/workspace/b.png" to null),
                    ),
                ),
            )
            link.deliverHostEvent(
                followId,
                frame(presented(42, turn = 3, callId = "call-4", files = listOf("/workspace/a.docx" to "终稿"))),
            )
            link.deliverHostEvent(followId, frame(sessionEvent(45, "turn/end", JSONObject().put("turn", 3))))
            harness.scheduler.runCurrent()

            val cards = harness.viewModel.uiState.value.entries.filter { it.kind == ChatEntryKind.ARTIFACTS }
            assertEquals("同轮多次 present 应聚合成一张卡", 1, cards.size)
            assertEquals(45L, cards.single().seq)
            assertEquals(
                listOf("/workspace/a.docx", "/workspace/b.png"),
                cards.single().artifacts.map { it.path },
            )
            assertEquals("同路径的描述应取最新一次声明", "终稿", cards.single().artifacts.first().description)
        } finally {
            harness.close()
        }
    }

    /** `present` 的工具行：call 时「正在交付 N 个文件…」，result 到了换成「已交付 N 个文件」。 */
    @Test
    fun presentToolRowShowsDeliveringThenDelivered() {
        val harness = Harness.create(mainDispatcherRule)
        try {
            harness.start()
            val (link, followId) = harness.followStream()

            link.deliverHostEvent(
                followId,
                frame(
                    sessionEvent(
                        20,
                        "tool/call",
                        JSONObject()
                            .put("turn", 4)
                            .put("callId", "p1")
                            .put("name", "present")
                            .put(
                                "arguments",
                                JSONObject().put(
                                    "files",
                                    JSONArray()
                                        .put(JSONObject().put("path", "/workspace/a.docx"))
                                        .put(JSONObject().put("path", "/workspace/b.png")),
                                ),
                            ),
                    ),
                ),
            )
            harness.scheduler.runCurrent()

            val pendingRow = harness.viewModel.uiState.value.entries.firstOrNull { it.id == "tool:20" }
            assertTrue("present 调用后没有「正在交付」行", pendingRow != null)
            assertEquals("正在交付 2 个文件…", pendingRow?.text)

            link.deliverHostEvent(followId, frame(toolResult(21, callId = "p1")))
            harness.scheduler.runCurrent()

            val entries = harness.viewModel.uiState.value.entries
            assertTrue("结果到了「正在交付」行还没撤", entries.none { it.id == "tool:20" })
            val doneRow = entries.firstOrNull { it.id == "tool:21" }
            assertTrue("结果到了没有「已交付」行", doneRow != null)
            assertEquals("已交付 2 个文件", doneRow?.text)
        } finally {
            harness.close()
        }
    }

    /**
     * 快照窗口边缘的 present：调用的 result 落在窗口外（窗口太短 / 当时页没开着），
     * 「正在交付」行不能挂一辈子 —— 快照处理完要把还没等到结果的 present 行撤掉
     * （`BotChatViewModel.handleFollowFrame` 的快照收尾清理）。
     */
    @Test
    fun snapshotDropsAPendingPresentRowWhoseResultFellOutside() {
        val harness = Harness.create(mainDispatcherRule)
        try {
            harness.start()
            val (link, followId) = harness.followStream()

            // 实时流里先来一条 present 调用（「正在交付」行挂出来）。
            link.deliverHostEvent(
                followId,
                frame(
                    sessionEvent(
                        50,
                        "tool/call",
                        JSONObject()
                            .put("turn", 5)
                            .put("callId", "p5")
                            .put("name", "present")
                            .put(
                                "arguments",
                                JSONObject().put("files", JSONArray().put(JSONObject().put("path", "/workspace/x.docx"))),
                            ),
                    ),
                ),
            )
            harness.scheduler.runCurrent()
            assertTrue(
                "「正在交付」行没有挂出来",
                harness.viewModel.uiState.value.entries.any { it.id == "tool:50" },
            )

            // 网关补发一条快照：窗口里只有这轮的 turn/start + present 调用，result 在窗口外。
            link.deliverHostEvent(
                followId,
                JSONObject()
                    .put("type", "snapshot")
                    .put(
                        "records",
                        JSONArray().put(
                            JSONObject().put(
                                "type",
                                "event",
                            ).put(
                                "event",
                                sessionEvent(48, "turn/start", JSONObject().put("turn", 5)),
                            ),
                        ).put(
                            JSONObject()
                                .put("type", "event")
                                .put(
                                    "event",
                                    sessionEvent(
                                        50,
                                        "tool/call",
                                        JSONObject()
                                            .put("turn", 5)
                                            .put("callId", "p5")
                                            .put("name", "present")
                                            .put(
                                                "arguments",
                                                JSONObject().put(
                                                    "files",
                                                    JSONArray().put(JSONObject().put("path", "/workspace/x.docx")),
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    ),
            )
            harness.scheduler.runCurrent()

            assertTrue(
                "快照处理完还留着悬挂的「正在交付」行",
                harness.viewModel.uiState.value.entries.none { it.id == "tool:50" },
            )
        } finally {
            harness.close()
        }
    }

    /** follow 流正常结束（网关 TYPE_END）：按退避自动重开一条新流。 */
    @Test
    fun followStreamReopensAfterEnd() {
        val harness = Harness.create(mainDispatcherRule)
        try {
            harness.start()
            val (link, followId) = harness.followStream()
            assertEquals(1, harness.followStreamCount())

            link.deliver(JSONObject().put("type", "end").put("streamId", followId))
            harness.scheduler.runCurrent()
            // 退避 1s 起步：虚拟时间推进到位，重开的流应该出现。
            harness.scheduler.advanceTimeBy(1_000)
            harness.scheduler.runCurrent()

            assertEquals("follow 断流后没有自动重订", 2, harness.followStreamCount())
        } finally {
            harness.close()
        }
    }

    /** 会话已归档/不存在这类永久失败：不重试。 */
    @Test
    fun followStreamDoesNotRetryOnPermanentError() {
        val harness = Harness.create(mainDispatcherRule)
        try {
            harness.start()
            val (link, followId) = harness.followStream()

            link.deliver(
                JSONObject()
                    .put("type", "error")
                    .put("streamId", followId)
                    .put("error", JSONObject().put("code", "session/not-found")),
            )
            harness.scheduler.runCurrent()
            harness.scheduler.advanceTimeBy(60_000)
            harness.scheduler.runCurrent()

            assertEquals("永久失败不该重订", 1, harness.followStreamCount())
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

    private fun presented(seq: Long, turn: Int, callId: String, files: List<Pair<String, String?>>) =
        sessionEvent(
            seq,
            "deliverables/presented",
            JSONObject()
                .put("turn", turn)
                .put("callId", callId)
                .put(
                    "files",
                    JSONArray().apply {
                        files.forEach { (path, description) ->
                            put(JSONObject().put("path", path).also { if (description != null) it.put("description", description) })
                        }
                    },
                ),
        )

    private fun toolResult(seq: Long, callId: String) = sessionEvent(
        seq,
        "tool/result",
        JSONObject().put(
            "message",
            JSONObject()
                .put("toolCallId", callId)
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "ok"))),
        ),
    )

    private class FakeApi : DshRemoteApi {
        private val feedEvents = MutableSharedFlow<BotFeedEvent>(extraBufferCapacity = 8)

        override suspend fun bot(endpoint: String, payload: JSONObject): JSONObject = when (endpoint) {
            // 有 sessionId 才会订阅 session/follow。
            "chat", "room-chat" -> JSONObject()
                .put("sessionId", "s-1")
                .put("items", JSONArray())

            else -> JSONObject()
        }

        override suspend fun botValue(endpoint: String, payload: JSONObject): Any? = JSONObject()

        override suspend fun rpc(method: String, args: JSONObject, timeoutMs: Int): JSONObject = JSONObject()

        override fun feed(): Flow<BotFeedEvent> = feedEvents
    }
}
