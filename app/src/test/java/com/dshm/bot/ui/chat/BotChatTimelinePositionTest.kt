package com.dshm.bot.ui.chat

import com.dshm.bot.MainDispatcherRule
import com.dshm.bot.data.remote.BotFeedEvent
import com.dshm.bot.data.remote.DshMuxLog
import com.dshm.bot.data.remote.DshRemoteApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * 乐观用户气泡在时间线里的位置。
 *
 * 已修的三层：
 * 1. 占位 seq = `max(maxSeenSeq, maxLocalSeq) + 1`，不再随发送次数无界累加；
 * 2. 连发（HTTP 一回、回显未到就发下一条）各自拿到**不同**的占位 seq，不互相顶替；
 * 3. 回显认领真 seq 时（向上向下都认）按 seq 稳定重排 —— 排队/steer 混发时
 *    「发送序 ≠ 日志序」的裂缝在这里收口；feed 条目落地也按 seq 找位（见 S1）。
 *
 * 这个文件不自动推进虚拟时间（`runCurrent` 只跑当前时刻的任务）：`send()` 置的
 * `optimisticRun` 在本测试里没有主机回执可清，轮询循环会一直 `delay(1000)` 下去，
 * 推进到 idle 会卡死。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BotChatTimelinePositionTest {

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

    private fun viewModel(api: FakeApi, scheduler: TestCoroutineScheduler): BotChatViewModel {
        val viewModel = BotChatViewModel(
            botId = "bot-1",
            botName = "小助手",
            isGroup = false,
            endpoint = null,
            appContext = null,
            dependencies = BotChatDependencies(remote = api, transport = null, speech = null, mux = null),
        )
        scheduler.runCurrent()
        return viewModel
    }

    @Test
    fun thisTurnEventLandsAfterTheNewestUserBubble() {
        val scheduler: TestCoroutineScheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        val viewModel = viewModel(api, scheduler)

        // 三轮发送，每轮结束后引擎广播一条带真 seq 的助手消息（历史边界随之前进）。
        viewModel.send("第一轮")
        scheduler.runCurrent()
        api.emitMessage(seq = 5)
        scheduler.runCurrent()
        viewModel.send("第二轮")
        scheduler.runCurrent()
        api.emitMessage(seq = 8)
        scheduler.runCurrent()
        viewModel.send("第三轮")
        scheduler.runCurrent()

        val entries = viewModel.uiState.value.entries
        val newestBubble = entries.last { it.kind == ChatEntryKind.USER }

        // 第三轮的第一个工具事件：真实 seq 紧跟本轮用户消息之后（8 之后依次是 9、10）。
        val event = ChatEntry(id = "tool:1", kind = ChatEntryKind.EVENT, text = "", seq = 10)
        val merged = mergeTimeline(entries, listOf(event))

        val bubbleAt = merged.indexOfFirst { it.id == newestBubble.id }
        val eventAt = merged.indexOfFirst { it.id == "tool:1" }
        assertTrue("找不到最新用户气泡", bubbleAt >= 0)
        assertTrue("找不到本轮事件", eventAt >= 0)
        assertTrue(
            "本轮事件被插到了最新用户气泡上面（用户气泡错位）：" +
                "bubble@$bubbleAt(seq=${newestBubble.seq}) event@$eventAt(seq=10)",
            eventAt > bubbleAt,
        )
    }

    /**
     * 连发时占位 seq 不再共享同一个值（防碰撞），回显认领后计数归位 ——
     * 下一次发送的占位紧跟真实 seq，不会像旧 `++optimisticSeq` 那样漂移。
     */
    @Test
    fun rapidSendsGetDistinctPlaceholdersThatTrackTheRealSeq() {
        val scheduler: TestCoroutineScheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        val viewModel = viewModel(api, scheduler)

        // 历史最大 seq = 2；回显未到就连续发三条（HTTP 之间虚拟时间已放行 sending）。
        viewModel.send("a")
        scheduler.runCurrent()
        viewModel.send("b")
        scheduler.runCurrent()
        viewModel.send("c")
        scheduler.runCurrent()

        val bubbles = viewModel.uiState.value.entries
            .filter { it.kind == ChatEntryKind.USER && it.text in setOf("a", "b", "c") }
        val seqs = bubbles.map { it.seq }
        // 历史最大 2 → 三条占位 3、4、5：各占一档，谁也不撞谁（防碰撞）。
        assertEquals(listOf(3L, 4L, 5L), seqs)

        // 回显认领真 seq 后，占位计数跟着抬到真 seq；再发一条就贴着真 seq 走。
        api.emitUserEcho(seq = 9, requestId = api.sentRequests[0])
        scheduler.runCurrent()
        viewModel.send("d")
        scheduler.runCurrent()
        val after = viewModel.uiState.value.entries.last { it.kind == ChatEntryKind.USER }
        assertEquals("占位没有跟上真实 seq（计数漂移了）：${after.seq}", 10L, after.seq)
    }

    @Test
    fun serverEchoAdoptsTheRealSeq() {
        val scheduler: TestCoroutineScheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        val viewModel = viewModel(api, scheduler)

        viewModel.send("问题")
        scheduler.runCurrent()
        val optimistic = viewModel.uiState.value.entries.last { it.kind == ChatEntryKind.USER }
        assertEquals("占位 seq 应为历史最大值 + 1", 3L, optimistic.seq)

        // 服务端回显（带 rpcId + 真实 seq）：本地气泡认领真实 seq。
        api.emitUserEcho(seq = 9)
        scheduler.runCurrent()
        val adopted = viewModel.uiState.value.entries.last { it.kind == ChatEntryKind.USER }
        assertEquals("回显没有把真实 seq 认领回本地气泡：${adopted.seq}", 9L, adopted.seq)

        // 认领之后，本轮事件（seq 10）理应落在气泡后面。
        val event = ChatEntry(id = "tool:1", kind = ChatEntryKind.EVENT, text = "", seq = 10)
        val merged = mergeTimeline(viewModel.uiState.value.entries, listOf(event))
        val bubbleAt = merged.indexOfFirst { it.id == adopted.id }
        val eventAt = merged.indexOfFirst { it.id == "tool:1" }
        assertTrue("本轮事件没有落在气泡后面：bubble@$bubbleAt event@$eventAt", eventAt > bubbleAt)
    }

    /**
     * S1：轮还在跑时发消息 → 消息进 nextTurn 排队，日志 seq 落在当前轮收尾文字
     * **之后**。收尾文字（seq 3）先到、落在本地图位气泡（占位 3）之后暂时容忍；
     * 回显（真 seq 9）认领并重排后，收尾文字必须回到气泡**前面**。
     */
    @Test
    fun queuedMessageClosingTextSettlesBeforeTheBubble() {
        val scheduler: TestCoroutineScheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        val viewModel = viewModel(api, scheduler)

        viewModel.send("排队消息")
        scheduler.runCurrent()
        // 上一轮的收尾文字：落日志（seq 3）早于排队消息，但 feed 先到。
        api.emitMessage(seq = 3)
        scheduler.runCurrent()
        // 排队消息在下一轮开始时才落日志：真 seq 9，回显把它认领回来。
        api.emitUserEcho(seq = 9)
        scheduler.runCurrent()

        val entries = viewModel.uiState.value.entries
        val closeAt = entries.indexOfFirst { it.kind == ChatEntryKind.BOT && it.text == "回复 3" }
        val bubbleAt = entries.indexOfFirst { it.kind == ChatEntryKind.USER && it.text == "排队消息" }
        assertTrue("找不到收尾文字", closeAt >= 0)
        assertTrue("找不到排队消息气泡", bubbleAt >= 0)
        assertTrue(
            "收尾文字（seq=3）应排在排队消息（真 seq=9）前面：" +
                "close@$closeAt bubble@$bubbleAt",
            closeAt < bubbleAt,
        )
    }

    /**
     * S2：连发两条 + 期间到达的收尾文字，各归其位。
     * 日志序：A=5、B=6，收尾文字=3。发送后占位各占一档（3、4），
     * 回显认领后整表按 seq 归位：收尾文字 < A < B。
     */
    @Test
    fun rapidDoubleSendWithClosingTextSettlesBySeq() {
        val scheduler: TestCoroutineScheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        val viewModel = viewModel(api, scheduler)

        viewModel.send("第一条")
        scheduler.runCurrent()
        viewModel.send("第二条")
        scheduler.runCurrent()
        val placeholders = viewModel.uiState.value.entries
            .filter { it.kind == ChatEntryKind.USER }
            .mapNotNull { it.seq }
        assertTrue(
            "连发的两条拿到同一个占位 seq（碰撞）：$placeholders",
            placeholders.distinct().size == placeholders.size,
        )

        api.emitMessage(seq = 3) // 上一轮收尾文字
        scheduler.runCurrent()
        api.emitUserEcho(seq = 5, requestId = api.sentRequests[0])
        scheduler.runCurrent()
        api.emitUserEcho(seq = 6, requestId = api.sentRequests[1])
        scheduler.runCurrent()

        val entries = viewModel.uiState.value.entries
        val closeAt = entries.indexOfFirst { it.kind == ChatEntryKind.BOT && it.text == "回复 3" }
        val firstAt = entries.indexOfFirst { it.text == "第一条" }
        val secondAt = entries.indexOfFirst { it.text == "第二条" }
        assertTrue(
            "没有按日志序归位：close@$closeAt first@$firstAt second@$secondAt",
            closeAt < firstAt && firstAt < secondAt,
        )
    }

    /**
     * S3：steer 与排队消息混发，发送序 [排队, 插话]、日志序 [插话(50), 排队(60)]。
     * 回显按日志序到达、逐条认领并重排后，插话必须排在排队消息**前面**。
     */
    @Test
    fun steerAndQueuedMessagesSettleInLogOrder() {
        val scheduler: TestCoroutineScheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        val viewModel = viewModel(api, scheduler)

        viewModel.send("排队消息")
        scheduler.runCurrent()
        viewModel.send("插话", steer = true)
        scheduler.runCurrent()

        // 日志里插话先落（当前轮的下一步就消费它），排队消息等下一轮。
        api.emitUserEcho(seq = 50, requestId = api.sentRequests[1])
        scheduler.runCurrent()
        api.emitUserEcho(seq = 60, requestId = api.sentRequests[0])
        scheduler.runCurrent()

        val entries = viewModel.uiState.value.entries
        val steerAt = entries.indexOfFirst { it.text == "插话" }
        val queuedAt = entries.indexOfFirst { it.text == "排队消息" }
        assertTrue("找不到插话气泡", steerAt >= 0)
        assertTrue("找不到排队气泡", queuedAt >= 0)
        assertTrue(
            "插话（真 seq=50）应排在排队消息（真 seq=60）前面：steer@$steerAt queued@$queuedAt",
            steerAt < queuedAt,
        )
    }

    private class FakeApi : DshRemoteApi {
        private val feedEvents = MutableSharedFlow<BotFeedEvent>(extraBufferCapacity = 16)

        /** 每次 `send` 带的 requestId，按发送顺序。 */
        val sentRequests = mutableListOf<String>()

        override suspend fun bot(endpoint: String, payload: JSONObject): JSONObject = when (endpoint) {
            "chat", "room-chat" -> JSONObject().put(
                "items",
                JSONArray().apply {
                    put(item("user", "历史提问", 1))
                    put(item("bot", "历史回复", 2))
                },
            )

            "send" -> {
                payload.optString("requestId").takeIf { it.isNotEmpty() }?.let(sentRequests::add)
                JSONObject()
            }

            else -> JSONObject()
        }

        override suspend fun botValue(endpoint: String, payload: JSONObject): Any? = JSONObject()

        override suspend fun rpc(method: String, args: JSONObject, timeoutMs: Int): JSONObject = JSONObject()

        override fun feed(): Flow<BotFeedEvent> = feedEvents

        /** 引擎广播一条助手消息（带真 seq），历史边界随之推进。 */
        fun emitMessage(seq: Long) {
            val item = item("bot", "回复 ${seq}", seq)
            feedEvents.tryEmit(
                BotFeedEvent(
                    event = "message",
                    data = JSONObject().put("botId", "bot-1").put("item", item).toString(),
                ),
            )
        }

        /** 引擎回显自己刚发的那条（带 `rpcId` 与真实 seq）。 */
        fun emitUserEcho(seq: Long, requestId: String? = null) {
            val item = item("user", "问题", seq)
            (requestId ?: sentRequests.lastOrNull())?.let { item.put("rpcId", it) }
            feedEvents.tryEmit(
                BotFeedEvent(
                    event = "message",
                    data = JSONObject().put("botId", "bot-1").put("item", item).toString(),
                ),
            )
        }

        private fun item(kind: String, text: String, seq: Long) = JSONObject()
            .put("kind", kind)
            .put("text", text)
            .put("seq", seq)
    }
}
