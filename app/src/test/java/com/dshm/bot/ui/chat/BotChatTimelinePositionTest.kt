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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 乐观用户气泡在时间线里的位置。
 *
 * bug：流式输出期间用户自己的气泡会错位 —— 本轮的工具/事件行被插到了它**上面**，
 * 重新进对话（新 ViewModel）或重新加载历史（拿到真 seq）后又恢复正常。
 *
 * 根因：占位 seq 写成 `maxSeenSeq + ++optimisticSeq`，计数每次发送都累加，
 * 会虚高到「本轮真实 seq」之上；`mergeTimeline` 按「插到第一条 seq 更大的条目之前」
 * 定位，于是本轮事件被插到了用户气泡前面。
 *
 * 这个用例不自动推进虚拟时间（`runCurrent` 只跑当前时刻的任务）：`send()` 置的
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

    @Test
    fun thisTurnEventLandsAfterTheNewestUserBubble() {
        val scheduler: TestCoroutineScheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        val viewModel = BotChatViewModel(
            botId = "bot-1",
            botName = "小助手",
            isGroup = false,
            endpoint = null,
            appContext = null,
            dependencies = BotChatDependencies(remote = api, transport = null, speech = null, mux = null),
        )
        scheduler.runCurrent()

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

    @Test
    fun placeholderSeqDoesNotAccumulateAcrossSends() {
        val scheduler: TestCoroutineScheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        val viewModel = BotChatViewModel(
            botId = "bot-1",
            botName = "小助手",
            isGroup = false,
            endpoint = null,
            appContext = null,
            dependencies = BotChatDependencies(remote = api, transport = null, speech = null, mux = null),
        )
        scheduler.runCurrent()

        // 历史最大 seq = 2；没有任何真 seq 推进时连续发三条。
        viewModel.send("a")
        scheduler.runCurrent()
        viewModel.send("b")
        scheduler.runCurrent()
        viewModel.send("c")
        scheduler.runCurrent()

        val bubbles = viewModel.uiState.value.entries.filter { it.kind == ChatEntryKind.USER }
        val seqs = bubbles.map { it.seq }
        // 占位 seq 只允许「比已加载历史新一档」，不能随发送次数累加。
        assertTrue("占位 seq 随发送次数累加了：$seqs（历史最大 seq = 2）", seqs.all { (it ?: -1L) <= 3L })
    }

    @Test
    fun serverEchoAdoptsTheRealSeq() {
        val scheduler: TestCoroutineScheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        val viewModel = BotChatViewModel(
            botId = "bot-1",
            botName = "小助手",
            isGroup = false,
            endpoint = null,
            appContext = null,
            dependencies = BotChatDependencies(remote = api, transport = null, speech = null, mux = null),
        )
        scheduler.runCurrent()

        viewModel.send("问题")
        scheduler.runCurrent()
        val optimistic = viewModel.uiState.value.entries.last { it.kind == ChatEntryKind.USER }
        assertTrue("占位 seq 应为历史最大值 + 1", optimistic.seq == 3L)

        // 服务端回显（带 rpcId + 真实 seq）：本地气泡认领真实 seq。
        api.emitUserEcho(seq = 9)
        scheduler.runCurrent()
        val adopted = viewModel.uiState.value.entries.last { it.kind == ChatEntryKind.USER }
        assertTrue("回显没有把真实 seq 认领回本地气泡：${adopted.seq}", adopted.seq == 9L)

        // 认领之后，本轮事件（seq 10）理应落在气泡后面。
        val event = ChatEntry(id = "tool:1", kind = ChatEntryKind.EVENT, text = "", seq = 10)
        val merged = mergeTimeline(viewModel.uiState.value.entries, listOf(event))
        val bubbleAt = merged.indexOfFirst { it.id == adopted.id }
        val eventAt = merged.indexOfFirst { it.id == "tool:1" }
        assertTrue("本轮事件没有落在气泡后面：bubble@$bubbleAt event@$eventAt", eventAt > bubbleAt)
    }

    private class FakeApi : DshRemoteApi {
        private val feedEvents = MutableSharedFlow<BotFeedEvent>(extraBufferCapacity = 16)
        private var nextId = 0
        private var lastSendRequestId: String? = null

        override suspend fun bot(endpoint: String, payload: JSONObject): JSONObject = when (endpoint) {
            "chat", "room-chat" -> JSONObject().put(
                "items",
                JSONArray().apply {
                    put(item("user", "历史提问", 1))
                    put(item("bot", "历史回复", 2))
                },
            )

            "send" -> {
                lastSendRequestId = payload.optString("requestId").takeIf { it.isNotEmpty() }
                JSONObject()
            }

            else -> JSONObject()
        }

        override suspend fun botValue(endpoint: String, payload: JSONObject): Any? = JSONObject()

        override suspend fun rpc(method: String, args: JSONObject, timeoutMs: Int): JSONObject = JSONObject()

        override fun feed(): Flow<BotFeedEvent> = feedEvents

        /** 引擎广播一条助手消息（带真 seq），历史边界随之推进。 */
        fun emitMessage(seq: Long) {
            nextId += 1
            val item = item("bot", "回复 $nextId", seq)
            feedEvents.tryEmit(
                BotFeedEvent(
                    event = "message",
                    data = JSONObject().put("botId", "bot-1").put("item", item).toString(),
                ),
            )
        }

        /** 引擎回显自己刚发的那条（带 `rpcId` 与真实 seq）。 */
        fun emitUserEcho(seq: Long) {
            val item = item("user", "问题", seq)
            lastSendRequestId?.let { item.put("rpcId", it) }
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
