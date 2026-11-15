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
    }

    @After
    fun tearDown() {
        DshMuxLog.sink = defaultSink
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

        /** 发一帧原始 durable 事件（`session` feed）。 */
        fun emitSession(seq: Long, type: String, sessionId: String = "s-1") {
            feedEvents.tryEmit(
                BotFeedEvent(
                    event = "session",
                    data = JSONObject()
                        .put("sessionId", sessionId)
                        .put("seq", seq)
                        .put("type", type)
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

        // 排队：服务端 inbox 行（requestId 命中）替换 p: 行。
        api.emitTimeline(api.row("inbox:i-1", "inbox", null, requestId = lastRequestId(api), slot = "next-turn"))
        scheduler.runCurrent()
        // 队列行不进时间线（官方 web 的时间线也不出现排队行），只进输入框上方的队列条。
        assertTrue("排队行不该出现在时间线里：${viewModel.uiState.value.entries.map { it.id }}", viewModel.uiState.value.entries.isEmpty())
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
            "更早的一页应插在前面",
            listOf("bot:5", "user:10", "bot:11"),
            viewModel.uiState.value.entries.map { it.id },
        )
        assertFalse("最后一页之后 hasEarlier 应收掉", viewModel.uiState.value.hasEarlier)
    }

    /**
     * 回归（2026-11-14 真机报「看不到更早的历史，冷启动才恢复」）：
     * 增量重放（带 since，宿主行走缓冲快路径）的响应**不描述分页边界**（cursor=null），
     * 不能被当成「没有更早的了」把游标清掉 —— 否则运行中翻过一次旧历史就再也翻不动。
     */
    @Test
    fun replayDoesNotClearEarlierCursor() {
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        // 首屏（最新）：user@10 + bot@11，游标 beforeSeq=10 → 还有更早的。
        api.snapshotRows = listOf(api.row("user:10", "user", 10L), api.row("bot:11", "bot", 11L))
        api.snapshotCursor = JSONObject().put("beforeSeq", 10)

        val viewModel = viewModel(api, scheduler)
        assertTrue("首屏就该说有更早的历史", viewModel.uiState.value.hasEarlier)

        // feed `revision` → scheduleReload → 带 since 的增量重放；宿主回 cursor=null。
        api.replayRows = listOf(api.row("bot:12", "bot", 12L))
        api.replayCursor = null
        api.feedEvents.tryEmit(BotFeedEvent(event = "revision", data = "{}"))
        scheduler.runCurrent()
        assertTrue("重放请求应带 since", api.timelineRequests.last().since != null)

        assertTrue("重放响应不带分页边界，不该清掉 hasEarlier", viewModel.uiState.value.hasEarlier)

        // 游标还在：翻页仍然可用，且请求带的就是首屏那个游标。
        api.earlierRows = listOf(api.row("bot:5", "bot", 5L))
        api.earlierCursor = null
        viewModel.loadEarlier()
        scheduler.runCurrent()
        assertEquals("翻页请求应带首屏游标", 10L, api.timelineRequests.last().cursor?.optLong("beforeSeq"))
        assertEquals(
            "更早的一页应插在前面",
            listOf("bot:5", "user:10", "bot:11", "bot:12"),
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
            listOf("divider:part:0", "user:1", "bot:2", "user:10", "bot:11"),
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
            listOf("user:5", "bot:6", "user:10", "bot:11"),
            viewModel.uiState.value.entries.map { it.id },
        )

        viewModel.loadEarlier()
        scheduler.runCurrent()
        assertEquals(
            listOf("user:1", "bot:2", "user:5", "bot:6", "user:10", "bot:11"),
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
     * #2 私聊「发送中」的 echo 气泡仍在时间线里（官方也是乐观气泡），
     * 只有已经进队列的 inbox 行搬到队列条。
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

        // 进队列后：时间线只剩 bot:1，队列条拿到 inbox 行。
        api.emitTimeline(api.row("inbox:q-1", "inbox", null, requestId = requestId, slot = "next-turn"))
        scheduler.runCurrent()
        assertEquals(listOf("bot:1"), viewModel.uiState.value.entries.map { it.id })
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
