package com.dshm.bot.ui.chat

import com.dshm.bot.MainDispatcherRule
import com.dshm.bot.data.remote.BotFeedEvent
import com.dshm.bot.data.remote.DshMuxLog
import com.dshm.bot.data.remote.DshRemoteApi
import com.dshm.bot.data.remote.SessionEvent
import com.dshm.bot.data.remote.TimelineRequest
import com.dshm.bot.data.remote.TimelineRow
import com.dshm.bot.data.remote.TimelineRowPayload
import com.dshm.bot.data.remote.TimelineSnapshot
import com.dshm.bot.domain.model.RoomMemberUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Phase 1b/1c 行管线的 VM 级测试（唯一管线）：快照 + feed 增量 → entries、
 * 发送中→排队→插话→认领的三态流转、撤回动作、群聊 roomId 寻址与 running→false 收口、
 * earlier 游标分页。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BotChatParityPipelineTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val defaultSink = DshMuxLog.sink

    @Before
    fun setUp() {
        DshMuxLog.sink = { }
        // 进程级对话缓存是全局单例：不清会让上一个用例的 entries 铺到下一个用例上
        // （每个用例的 botId 都是 bot-1，会互相污染）。
        BotChatStateCache.clear()
    }

    @After
    fun tearDown() {
        DshMuxLog.sink = defaultSink
        BotChatStateCache.clear()
    }

    private class FakeApi : DshRemoteApi {
        val feedEvents = MutableSharedFlow<BotFeedEvent>(extraBufferCapacity = 16)
        val timelineRequests = mutableListOf<TimelineRequest>()
        val chatEndpoints = mutableListOf<String>()
        val rpcCalls = mutableListOf<Pair<String, JSONObject>>()
        val botValueCalls = mutableListOf<Pair<String, JSONObject>>()
        val botCalls = mutableListOf<Pair<String, JSONObject>>()
        val sendRequestIds = mutableListOf<String>()

        /** 首屏快照（reset）与翻页（cursor）分别给的行。 */
        var snapshotRows: List<TimelineRow> = emptyList()
        var earlierRows: List<TimelineRow> = emptyList()
        var snapshotCursor: JSONObject? = null
        var earlierCursor: JSONObject? = null
        /** 多页翻页脚本（非空时优先，按顺序弹出；用尽后回落到 earlierRows/earlierCursor）。 */
        val earlierPages = ArrayDeque<Pair<List<TimelineRow>, JSONObject?>>()
        var snapshotSeq: Long = 40
        /** `state` 端点的回执（#3 的 running 判定用）。 */
        var stateResponse: JSONObject = JSONObject()

        /**
         * 带 `since` 的增量重放（宿主行缓冲快路径）回的行与游标。
         * 宿主那条路径**不描述分页边界**，游标回 null（`{cursor: null, replayed: true}`）。
         */
        var replayRows: List<TimelineRow> = emptyList()
        var replayCursor: JSONObject? = null

        override suspend fun bot(endpoint: String, payload: JSONObject): JSONObject {
            botCalls += endpoint to payload
            return when (endpoint) {
            // 旧管线端点：行管线不该再调用（调用就记下来，测试断言为空）。
            "chat", "room-chat" -> {
                chatEndpoints += endpoint
                JSONObject().put("sessionId", "s-1").put("items", JSONArray())
            }

            "send" -> {
                payload.optString("requestId").takeIf { it.isNotEmpty() }?.let(sendRequestIds::add)
                JSONObject()
            }

            // 忽略提问卡：像真宿主一样按会话把它从 state 里删掉（后续 refreshBotStatus 即对齐）。
            "dismiss-question" -> {
                stateResponse.optJSONObject("questions")?.remove(payload.optString("sessionId"))
                JSONObject()
            }

            "state" -> stateResponse

            else -> JSONObject()
            }
        }

        override suspend fun timeline(request: TimelineRequest): TimelineSnapshot {
            timelineRequests += request
            val paging = request.cursor != null
            val replaying = !paging && request.since != null
            val scripted = if (paging) earlierPages.removeFirstOrNull() else null
            return TimelineSnapshot(
                sessionId = "s-1",
                roomId = null,
                seq = snapshotSeq,
                rows = when {
                    scripted != null -> scripted.first
                    paging -> earlierRows
                    replaying -> replayRows
                    else -> snapshotRows
                },
                cursor = when {
                    scripted != null -> scripted.second
                    paging -> earlierCursor
                    replaying -> replayCursor
                    else -> snapshotCursor
                },
                running = null,
                speaking = null,
                cwd = snapshotCwd,
                eventFrom = snapshotEventFrom,
                eventTo = snapshotEventTo,
            )
        }

        override suspend fun rpc(method: String, args: JSONObject, timeoutMs: Int): JSONObject {
            rpcCalls += method to args
            return JSONObject()
        }

        override suspend fun botValue(endpoint: String, payload: JSONObject): Any? {
            botValueCalls += endpoint to payload
            return JSONObject().put("ok", true).put("removed", true)
        }
        override fun feed(): Flow<BotFeedEvent> = feedEvents

        val changesSummaryCalls = mutableListOf<Pair<String, Long>>()
        var changesSummaryResponse: JSONObject? = null

        override suspend fun changesSummary(sessionId: String, seq: Long): JSONObject? {
            changesSummaryCalls += sessionId to seq
            return changesSummaryResponse
        }

        /** 四期 4a：补洞查询的记录（from to through）。 */
        val eventsCalls = mutableListOf<Pair<Long, Long?>>()
        var eventsResponse: List<SessionEvent> = emptyList()

        override suspend fun events(sessionId: String, from: Long, through: Long?, max: Int): List<SessionEvent> {
            eventsCalls += from to through
            return eventsResponse
        }

        /** 快照带的事件区间（影子双轨/客户端折叠靠它取同一段事件）。 */
        var snapshotEventFrom: Long? = null
        var snapshotEventTo: Long? = null
        var snapshotCwd: String? = null


        /** 发一帧 `stream` feed 事件（phase=start/chunk/end）。 */
        fun emitStream(phase: String, attemptId: String, text: String? = null) {
            feedEvents.tryEmit(
                BotFeedEvent(
                    event = "stream",
                    data = JSONObject()
                        .put("botId", "bot-1")
                        .put("sessionId", "s-1")
                        .put("attemptId", attemptId)
                        .put("phase", phase)
                        .apply { if (text != null) put("text", text) }
                        .toString(),
                ),
            )
        }

        /** 发一帧原始 durable 事件（`session` feed）。带 data 时折叠才产得出对应行。 */
        fun emitSession(
            seq: Long,
            type: String,
            sessionId: String = "s-1",
            data: JSONObject? = null,
        ) {
            feedEvents.tryEmit(
                BotFeedEvent(
                    event = "session",
                    data = JSONObject()
                        .put("sessionId", sessionId)
                        .put("seq", seq)
                        .put("type", type)
                        .apply { if (data != null) put("data", data) }
                        .toString(),
                ),
            )
        }

        fun row(
            key: String,
            kind: String,
            seq: Long?,
            requestId: String? = null,
            slot: String? = null,
            sourceKind: String? = "user",
            name: String? = null,
            botId: String? = null,
        ) = TimelineRow(
            key = key,
            kind = kind,
            seq = seq,
            turn = null,
            timeMs = null,
            payload = when (kind) {
                "inbox" -> TimelineRowPayload.Inbox(
                    text = "排队消息",
                    slot = slot ?: "next-turn",
                    requestId = requestId,
                    itemId = key.removePrefix("inbox:"),
                    sourceKind = sourceKind,
                    botId = botId,
                    name = name,
                )

                "divider" -> TimelineRowPayload.Divider(timeMs = null)
                else -> TimelineRowPayload.User("消息 $key", emptyList(), 0, requestId, live = false)
            },
        )

        /** feed `timeline` 增量帧（走真实数据形状：JSON → 解析器）。 */
        fun emitTimeline(
            vararg rows: TimelineRow,
            isGroup: Boolean = false,
            running: Boolean? = null,
            from: Long? = null,
            bufferFrom: Long? = null,
        ) {
            val data = JSONObject()
            if (isGroup) data.put("roomId", "room-1") else data.put("botId", "bot-1")
            data.put("seq", rows.maxOfOrNull { it.seq ?: 0L } ?: 0L)
            from?.let { data.put("from", it) }
            bufferFrom?.let { data.put("bufferFrom", it) }
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
                                payload.sourceKind?.let { payloadJson.put("sourceKind", it) }
                                payload.botId?.let { payloadJson.put("botId", it) }
                                payload.name?.let { payloadJson.put("name", it) }
                            }

                            is TimelineRowPayload.Divider -> payload.timeMs?.let { payloadJson.put("timeMs", it) }

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
        isGroup: Boolean = false,
    ): BotChatViewModel {
        val viewModel = BotChatViewModel(
            botId = if (isGroup) "room-1" else "bot-1",
            botName = "小助手",
            isGroup = isGroup,
            endpoint = null,
            appContext = null,
            dependencies = BotChatDependencies(remote = api, transport = null, speech = null, mux = null),
        )
        scheduler.runCurrent()
        return viewModel
    }

    /**
     * 四期 4c：**私聊的行来自客户端自折叠**（事件日志 » fold），宿主行只作兜底。
     *
     * 契约：同一批事件，客户端折出来的行与宿主给的一致；而事件一到，界面就更新
     * （不必等宿主的行帧）——这就是「显示永远由事件日志派生」。
     */
    @Test
    fun privateChatRowsComeFromTheClientFold() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        // 宿主这一页只有「用户行」；事件日志里还有 bot 行 —— 若显示用宿主行就少了它。
        api.snapshotRows = listOf(api.row("user:2", "user", 2L))
        api.snapshotEventFrom = 1L
        api.snapshotEventTo = 3L
        api.snapshotCwd = "/root/w"
        api.eventsResponse = listOf(
            SessionEvent(1L, "turn/start", 1000L, JSONObject().put("turn", 1)),
            SessionEvent(2L, "user/message", 1100L, JSONObject().put("source", JSONObject().put("kind", "user")).put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "你好")))),
            SessionEvent(3L, "assistant/message", 1200L, JSONObject().put("turn", 1).put("message", JSONObject().put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "在的"))))),
        )

        val viewModel = viewModel(api, scheduler)
        scheduler.runCurrent()

        // 宿主只给了 user:2；客户端折叠多折出 bot:3（事件日志里有）。
        assertEquals(
            listOf("user:2", "bot:3"),
            viewModel.uiState.value.entries.map { it.id },
        )
    }

    /**
     * 四期 4c 的兜底：事件日志**还没播种 / 有缺口**时，不能让界面空着——
     * 继续用宿主的行顶上（缺口补上后自动切回客户端折叠）。
     */
    @Test
    fun theHostRowsAreTheFallbackWhenTheEventLogIsNotReady() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        api.snapshotRows = listOf(api.row("user:1", "user", 1L), api.row("bot:2", "bot", 2L))
        // 事件区间给了，但补洞拿不到事件（eventsResponse 为空）→ 日志始终不可用。
        api.snapshotEventFrom = 1L
        api.snapshotEventTo = 2L

        val viewModel = viewModel(api, scheduler)
        scheduler.runCurrent()

        assertEquals(
            "事件日志不可用时要用宿主行顶上（不能白屏）",
            listOf("user:1", "bot:2"),
            viewModel.uiState.value.entries.map { it.id },
        )
    }

    @Test
    fun snapshotAndIncrementDriveEntriesWithoutTheLegacyPipeline() {
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
        assertEquals("快照走 timeline 端点", 1, api.timelineRequests.size)
        assertTrue("行管线不该再调旧历史端点", api.chatEndpoints.isEmpty())

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

        // 排队：服务端 inbox 行（requestId 命中）确认了发送。
        api.emitTimeline(api.row("inbox:i-1", "inbox", null, requestId = lastRequestId(api), slot = "next-turn"))
        scheduler.runCurrent()
        // 排队行不进时间线（官方 web 的时间线也不出现排队行）；界面也没有队列条
        // （2026-11-15 用户定：去掉那个「N 条待发送」胶囊）。**本地 echo 气泡留着**
        // ——排队期间用户仍看得见自己那条消息，直到服务端回显原地接管它。
        assertTrue("排队行不该出现在时间线里：${viewModel.uiState.value.entries.map { it.id }}", viewModel.uiState.value.entries.none { it.id.startsWith("inbox:") })
        assertEquals(
            "排队期间本地 echo 气泡要留着（撑到回显）",
            listOf("p:${lastRequestId(api)}"),
            viewModel.uiState.value.entries.map { it.id },
        )
        val queued = viewModel.uiState.value.pendingQueue
        assertEquals(listOf("inbox:i-1"), queued.map { it.id })
        assertEquals(ChatEntryKind.PENDING, queued.single().kind)
        assertEquals("next-turn", queued.single().pendingSlot)

        // 插话：同 key 行换成 next-step。
        api.emitTimeline(api.row("inbox:i-1", "inbox", null, requestId = lastRequestId(api), slot = "next-step"))
        scheduler.runCurrent()
        assertEquals("next-step", viewModel.uiState.value.pendingQueue.single().pendingSlot)

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
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        api.snapshotRows = listOf(api.row("inbox:i-1", "inbox", null, requestId = "req-1", slot = "next-turn"))
        val viewModel = viewModel(api, scheduler)
        scheduler.runCurrent()

        val pending = viewModel.uiState.value.pendingQueue.single()
        viewModel.recallPending(pending)
        scheduler.runCurrent()

        val (method, args) = api.rpcCalls.single()
        assertEquals("session/updateQueue", method)
        val request = args.getJSONObject("request")
        assertEquals("i-1", request.optString("itemId"))
        assertEquals("remove", request.getJSONObject("action").optString("kind"))
        assertTrue("撤回后行先乐观摘除", viewModel.uiState.value.pendingQueue.isEmpty())
    }

    /**
     * 同一条消息已经成了 `user` 气泡 → 它的 `inbox`（排队/插话）行不能再出现在队列条：
     * 旧行为是「本地 withdraw，服务端再给一次就恢复」，于是每次刷新队列条都把「撤回」
     * 又摆出来（真机报「怎么每次都有个撤回」）。气泡还没到之前队列行保留，撤回能力不变。
     */
    @Test
    fun anInboxRowIsHiddenOnceItsMessageIsAUserBubble() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        api.snapshotRows = listOf(api.row("inbox:roomq:room-1:1", "inbox", null, requestId = "r-1"))
        val viewModel = viewModel(api, scheduler, isGroup = true)
        scheduler.runCurrent()
        assertEquals("还没成气泡：队列行保留（可撤回）", 1, viewModel.uiState.value.pendingQueue.size)

        api.emitTimeline(api.row("user:10", "user", 10L, requestId = "r-1"), isGroup = true)
        scheduler.runCurrent()
        assertTrue(
            "气泡与队列行是同一句话，队列行必须去掉",
            viewModel.uiState.value.pendingQueue.isEmpty(),
        )
        assertTrue(
            "气泡必须还在",
            viewModel.uiState.value.entries.any { it.id == "user:10" },
        )
    }

    /**
     * 四期 4a：原始 durable 事件按连续 seq 判定完整性。
     * 连续事件不补洞；跳号（`seq > cursor + 1`）才向宿主 `events` 端点补洞（对齐 dsh-Go `_fill`）。
     */
    @Test
    fun theEventLogDetectsGapsAndAsksTheHostForTheHoles() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        api.snapshotRows = listOf(api.row("user:10", "user", 10L))
        val viewModel = viewModel(api, scheduler)
        scheduler.runCurrent()
        api.eventsCalls.clear() // 首屏种子（默认空应答）

        assertEquals("还没有事件时游标是 -1", -1L, viewModel.sessionEventCursor())

        api.emitSession(20, "user/message")
        api.emitSession(21, "assistant/message")
        scheduler.runCurrent()
        assertTrue("连续事件不该补洞：${api.eventsCalls}", api.eventsCalls.isEmpty())
        assertEquals(21L, viewModel.sessionEventCursor())
        assertEquals(2, viewModel.sessionEventCount())

        // 丢了三帧（22..24）：必须补洞，且补洞期间游标不越过缺口。
        api.emitSession(25, "user/message")
        scheduler.runCurrent()
        assertEquals("跳号 22..24 应触发一次补洞", listOf(22L to 24L), api.eventsCalls)
        assertEquals("补洞结果为空时游标停在缺口前", 21L, viewModel.sessionEventCursor())

        // 宿主把洞补上：游标一路推进到已缓存的最新事件。
        api.eventsResponse = listOf(
            SessionEvent(22L, "user/message", null, null),
            SessionEvent(23L, "assistant/message", null, null),
            SessionEvent(24L, "user/message", null, null),
        )
        api.emitSession(26, "assistant/message")
        scheduler.runCurrent()
        assertEquals("补洞后游标应推进到 26", 26L, viewModel.sessionEventCursor())
    }

    @Test
    fun groupChatUsesRoomIdAndRefetchesSnapshotWhenRoundCloses() {
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

        // round 收口（true→false 边沿）：**合并**重放一次快照，把落定行对齐。
        // 旧实现是 reset=true（清空日志 + 只装回最新一页）：更早的轮会整片丢失，
        // 真机表现为「发完消息前面的气泡全没了，冷启动又正常」。
        api.emitTimeline(api.row("event:12", "event", 12L), isGroup = true, running = false)
        scheduler.runCurrent()
        assertEquals(2, api.timelineRequests.size)
        // 轮收口是**权威重读**：拉完整最新窗口（不带 since），用它对当前 part 收敛。
        assertNull("收口重拉是权威重读（不带 since）", api.timelineRequests.last().since)
        assertTrue(
            "收口刷新后更早的行必须还在（不能被清空）",
            viewModel.uiState.value.entries.map { it.id }
                .containsAll(listOf("user:relay-9:0", "bot:relay-9:1")),
        )
    }

    /** 权威重读会清掉当前 part 里宿主已不再给的行（已落定的 live 回复 / 被移除项）。 */
    @Test
    fun resyncPrunesRowsTheHostNoLongerEmits() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        api.snapshotRows = listOf(
            api.row("user:1", "user", 1L),
            api.row("bot:relay-1:0", "bot", null),
        )
        val viewModel = viewModel(api, scheduler, isGroup = true)
        scheduler.runCurrent()
        assertEquals(2, viewModel.uiState.value.entries.size)

        // 开跑：一行 live 回复进来（seq=null）。
        api.emitTimeline(api.row("bot:relay-2:9", "bot", null), isGroup = true, running = true)
        scheduler.runCurrent()
        assertTrue(
            "live 回复应在列",
            viewModel.uiState.value.entries.any { it.id == "bot:relay-2:9" },
        )

        // 收口：权威重读返回「餐厅快照」——旧的 live 回复已落定/不再在列，需被清掉。
        api.snapshotRows = listOf(
            api.row("user:1", "user", 1L),
            api.row("bot:relay-2:9", "bot", null), // 已落定的同 key 行（宿主给出的等价物）
        )
        api.emitTimeline(running = false, isGroup = true)
        scheduler.runCurrent()
        val ids = viewModel.uiState.value.entries.map { it.id }
        assertEquals(
            "重读后只留宿主给出的行，live 残留/过期行被清掉",
            listOf("user:1", "bot:relay-2:9"),
            ids,
        )
    }

    /**
     * earlier 分页走 timeline cursor：翻页请求带 cursor（beforeSeq），更早的一页
     * 按 key=身份**前插**进列表，游标推进到 null 时 `hasEarlier` 收掉。
     */
    @Test
    fun earlierPageLoadsWithCursorAndPrependsRows() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        // 首屏（最新）：user@10 + bot@11，游标 beforeSeq=10（还有更早的）。
        api.snapshotRows = listOf(api.row("user:10", "user", 10L), api.row("bot:11", "bot", 11L))
        api.snapshotCursor = JSONObject().put("beforeSeq", 10)

        val viewModel = viewModel(api, scheduler)
        assertEquals(listOf("user:10", "bot:11"), viewModel.uiState.value.entries.map { it.id })
        assertTrue("游标说还有更早的历史", viewModel.uiState.value.hasEarlier)

        // 翻一页：older@5 插在前面，游标收口。
        api.earlierRows = listOf(api.row("bot:5", "bot", 5L))
        api.earlierCursor = null
        viewModel.loadEarlier()
        scheduler.runCurrent()

        val pagingRequest = api.timelineRequests.last()
        assertEquals("翻页请求带游标", 10L, pagingRequest.cursor?.optLong("beforeSeq"))
        assertEquals(
            "更早的一页应插在前面（渲染 id 带命名空间前缀，见 earlierPagesStillShow…）",
            listOf("earlier:bot:5", "user:10", "bot:11"),
            viewModel.uiState.value.entries.map { it.id },
        )
        assertFalse("最后一页之后 hasEarlier 应收掉", viewModel.uiState.value.hasEarlier)
    }

    /**
     * 回归（2026-11-15 真机报「加载更早历史转圈、加载不出来」）：
     * 4c 切换后私聊的显示来自**客户端自折叠**，而更早分页还是**宿主的行** ——
     * 两者要拼起来（更早页 + 当前段），不能只取折叠那一段。
     *
     * 事件日志可用（折叠接管）时，更早页必须照样显示。
     */
    /** 构造一条 user/message 事件（真机形状：source.kind=user + content 文本块）。 */
    private fun userEvent(seq: Long, timeMs: Long, text: String): SessionEvent = SessionEvent(
        seq,
        "user/message",
        timeMs,
        JSONObject()
            .put("source", JSONObject().put("kind", "user"))
            .put(
                "content",
                org.json.JSONArray().put(JSONObject().put("type", "text").put("text", text)),
            ),
    )

    /** 构造一条 assistant/message 事件（真机形状：turn + message.content 文本块）。 */
    private fun assistantEvent(seq: Long, timeMs: Long, text: String): SessionEvent = SessionEvent(
        seq,
        "assistant/message",
        timeMs,
        JSONObject()
            .put("turn", 1)
            .put(
                "message",
                JSONObject().put(
                    "content",
                    org.json.JSONArray().put(JSONObject().put("type", "text").put("text", text)),
                ),
            ),
    )

    /**
     * 回归（2026-11-15 全面 review 发现）：4c 把私聊显示切到客户端折叠后，
     * 折叠**没实现**的三类行（只读视图卡 / 回答过的提问 / Bot 互答提示）会整类消失。
     *
     * 现在折叠自己产这三类（不再从宿主补行），这里验「折叠产出的它们真的进了显示」。
     */
    /**
     * 回归（2026-11-15 真机报「浏览器网页卡每次都在发信息的后面」）：
     * 缩略图卡原先**永远追加到 entries 末尾**，于是下一条消息一来，它就跑到新消息
     * 后面去了。现在按「截图那一刻的 seq」地锚插回它所属那一轮的轮末。
     */
    /**
     * 回归（2026-11-16 真机报「流式完成时会看到两个」）：流式气泡必须**按 seq**
     * 立刻让位给落定行（对齐 dsh-Go：durable 消息一到就删同 turn/step 的草稿），
     * 不能靠文本比对 + 200ms 定时器 —— 文本对不上时那段时间就是双份。
     */
    /**
     * 重进对话（Activity 被系统重建 → 新 VM）：**先把进程缓存里的内容铺上**，
     * 不能回到骨架屏（loading + 空列表）。对齐 dsh-Go
     * `_retained(items: transcript.items, loading: items.isEmpty)`。
     */
    /**
     * 自己发的问题**永远在回答上面**（真机 2026-11-17：「大模型流出完后，我的问题气泡
     * 会跑到大模型流式输出完的下面，然后又消失」）。
     *
     * 复现条件：宿主的 user 行/事件还没回来，回答已经落定 —— 本地 echo 就是这条问题的
     * 唯一代表。以前 echo 一律追加到 entries 末尾 → 落定后它成了最后一条 = 回答的下方。
     */
    @Test
    fun theSentQuestionNeverFallsBelowTheAnswer() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        api.snapshotRows = listOf(api.row("user:10", "user", 10L))
        api.snapshotEventFrom = 10L
        api.snapshotEventTo = 10L
        api.snapshotSeq = 40
        api.eventsResponse = listOf(userEvent(10L, 1000L, "你好"))
        val vm = viewModel(api, scheduler)
        scheduler.runCurrent()

        vm.send("新问题")
        scheduler.runCurrent()
        val rid = api.sendRequestIds.last()
        assertEquals(
            "发送后本地 echo 就是最新一条",
            listOf("user:10", "p:$rid"),
            vm.uiState.value.entries.map { it.id },
        )

        api.emitStream(phase = "start", attemptId = "a1")
        api.emitStream(phase = "chunk", attemptId = "a1", text = "这是回答")
        scheduler.runCurrent()
        assertEquals(
            "流式中：问题在上、流式气泡在下",
            listOf("user:10", "p:$rid", "live:1"),
            vm.uiState.value.entries.map { it.id },
        )

        // 落定：只有回答进了日志（宿主的 user 行还没到）→ echo 必须仍在回答**上面**。
        api.eventsResponse = buildList {
            add(userEvent(10L, 1_000L, "你好"))
            for (seq in 11L..40L) add(SessionEvent(seq, "step/start", 1_000L + seq, null))
            add(assistantEvent(41L, 1_041L, "这是回答"))
        }
        api.emitSession(
            41L, "assistant/message",
            data = JSONObject().put("turn", 1).put(
                "message",
                JSONObject().put(
                    "content",
                    org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "这是回答")),
                ),
            ),
        )
        scheduler.runCurrent()
        val ids = vm.uiState.value.entries.map { it.id }
        assertEquals(
            "落定后：问题仍在回答上面（不能掉到下面）",
            listOf("user:10", "p:$rid", "live:1"),
            ids,
        )
    }

    /**
     * 宿主的 user 行先到、但**显示来源（客户端折叠）还没有这条行**时，
     * 本地 echo 不能先摘掉 —— 摘了气泡会凭空消失一会儿（真机「然后又消失」）。
     */
    @Test
    fun theEchoStaysUntilTheDisplayedRowsCarryIt() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        api.snapshotRows = listOf(api.row("user:10", "user", 10L))
        api.snapshotEventFrom = 10L
        api.snapshotEventTo = 10L
        api.snapshotSeq = 40
        api.eventsResponse = buildList {
            add(userEvent(10L, 1_000L, "你好"))
            for (seq in 11L..40L) add(SessionEvent(seq, "step/start", 1_000L + seq, null))
        }
        val vm = viewModel(api, scheduler)
        scheduler.runCurrent()

        vm.send("新问题")
        scheduler.runCurrent()
        val rid = api.sendRequestIds.last()

        // 宿主行认领（absorbParityRow 那条路），但折叠里还没有 user:41 这条行。
        api.emitTimeline(api.row("user:41", "user", 41L, requestId = rid))
        scheduler.runCurrent()
        assertTrue(
            "认领了但显示里还没有正式行时，气泡要留着：${vm.uiState.value.entries.map { it.id }}",
            vm.uiState.value.entries.any { it.id == "p:$rid" },
        )

        // 正式行真的进显示（折叠产出带 requestId 的 user 行）→ echo 摘掉、原地接管。
        api.eventsResponse = buildList {
            add(userEvent(10L, 1_000L, "你好"))
            for (seq in 11L..40L) add(SessionEvent(seq, "step/start", 1_000L + seq, null))
            add(
                SessionEvent(
                    41L, "user/message", 1_041L,
                    JSONObject()
                        .put("source", JSONObject().put("kind", "user").put("rpcId", rid))
                        .put(
                            "content",
                            org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "新问题")),
                        ),
                ),
            )
        }
        api.emitSession(
            41L, "user/message",
            data = JSONObject()
                .put("source", JSONObject().put("kind", "user").put("rpcId", rid))
                .put(
                    "content",
                    org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "新问题")),
                ),
        )
        scheduler.runCurrent()
        assertEquals(
            "显示里有正式行后只剩一条（同 key 原地接管，不重复也不消失）",
            listOf("user:10", "p:$rid"),
            vm.uiState.value.entries.map { it.id },
        )
    }

    @Test
    fun reenteringTheChatPaintsTheCachedTimelineInsteadOfASkeleton() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        api.snapshotRows = listOf(api.row("user:10", "user", 10L))
        api.snapshotEventFrom = 10L
        api.snapshotEventTo = 10L
        api.snapshotSeq = 40
        api.eventsResponse = listOf(userEvent(10L, 1_000L, "你好"))

        val first = viewModel(api, scheduler)
        scheduler.runCurrent()
        val painted = first.uiState.value.entries.map { it.id }
        assertTrue("首个 VM 应已画出内容", painted.isNotEmpty())
        assertFalse(first.uiState.value.loading)

        // Activity 重建：同一个 bot，新的 VM，且**快照还没返回**（不跑 scheduler）。
        val second = BotChatViewModel(
            botId = "bot-1",
            botName = "小助手",
            isGroup = false,
            endpoint = null,
            appContext = null,
            dependencies = BotChatDependencies(
                remote = FakeApi(),
                transport = null,
                speech = null,
                mux = null,
            ),
        )
        assertEquals(
            "重进对话必须先把缓存内容铺上（否则就是骨架屏）",
            painted,
            second.uiState.value.entries.map { it.id },
        )
        assertFalse("有缓存内容时不该再算 loading", second.uiState.value.loading)
    }

    @Test
    fun aSettledBotRowClearsTheStreamingBubbleBySeq() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        api.snapshotRows = listOf(api.row("user:10", "user", 10L))
        api.snapshotEventFrom = 10L
        api.snapshotEventTo = 10L
        api.snapshotSeq = 40
        api.eventsResponse = listOf(userEvent(10L, 1000L, "你好"))

        val vm = viewModel(api, scheduler)
        scheduler.runCurrent()

        // 流式开始 + 一段文本（长度随便，判据不再看长度/文本）。
        api.emitStream(phase = "start", attemptId = "a1")
        api.emitStream(phase = "chunk", attemptId = "a1", text = "这是模型的回复")
        scheduler.runCurrent()
        assertTrue("流式气泡应在显示", vm.streamingText.value.isNotEmpty())

        // 流式与落定必须是**同一个 LazyColumn item**（同一个渲染键）：流式期 entries
        // 尾部有一条乐观 bot 行（id = live:<n>），落定行认领它 → 元素原地复用。
        // 换 item 会让 markdown 片段重新组合（缓存冷未命中时高度为 0 → 整列跳一下）。
        val liveEntry = vm.uiState.value.entries.last()
        assertEquals(ChatEntryKind.BOT, liveEntry.kind)
        assertTrue(
            "流式中的条目应是乐观 bot 行：${liveEntry.id}",
            liveEntry.id.startsWith(LIVE_ROW_ID_PREFIX),
        )

        // 落定行（seq 41 > 流式起点 40）到达 → 流式气泡必须**立刻**消失，不留双份。
        // 注意要走「事件进日志」这条路：私聊的显示由折叠产出，光发 timeline 行不会进显示。
        // 日志必须连续（折叠只在连续区间上工作），把 11..40 用折叠不认的事件补齐。
        api.eventsResponse = buildList {
            add(userEvent(10L, 1_000L, "你好"))
            for (seq in 11L..40L) add(SessionEvent(seq, "step/start", 1_000L + seq, null))
            add(assistantEvent(41L, 1_041L, "这是模型的回复"))
        }
        api.emitSession(
            41L,
            "assistant/message",
            data = JSONObject().put("turn", 1).put(
                "message",
                JSONObject().put(
                    "content",
                    org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "这是模型的回复")),
                ),
            ),
        )
        scheduler.runCurrent()
        assertTrue(
            "落定行到位后流式气泡必须立刻消失（否则会看到两个）：${vm.streamingText.value}",
            vm.streamingText.value.isEmpty(),
        )
        val botEntries = vm.uiState.value.entries.filter { it.kind == ChatEntryKind.BOT }
        assertEquals("落定后只能有一条 bot 条目（乐观行应被认领，不能残留第二条）", 1, botEntries.size)
        assertEquals(
            "落定行必须接管流式气泡的渲染键（同一个 LazyColumn item，不换元素）",
            liveEntry.id,
            botEntries.single().id,
        )
    }

    @Test
    fun aBrowserShotStaysInItsTurnInsteadOfFollowingTheNextMessage() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        api.snapshotRows = listOf(api.row("bot:10", "bot", 10L))
        api.snapshotSeq = 40
        val viewModel = viewModel(api, scheduler)
        scheduler.runCurrent()

        val url = "https://example.com/a"
        viewModel.addBrowserShot(
            ChatBrowserShot(path = "/tmp/a.png", url = url, title = "A", timeMs = 1_000L),
        )
        scheduler.runCurrent()
        val shotId = "shot:1000:${url.hashCode()}"
        assertEquals(listOf("bot:10", shotId), viewModel.uiState.value.entries.map { it.id })

        // 又来一条新消息（seq 更大）：缩略图必须留在它自己那一轮，不能被顶到后面。
        api.emitTimeline(api.row("user:42", "user", 42L))
        scheduler.runCurrent()
        assertEquals(
            "缩略图应留在它那一轮之后、新消息之前",
            listOf("bot:10", shotId, "user:42"),
            viewModel.uiState.value.entries.map { it.id },
        )
    }

    @Test
    fun theFoldProducesViewAnsweredAndBotExchangeRows() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        // 宿主当前段只给一条 user 行；其余靠折叠从事件里产出。
        api.snapshotRows = listOf(api.row("user:10", "user", 10L))
        api.snapshotEventFrom = 10L
        api.snapshotEventTo = 12L
        api.eventsResponse = listOf(
            userEvent(10L, 1000L, "你好"),
            SessionEvent(
                11L, "tool/call", 1200L,
                JSONObject().put("turn", 1).put("callId", "v1").put("name", "show_view").put(
                    "arguments",
                    JSONObject().put("title", "只读视图")
                        .put("view", JSONObject().put("type", "text").put("text", "内容")),
                ),
            ),
            // Bot 之间的一次往来。
            SessionEvent(
                12L, "user/message", 1400L,
                JSONObject().put(
                    "source",
                    JSONObject().put("kind", "bot").put("role", "request")
                        .put("senderSessionId", "s2").put("senderName", "小助手"),
                ).put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "帮我查一下"))),
            ),
        )

        val viewModel = viewModel(api, scheduler)
        scheduler.runCurrent()

        val ids = viewModel.uiState.value.entries.map { it.id }
        assertTrue("当前段由折叠接管：$ids", "user:10" in ids)
        assertTrue("折叠要产出只读视图卡：$ids", "view:v1" in ids)
        assertTrue("折叠要产出 Bot 互答行：$ids", "event:12" in ids)
    }

    /**
     * 回归（同一次 review）：resync 快照只是「最新一页」，**不能把折叠范围往前收窄**
     * —— 否则已在屏上的历史整段消失（真机「最近的对话没了」在 resync 路径上的残留）。
     */
    @Test
    fun aResyncDoesNotShrinkTheFoldedRange() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        api.snapshotRows = listOf(
            api.row("user:101", "user", 101L),
            api.row("user:110", "user", 110L),
        )
        api.snapshotEventFrom = 100L
        api.snapshotEventTo = 110L
        api.eventsResponse = listOf(
            userEvent(101L, 1010L, "第一条"),
            userEvent(110L, 1100L, "第二条"),
        )

        val viewModel = viewModel(api, scheduler)
        scheduler.runCurrent()
        assertEquals(listOf("user:101", "user:110"), viewModel.uiState.value.entries.map { it.id })

        // resync：宿主这一页的起点更靠后（110），但同一会话内范围不能收窄。
        api.snapshotEventFrom = 110L
        api.snapshotEventTo = 111L
        api.eventsResponse = listOf(userEvent(110L, 1100L, "第二条"), userEvent(111L, 1110L, "第三条"))
        api.emitTimeline(api.row("user:111", "user", 111L), isGroup = false, running = null, from = 111L)
        scheduler.runCurrent()

        val ids = viewModel.uiState.value.entries.map { it.id }
        assertTrue("resync 不能把历史收窄掉：$ids", "user:101" in ids)
    }

    @Test
    fun earlierPagesStillShowWhenTheClientFoldTakesOver() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        // 首屏：当前段（user@10 / bot@11），快照回事件区间 [10, 11]。
        api.snapshotRows = listOf(api.row("user:10", "user", 10L), api.row("bot:11", "bot", 11L))
        api.snapshotEventFrom = 10L
        api.snapshotEventTo = 11L
        api.snapshotCursor = JSONObject().put("beforeSeq", 10)
        // 事件日志可用：宿主按区间回这两条事件，客户端就能自己折叠。
        api.eventsResponse = listOf(
            SessionEvent(10L, "user/message", 1000L, JSONObject().put("source", JSONObject().put("kind", "user")).put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "你好")))),
            SessionEvent(11L, "assistant/message", 1100L, JSONObject().put("turn", 1).put("message", JSONObject().put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "在的"))))),
        )

        val viewModel = viewModel(api, scheduler)
        scheduler.runCurrent()
        assertEquals(
            "当前段由客户端折叠给出",
            listOf("user:10", "bot:11"),
            viewModel.uiState.value.entries.map { it.id },
        )

        // 翻一页更早的（宿主行）：必须显示出来，不能被折叠那一段盖掉。
        api.earlierRows = listOf(api.row("bot:5", "bot", 5L))
        api.earlierCursor = null
        viewModel.loadEarlier()
        scheduler.runCurrent()

        // 更早页的**渲染 id**带命名空间前缀：行的 key 只带 seq 不带 part，跨 part
        // 同 seq 会撞 LazyColumn 的 key（Compose 直接抛「Key was already used」→
        // 列表挂掉 → 真机症状「加载历史后分割线下面的气泡消失」）。
        assertEquals(
            "更早的一页必须显示（折叠只管当前段），且渲染 id 与当前段隔开",
            listOf("earlier:bot:5", "user:10", "bot:11"),
            viewModel.uiState.value.entries.map { it.id },
        )
    }

    /**
 * 回归（2026-11-15 真机报「最近的对话没了、新消息跑到很早的消息下面，重启就好」）：
 * **增量/重放不得收窄折叠范围**。
 *
 * 宿主在 since 快路径（增量）里回的 `eventFrom` 是**增量区间**的起点，不是快照页的
 * 起点。拿它当折叠起点 → 范围一次次收窄 → 当前会话的历史从显示里消失。
 */
    @Test
    fun anIncrementalReplayDoesNotShrinkTheFoldedRange() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        // 首屏快照：当前段覆盖事件 [100, 110]，页里有三条消息。
        api.snapshotRows = listOf(
            api.row("user:101", "user", 101L),
            api.row("bot:105", "bot", 105L),
            api.row("user:110", "user", 110L),
        )
        api.snapshotEventFrom = 100L
        api.snapshotEventTo = 110L
        api.eventsResponse = listOf(
            userEvent(101L, 1010L, "第一条"),
            assistantEvent(105L, 1050L, "回复"),
            userEvent(110L, 1100L, "第三条"),
        )

        val viewModel = viewModel(api, scheduler)
        scheduler.runCurrent()
        assertEquals(
            "首屏：当前段三条消息都在",
            listOf("user:101", "bot:105", "user:110"),
            viewModel.uiState.value.entries.map { it.id },
        )

        // 增量重放：宿主回的是**增量区间**的 eventFrom（110），不是快照页的起点（100）。
        api.snapshotEventFrom = 110L
        api.snapshotEventTo = 111L
        api.replayRows = listOf(api.row("user:111", "user", 111L))
        api.emitTimeline(api.row("user:111", "user", 111L), from = 111L)
        scheduler.runCurrent()

        assertEquals(
            "增量不得把折叠范围收窄（否则当前会话的历史消失）",
            listOf("user:101", "bot:105", "user:110", "user:111"),
            viewModel.uiState.value.entries.map { it.id },
        )
    }

    /**
     * ② 跨 part earlier：part 之间 seq 空间独立，更早的 part 页必须**整页前插**
     * （不能按 seq 归位——老 part 的 seq 可能比当前 part 大）。
     * page1（当前 part）seq 10/11 → 翻页回更早 part（divider:part:0 + seq 1/2）。
     */
    @Test
    fun earlierAcrossPartsPrependsTheWholePageByInsertionOrder() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        // 当前 part：seq 10/11，游标指向更早的 part 页。
        api.snapshotRows = listOf(api.row("user:10", "user", 10L), api.row("bot:11", "bot", 11L))
        api.snapshotCursor = JSONObject().put("part", 1).put("beforeSeq", 10)
        api.earlierPages.addLast(
            listOf(
                api.row("divider:part:0", "divider", null),
                api.row("user:1", "user", 1L),
                api.row("bot:2", "bot", 2L),
            ) to null,
        )

        val viewModel = viewModel(api, scheduler)
        assertEquals(listOf("user:10", "bot:11"), viewModel.uiState.value.entries.map { it.id })

        viewModel.loadEarlier()
        scheduler.runCurrent()

        // 显示序：老 part 整页在前（divider → 老行），不被 seq 排序打乱。
        assertEquals(
            // 跨 part：更早页来自上一个 part，seq 空间独立 → 渲染 id 必须与当前段隔开，
            // 否则 LazyColumn 的 key 冲突会让列表整个挂掉（真机：气泡消失）。
            listOf("earlier:divider:part:0", "earlier:user:1", "earlier:bot:2", "user:10", "bot:11"),
            viewModel.uiState.value.entries.map { it.id },
        )
        assertFalse("游标收口后 hasEarlier 应关掉", viewModel.uiState.value.hasEarlier)
    }

    /** 同一 part 内多次翻页：仍按宿主给的显示序正序（插入序 == seq 升序）。 */
    @Test
    fun multipleEarlierPagesWithinOnePartStayAscending() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        api.snapshotRows = listOf(api.row("user:10", "user", 10L), api.row("bot:11", "bot", 11L))
        api.snapshotCursor = JSONObject().put("part", 0).put("beforeSeq", 10)
        api.earlierPages.addLast(
            listOf(api.row("user:5", "user", 5L), api.row("bot:6", "bot", 6L)) to
                JSONObject().put("part", 0).put("beforeSeq", 5),
        )
        api.earlierPages.addLast(
            listOf(api.row("user:1", "user", 1L), api.row("bot:2", "bot", 2L)) to null,
        )

        val viewModel = viewModel(api, scheduler)

        viewModel.loadEarlier()
        scheduler.runCurrent()
        assertEquals(
            listOf("earlier:user:5", "earlier:bot:6", "user:10", "bot:11"),
            viewModel.uiState.value.entries.map { it.id },
        )

        viewModel.loadEarlier()
        scheduler.runCurrent()
        assertEquals(
            "两页更早的行都带命名空间前缀，且按更早→更晚排",
            listOf("earlier:user:1", "earlier:bot:2", "earlier:user:5", "earlier:bot:6", "user:10", "bot:11"),
            viewModel.uiState.value.entries.map { it.id },
        )
        assertFalse(viewModel.uiState.value.hasEarlier)
    }

    /**
     * ① 群排队项撤回：`sourceKind == "user"` 的行走 `room-queue {roomId, id, action:remove}`，
     * 提交即乐观摘行；成员项（sourceKind != user）不发请求。
     */
    @Test
    fun groupPendingRecallGoesThroughRoomQueueRemove() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        api.snapshotRows = listOf(
            api.row("inbox:roomq:room-1:3", "inbox", null, sourceKind = "user", name = "我"),
        )
        val viewModel = viewModel(api, scheduler, isGroup = true)
        scheduler.runCurrent()

        val pending = viewModel.uiState.value.pendingQueue.single()
        assertEquals(ChatEntryKind.PENDING, pending.kind)
        assertEquals("user", pending.pendingSourceKind)

        viewModel.recallPending(pending)
        scheduler.runCurrent()

        val (endpoint, payload) = api.botValueCalls.single()
        assertEquals("room-queue", endpoint)
        assertEquals("room-1", payload.optString("roomId"))
        assertEquals("roomq:room-1:3", payload.optString("id"))
        assertEquals("remove", payload.optString("action"))
        assertTrue("撤回后乐观摘行", viewModel.uiState.value.pendingQueue.isEmpty())
        assertTrue("群聊不提供改为插话", api.rpcCalls.none { it.first == "session/updateQueue" })
    }

    /** 群成员/系统排队项：不给操作，调 recall 也不发请求。 */
    @Test
    fun groupPendingRecallIsNoOpForNonUserItems() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        api.snapshotRows = listOf(
            api.row("inbox:roomq:room-1:4", "inbox", null, sourceKind = "member", name = "研究员", botId = "bot-9"),
        )
        val viewModel = viewModel(api, scheduler, isGroup = true)
        scheduler.runCurrent()

        viewModel.recallPending(viewModel.uiState.value.pendingQueue.single())
        scheduler.runCurrent()

        assertTrue("成员项不该发 room-queue 请求", api.botValueCalls.isEmpty())
        assertEquals("成员项仍留在队列条里", 1, viewModel.uiState.value.pendingQueue.size)
    }

    /**
     * #2 私聊「发送中」的 echo 气泡留在时间线里（官方也是乐观气泡），**一直撑到
     * 服务端回显原地接管它**；界面不再画队列条（2026-11-15 用户定），排队期间
     * 用户看得见自己那条消息，靠的就是这条气泡。
     */
    @Test
    fun echoStaysInTimelineWhileQueueRowsMoveToTheDock() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        api.snapshotRows = listOf(api.row("bot:1", "bot", 1L))
        val viewModel = viewModel(api, scheduler)
        scheduler.runCurrent()

        viewModel.send("刚发的")
        scheduler.runCurrent()
        val requestId = lastRequestId(api)
        // 发送中：echo 在时间线尾部。
        assertEquals(
            listOf("bot:1", "p:$requestId"),
            viewModel.uiState.value.entries.map { it.id },
        )
        assertTrue(viewModel.uiState.value.pendingQueue.isEmpty())

        // 进队列后：echo **仍在**时间线（撑到回显），队列数据里拿到 inbox 行。
        api.emitTimeline(api.row("inbox:q-1", "inbox", null, requestId = requestId, slot = "next-turn"))
        scheduler.runCurrent()
        assertEquals(
            "排队期间 echo 气泡不能消失（没有队列条可看）",
            listOf("bot:1", "p:$requestId"),
            viewModel.uiState.value.entries.map { it.id },
        )
        assertEquals(listOf("inbox:q-1"), viewModel.uiState.value.pendingQueue.map { it.id })
    }

    /**
     * #3 单聊 running 不看「群里的工作」：`work.bots[botId]` 带 `roomId` 时
     * 那是群回合挂上来的，私聊必须保持空闲（不带 roomId 才算自己会话在跑）。
     */
    @Test
    fun singleChatRunningIgnoresGroupWorkBoundToTheSameBot() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler

        // 带 roomId：群回合挂在同一个 botId 上 → 私聊不亮。
        val withRoom = FakeApi().apply {
            stateResponse = JSONObject().put(
                "work",
                JSONObject().put(
                    "bots",
                    JSONObject().put("bot-1", JSONObject().put("roomId", "room-1").put("tool", "bash")),
                ),
            )
        }
        val groupBusy = viewModel(withRoom, scheduler)
        scheduler.advanceUntilIdle()
        assertFalse("带 roomId 的群工作不该点亮私聊 running", groupBusy.uiState.value.running)
        assertFalse(groupBusy.uiState.value.stopVisible)

        // 不带 roomId：自己的会话在跑 → 亮。
        val own = FakeApi().apply {
            stateResponse = JSONObject().put(
                "work",
                JSONObject().put("bots", JSONObject().put("bot-1", JSONObject().put("tool", "bash"))),
            )
        }
        val ownBusy = viewModel(own, scheduler)
        scheduler.advanceUntilIdle()
        assertTrue("自己的会话在跑应该亮 running", ownBusy.uiState.value.running)
        assertTrue(ownBusy.uiState.value.stopVisible)
    }

    /** #6 忽略提问卡：`dismiss-question {sessionId}` + 本地乐观摘卡。 */
    @Test
    fun dismissQuestionSendsSessionScopedEndpointAndClearsTheCard() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        api.stateResponse = JSONObject().put(
            "questions",
            JSONObject().put(
                "s-1",
                JSONObject()
                    .put("id", "q-1")
                    .put("question", "要继续吗？")
                    .put("options", JSONArray().put(JSONObject().put("label", "是"))),
            ),
        )
        val viewModel = viewModel(api, scheduler)
        scheduler.advanceUntilIdle()
        val card = viewModel.uiState.value.pendingQuestions.single()

        viewModel.dismissChatQuestion(card)
        scheduler.advanceUntilIdle()

        // 后面 refreshBotStatus 还会打一次 `state`：只在调用记录里找那一次 dismiss-question。
        val (endpoint, payload) = api.botCalls.first { it.first == "dismiss-question" }
        assertEquals("dismiss-question", endpoint)
        assertEquals("按会话关整张卡：只带 sessionId", "s-1", payload.optString("sessionId"))
        assertTrue(
            "本地应乐观摘掉提问卡",
            viewModel.uiState.value.pendingQuestions.none { it.id == "q-1" },
        )
    }

    /** #7 单独停下群成员的工作：`stop-member-work {roomId, botId}` + 轻提示。 */
    @Test
    fun stopMemberWorkCallsTheRoomScopedEndpointAndShowsANotice() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        val viewModel = viewModel(api, scheduler, isGroup = true)
        scheduler.runCurrent()

        viewModel.stopMemberWork(RoomMemberUi(id = "bot-9", name = "研究员", inkId = null, avatarImage = null, avatarShape = null))
        // 只推进到请求完成：轻提示 2.5s 后自动收回，不能推进到 idle。
        scheduler.advanceTimeBy(1_000)

        val (endpoint, payload) = api.botValueCalls.single()
        assertEquals("stop-member-work", endpoint)
        assertEquals("room-1", payload.optString("roomId"))
        assertEquals("bot-9", payload.optString("botId"))
        assertEquals("已让 研究员 停下", viewModel.uiState.value.actionNotice)
    }

    /** 断档检测：帧 from 比本地 seq 大 → 主动带 since 重放快照，把丢掉的卡补回来。 */
    @Test
    fun gapInTimelineFramesTriggersSinceReplay() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        api.snapshotRows = listOf(api.row("user:10", "user", 10L), api.row("bot:11", "bot", 11L))
        val viewModel = viewModel(api, scheduler)
        assertEquals(listOf("user:10", "bot:11"), viewModel.uiState.value.entries.map { it.id })
        val before = api.timelineRequests.size

        // 模拟 feed 断线期间丢了一帧：本地只到快照 seq=40，新帧 from=45（41~44 丢了）。
        api.emitTimeline(api.row("bot:50", "bot", 50L), from = 45L)
        scheduler.runCurrent()

        val replay = api.timelineRequests.drop(before).firstOrNull()
        assertTrue("断档应触发一次带 since 的重放：$replay", replay?.since != null)
        assertEquals("since 应为断档前的本地 seq", 40L, replay?.since)
        assertTrue("当前帧仍要正常应用", viewModel.uiState.value.entries.any { it.id == "bot:50" })
    }

    /** `changes` 行：摘要按 (sessionId, seq) 只拉一次，拉到后行才带上改动卡。 */
    @Test
    fun changesRowFetchesItsSummaryOnceAndRendersTheCard() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        api.changesSummaryResponse = JSONObject()
            .put("total", 1).put("added", 3).put("deleted", 1)
            .put(
                "files",
                JSONArray().put(
                    JSONObject().put("path", "/ws/a.md").put("display", "a.md").put("added", 3).put("deleted", 1),
                ),
            )
        api.snapshotRows = listOf(
            TimelineRow(
                key = "changes:6", kind = "changes", seq = 6L, turn = 2, timeMs = null,
                payload = TimelineRowPayload.Changes(turn = 2, raw = JSONObject().put("turn", 2)),
            ),
        )
        val viewModel = viewModel(api, scheduler)
        scheduler.runCurrent()

        // 快照 admitted 后按 (sessionId, seq) 拉了正好一次（sessionId 来自快照）。
        assertEquals(listOf("s-1" to 6L), api.changesSummaryCalls)
        val entry = viewModel.uiState.value.entries.single()
        assertEquals(ChatEntryKind.CHANGES, entry.kind)
        assertEquals("changes:6", entry.id)
        assertEquals(1, entry.changes?.total)
        assertEquals(listOf("a.md"), entry.changes?.files?.map { it.display })

        // 同一 seq 再来一次（重放/重连）不会重复拉。
        api.emitTimeline(
            TimelineRow(
                key = "changes:6", kind = "changes", seq = 6L, turn = 2, timeMs = null,
                payload = TimelineRowPayload.Changes(turn = 2, raw = JSONObject().put("turn", 2)),
            ),
        )
        scheduler.runCurrent()
        assertEquals(1, api.changesSummaryCalls.size)
    }

    /** `changes` 行拉不到摘要时不出行，但**失败会重试**（以前永久记 null → 卡再也不出现）。 */
    @Test
    fun changesRowWithoutSummaryRetriesAndRendersOnceTheHostAnswers() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        api.changesSummaryResponse = null
        val row = TimelineRow(
            key = "changes:9", kind = "changes", seq = 9L, turn = 3, timeMs = null,
            payload = TimelineRowPayload.Changes(turn = 3, raw = JSONObject().put("turn", 3)),
        )
        api.snapshotRows = listOf(row)
        val viewModel = viewModel(api, scheduler)
        scheduler.runCurrent()

        assertEquals(listOf("s-1" to 9L), api.changesSummaryCalls)
        assertTrue("没有摘要就不出行", viewModel.uiState.value.entries.isEmpty())

        // 宿主恢复提供（或第一次只是暂时失败）：重放该行会重试，摘要到位后卡出现。
        // 旧实现把 null 缓存在 `changesSummaries` 里且「同一 seq 只拉一次」——这一轮
        // 的卡就永久消失，只有重开界面才好（用户实测「代码产物卡不持久化」）。
        api.changesSummaryResponse = JSONObject()
            .put("total", 1).put("added", 1).put("deleted", 0)
            .put(
                "files",
                JSONArray().put(JSONObject().put("path", "/ws/b.md").put("display", "b.md")),
            )
        api.emitTimeline(row)
        scheduler.runCurrent()

        assertEquals(2, api.changesSummaryCalls.size)
        val entry = viewModel.uiState.value.entries.single()
        assertEquals(ChatEntryKind.CHANGES, entry.kind)
        assertEquals(listOf("b.md"), entry.changes?.files?.map { it.display })
    }

    private fun lastRequestId(api: FakeApi): String =
        api.sendRequestIds.lastOrNull() ?: error("没有 send 记录")
}
