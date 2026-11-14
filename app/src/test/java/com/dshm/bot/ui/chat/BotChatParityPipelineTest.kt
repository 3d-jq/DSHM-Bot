package com.dshm.bot.ui.chat

import com.dshm.bot.MainDispatcherRule
import com.dshm.bot.data.remote.BotFeedEvent
import com.dshm.bot.data.remote.DshClient
import com.dshm.bot.data.remote.DshMuxLog
import com.dshm.bot.data.remote.DshRemoteApi
import com.dshm.bot.data.remote.DsBotCapabilities
import com.dshm.bot.data.remote.FakeDshServer
import com.dshm.bot.data.remote.TimelineRequest
import com.dshm.bot.data.remote.TimelineRow
import com.dshm.bot.data.remote.TimelineRowPayload
import com.dshm.bot.data.remote.TimelineSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Phase 1b/1c 主管线切换的 VM 级测试：快照 + feed 增量 → entries、
 * 发送中→排队→插话→认领的三态流转、群聊 roomId 寻址与 running→false 收口、
 * kill-switch 回旧管线。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BotChatParityPipelineTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val defaultSink = DshMuxLog.sink

    @Before
    fun setUp() {
        DshMuxLog.sink = { }
        DsBotCapabilities.clear()
    }

    @After
    fun tearDown() {
        DshMuxLog.sink = defaultSink
        DsBotCapabilities.clear()
    }

    /** 用 FakeDshServer 协商一份声明了 `timeline` 能力的 hello（进程级单例）。 */
    private fun negotiateTimelineCapability() {
        val hello = JSONObject()
            .put("api", 1)
            .put("apiMin", 1)
            .put("apiMax", 1)
            .put("plugin", JSONObject().put("name", "ds-bot").put("version", "test"))
            .put("capabilities", JSONArray(listOf("state", "chat", "timeline")))
            .put("boot", "boot-1")
            .put("seq", 1)
        FakeDshServer().use { server ->
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) ->
                        FakeDshServer.cookie("abc")

                    request.path == "/api/bot" -> {
                        val requested = JSONObject(request.body).optString("endpoint")
                        val value = if (requested == "hello") hello.toString() else "{}"
                        FakeDshServer.Response(200, body = """{"ok":true,"value":$value}""")
                    }

                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()
            runBlocking { DsBotCapabilities.refresh(server.endpoint, DshClient(server.endpoint)) }
        }
        assertTrue(DsBotCapabilities.timelineSupported())
    }

    private class FakeApi : DshRemoteApi {
        val feedEvents = MutableSharedFlow<BotFeedEvent>(extraBufferCapacity = 16)
        val timelineRequests = mutableListOf<TimelineRequest>()
        val chatEndpoints = mutableListOf<String>()
        val rpcCalls = mutableListOf<Pair<String, JSONObject>>()
        val sendRequestIds = mutableListOf<String>()
        var snapshotRows: List<TimelineRow> = emptyList()
        var snapshotSeq: Long = 40

        override suspend fun bot(endpoint: String, payload: JSONObject): JSONObject = when (endpoint) {
            "chat", "room-chat" -> {
                chatEndpoints += endpoint
                JSONObject().put("sessionId", "s-1").put("items", JSONArray())
            }

            "send" -> {
                payload.optString("requestId").takeIf { it.isNotEmpty() }?.let(sendRequestIds::add)
                JSONObject()
            }

            else -> JSONObject()
        }

        override suspend fun timeline(request: TimelineRequest): TimelineSnapshot {
            timelineRequests += request
            return TimelineSnapshot(
                sessionId = "s-1",
                roomId = null,
                seq = snapshotSeq,
                rows = snapshotRows,
                cursor = null,
                running = null,
                speaking = null,
            )
        }

        override suspend fun rpc(method: String, args: JSONObject, timeoutMs: Int): JSONObject {
            rpcCalls += method to args
            return JSONObject()
        }

        override suspend fun botValue(endpoint: String, payload: JSONObject): Any? = JSONObject()
        override fun feed(): Flow<BotFeedEvent> = feedEvents

        fun row(key: String, kind: String, seq: Long?, requestId: String? = null, slot: String? = null) =
            TimelineRow(
                key = key,
                kind = kind,
                seq = seq,
                turn = null,
                timeMs = null,
                payload = when (kind) {
                    "inbox" -> TimelineRowPayload.Inbox("排队消息", slot ?: "next-turn", requestId, key.removePrefix("inbox:"))
                    else -> TimelineRowPayload.User("消息 $key", emptyList(), 0, requestId, live = false)
                },
            )

        /** feed `timeline` 增量帧（走真实 SSE 数据形状：JSON → 解析器）。 */
        fun emitTimeline(vararg rows: TimelineRow, isGroup: Boolean = false, running: Boolean? = null) {
            val data = JSONObject()
            if (isGroup) data.put("roomId", "room-1") else data.put("botId", "bot-1")
            data.put("seq", rows.maxOfOrNull { it.seq ?: 0L } ?: 0L)
            data.put(
                "rows",
                JSONArray().apply {
                    rows.forEach { row ->
                        val payloadJson = JSONObject()
                        when (val payload = row.payload) {
                            is TimelineRowPayload.User -> {
                                payloadJson.put("text", payload.text)
                                payload.requestId?.let { payloadJson.put("requestId", it) }
                                if (payload.live) payloadJson.put("live", true)
                            }

                            is TimelineRowPayload.Inbox -> {
                                payloadJson.put("text", payload.text).put("slot", payload.slot).put("itemId", payload.itemId)
                                payload.requestId?.let { payloadJson.put("requestId", it) }
                            }

                            else -> {}
                        }
                        put(
                            JSONObject()
                                .put("key", row.key)
                                .put("kind", row.kind)
                                .put("seq", row.seq ?: JSONObject.NULL)
                                .put("payload", payloadJson),
                        )
                    }
                },
            )
            running?.let { data.put("running", it) }
            feedEvents.tryEmit(BotFeedEvent(event = "timeline", data = data.toString()))
        }
    }

    private fun viewModel(
        api: FakeApi,
        scheduler: TestCoroutineScheduler,
        parity: Boolean = true,
        isGroup: Boolean = false,
    ): BotChatViewModel {
        val viewModel = BotChatViewModel(
            botId = if (isGroup) "room-1" else "bot-1",
            botName = "小助手",
            isGroup = isGroup,
            endpoint = null,
            appContext = null,
            dependencies = BotChatDependencies(
                remote = api, transport = null, speech = null, mux = null,
                timelineParity = parity,
            ),
        )
        scheduler.runCurrent()
        return viewModel
    }

    @Test
    fun snapshotAndIncrementDriveEntriesWithoutTheLegacyPipeline() {
        negotiateTimelineCapability()
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        api.snapshotRows = listOf(api.row("user:1", "user", 1L), api.row("bot:2", "bot", 2L))

        val viewModel = viewModel(api, scheduler)

        // 快照 → entries（首屏不闪骨架）。
        assertEquals(
            listOf("user:1", "bot:2"),
            viewModel.uiState.value.entries.map { it.id },
        )
        assertEquals(false, viewModel.uiState.value.loading)
        assertTrue("快照走 timeline 端点", api.timelineRequests.size == 1)
        assertTrue("parity 下不拉旧历史", api.chatEndpoints.isEmpty())

        // feed 增量 → 追行。
        api.emitTimeline(api.row("bot:3", "bot", 3L))
        scheduler.runCurrent()
        assertEquals(
            listOf("user:1", "bot:2", "bot:3"),
            viewModel.uiState.value.entries.map { it.id },
        )
    }

    @Test
    fun echoTransitionsThroughQueuedSteeringAndClaimed() {
        negotiateTimelineCapability()
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        api.snapshotRows = emptyList()
        val viewModel = viewModel(api, scheduler)
        scheduler.runCurrent()

        // 发送中：本地 p: 行（无 seq，追加尾部）。
        viewModel.send("排队消息")
        scheduler.runCurrent()
        val afterSend = viewModel.uiState.value.entries
        assertEquals(listOf("p:${lastRequestId(api)}"), afterSend.map { it.id })
        assertEquals(ChatEntryKind.USER, afterSend.single().kind)

        // 排队：服务端 inbox 行（requestId 命中）替换 p: 行。
        api.emitTimeline(api.row("inbox:i-1", "inbox", null, requestId = lastRequestId(api), slot = "next-turn"))
        scheduler.runCurrent()
        val queued = viewModel.uiState.value.entries
        assertEquals(listOf("inbox:i-1"), queued.map { it.id })
        assertEquals(ChatEntryKind.PENDING, queued.single().kind)
        assertEquals("next-turn", queued.single().pendingSlot)

        // 插话：同 key 行换成 next-step。
        api.emitTimeline(api.row("inbox:i-1", "inbox", null, requestId = lastRequestId(api), slot = "next-step"))
        scheduler.runCurrent()
        assertEquals("next-step", viewModel.uiState.value.entries.single().pendingSlot)

        // 认领：user 行带 requestId → 渲染键保留 p:<requestId>，元素原地过渡。
        api.emitTimeline(api.row("user:7", "user", 7L, requestId = lastRequestId(api)))
        scheduler.runCurrent()
        val claimed = viewModel.uiState.value.entries
        assertEquals(listOf("p:${lastRequestId(api)}"), claimed.map { it.id })
        assertEquals(ChatEntryKind.USER, claimed.single().kind)
        assertEquals(7L, claimed.single().seq)
    }

    @Test
    fun recallPendingSendsUpdateQueueRemoveAndDropsTheRow() {
        negotiateTimelineCapability()
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        api.snapshotRows = listOf(api.row("inbox:i-1", "inbox", null, requestId = "req-1", slot = "next-turn"))
        val viewModel = viewModel(api, scheduler)
        scheduler.runCurrent()

        val pending = viewModel.uiState.value.entries.single()
        viewModel.recallPending(pending)
        scheduler.runCurrent()

        val (method, args) = api.rpcCalls.single()
        assertEquals("session/updateQueue", method)
        val request = args.getJSONObject("request")
        assertEquals("i-1", request.optString("itemId"))
        assertEquals("remove", request.getJSONObject("action").optString("kind"))
        assertTrue("撤回后行先乐观摘除", viewModel.uiState.value.entries.isEmpty())
    }

    @Test
    fun groupChatUsesRoomIdAndRefetchesSnapshotWhenRoundCloses() {
        negotiateTimelineCapability()
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        api.snapshotRows = listOf(api.row("user:relay-9:0", "user", null), api.row("bot:relay-9:1", "bot", null))
        val viewModel = viewModel(api, scheduler, isGroup = true)
        scheduler.runCurrent()

        // 群聊以 roomId 寻址（快照请求 payload 带 roomId，不带 botId）。
        val first = api.timelineRequests.single()
        assertEquals("room-1", first.toPayload().optString("roomId"))
        assertTrue(!first.toPayload().has("botId"))
        // 群行是位置序：seq=null 的 relay 行也按宿主给的顺序原样呈现。
        assertEquals(
            listOf("user:relay-9:0", "bot:relay-9:1"),
            viewModel.uiState.value.entries.map { it.id },
        )

        // round 开跑：不重拉快照。
        api.emitTimeline(api.row("bot:relay-10:0", "bot", null), isGroup = true, running = true)
        scheduler.runCurrent()
        assertEquals(1, api.timelineRequests.size)

        // round 收口（true→false 边沿）：全量重拉快照（群产物卡只在 snapshot 里聚合）。
        api.emitTimeline(api.row("event:12", "event", 12L), isGroup = true, running = false)
        scheduler.runCurrent()
        assertEquals(2, api.timelineRequests.size)
        assertNull("收口重拉是全量（不带 since）", api.timelineRequests.last().since)
    }

    @Test
    fun killSwitchFallsBackToTheLegacyPipeline() {
        negotiateTimelineCapability()
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        // 注入位不点 parity：kill-switch 关 → 整条旧管线。
        val viewModel = viewModel(api, scheduler, parity = false)
        scheduler.runCurrent()

        assertTrue("回退路径要拉旧历史", api.chatEndpoints.isNotEmpty())
        assertEquals("回退路径不发 timeline 请求", 0, api.timelineRequests.size)

        // 旧管线的 feed message 继续驱动 entries。
        api.feedEvents.tryEmit(
            BotFeedEvent(
                event = "message",
                data = JSONObject()
                    .put("botId", "bot-1")
                    .put("item", JSONObject().put("kind", "bot").put("text", "旧管线回复").put("seq", 5))
                    .toString(),
            ),
        )
        scheduler.runCurrent()
        assertTrue(viewModel.uiState.value.entries.any { it.text == "旧管线回复" })
    }

    private fun lastRequestId(api: FakeApi): String =
        api.sendRequestIds.lastOrNull() ?: error("没有 send 记录")
}
