package com.dshm.bot.ui.chat

import com.dshm.bot.MainDispatcherRule
import com.dshm.bot.data.remote.BotFeedEvent
import com.dshm.bot.data.remote.DshMux
import com.dshm.bot.data.remote.DshMuxFrame
import com.dshm.bot.data.remote.DshMuxLog
import com.dshm.bot.data.remote.DshRemoteApi
import com.dshm.bot.data.remote.FakeMuxLink
import com.dshm.bot.domain.model.ChatApproval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
 * `BotChatViewModel` 的接线测试：
 * 主机事件 → 卡片缓存、feed → 消息列表 / 流式侧通道。
 *
 * 以前这些路径只能靠真机；现在依赖通过 [BotChatDependencies] 注入，
 * mux 用 `FakeMuxLink` 驱动的真状态机，HTTP 用假 `DshRemoteApi`。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BotChatViewModelWiringTest {

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

    private fun viewModel(
        remote: DshRemoteApi,
        mux: DshMux? = null,
    ) = BotChatViewModel(
        botId = "bot-1",
        botName = "小助手",
        isGroup = false,
        endpoint = null,
        appContext = null,
        dependencies = BotChatDependencies(remote = remote, transport = null, speech = null, mux = mux),
    )

    private fun readyFrame(clientId: String = "client-1") = JSONObject()
        .put("type", "ready")
        .put("clientId", clientId)
        .put("host", JSONObject().put("home", "/"))

    private fun questionFrame(eventId: String, agentId: String) = JSONObject()
        .put("type", "waterfall")
        .put("event", "user-questions/request")
        .put("eventId", eventId)
        .put("agentId", agentId)
        .put(
            "request",
            JSONObject().put(
                "questions",
                JSONArray().put(
                    JSONObject()
                        .put("id", "q-id-1")
                        .put("question", "要继续吗？")
                        .put(
                            "options",
                            JSONArray()
                                .put(JSONObject().put("label", "是"))
                                .put(JSONObject().put("label", "否")),
                        ),
                ),
            ),
        )

    @Test
    fun hostQuestionBecomesAnswerableCard() = runTest(mainDispatcherRule.testDispatcher) {
        val remote = FakeRemoteApi()
        val links = mutableListOf<FakeMuxLink>()
        val scope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob())
        val mux = DshMux(
            scope = scope,
            connect = { FakeMuxLink().also { links += it } },
            readyTimeoutMs = 1_000,
            retryCapsMs = listOf(10),
        )
        val viewModel = viewModel(remote, mux)
        try {
            runCurrent()
            val link = links.single()
            // ready 是 `$events` 流里的一个 item（和 DshMuxTest 的投递方式一致）。
            link.deliverHostEvent(readyFrame())
            runCurrent()
            link.deliverHostEvent(questionFrame("q-1", "bot-1"))
            runCurrent()

            val approvals = viewModel.uiState.value.approvals
            assertEquals(1, approvals.size)
            assertEquals(ChatApproval.Kind.QUESTION, approvals.first().kind)
            assertEquals(ChatApproval.Action.ANSWER, approvals.first().action)
            assertEquals("q-1", approvals.first().eventId)
        } finally {
            mux.close()
            scope.cancel()
        }
    }

    @Test
    fun feedMessageAppendsBotEntry() = runTest(mainDispatcherRule.testDispatcher) {
        val remote = FakeRemoteApi()
        val viewModel = viewModel(remote)
        runCurrent()

        val item = JSONObject()
            .put("kind", "bot")
            .put("id", "m-1")
            .put("text", "你好，我是小助手")
        remote.feedEvents.emit(
            BotFeedEvent(
                event = "message",
                data = JSONObject().put("botId", "bot-1").put("item", item).toString(),
            ),
        )
        runCurrent()

        val entry = viewModel.uiState.value.entries.firstOrNull { it.id == "m-1" }
        assertTrue("feed 消息没有进入列表", entry != null)
        assertEquals(ChatEntryKind.BOT, entry?.kind)
        assertEquals("你好，我是小助手", entry?.text)
    }

    @Test
    fun feedStreamChunkLandsOnSideChannel() = runTest(mainDispatcherRule.testDispatcher) {
        val remote = FakeRemoteApi()
        val viewModel = viewModel(remote)
        runCurrent()

        remote.feedEvents.emit(
            BotFeedEvent(
                event = "stream",
                data = JSONObject()
                    .put("botId", "bot-1")
                    .put("phase", "delta")
                    .put("attemptId", "attempt-1")
                    .put("text", "你")
                    .toString(),
            ),
        )
        remote.feedEvents.emit(
            BotFeedEvent(
                event = "stream",
                data = JSONObject()
                    .put("botId", "bot-1")
                    .put("phase", "delta")
                    .put("attemptId", "attempt-1")
                    .put("text", "好")
                    .toString(),
            ),
        )
        advanceUntilIdle()

        assertEquals("你好", viewModel.streamingText.value)
    }

    /** 按 WS 读线程的投递形状喂一个 session/follow 帧。 */
    private fun deliverFollow(link: FakeMuxLink, streamId: String, event: JSONObject) {
        link.deliver(
            JSONObject()
                .put("type", DshMuxFrame.TYPE_ITEM)
                .put("streamId", streamId)
                .put("value", event),
        )
    }

    /** follow 流里的 `llm/retry` 事件：官方画一条普通事件行，对齐成「模型请求失败，正在重试(n/max)：原因」。 */
    private fun llmRetryEvent(seq: Long, retry: Int, max: Int, code: String, message: String): JSONObject =
        JSONObject()
            .put("type", "event")
            .put(
                "event",
                JSONObject()
                    .put("type", "llm/retry")
                    .put("seq", seq)
                    .put("time", 1_700_000_000_000L + seq)
                    .put(
                        "data",
                        JSONObject()
                            .put("retry", retry)
                            .put("maxRetries", max)
                            .put("failure", JSONObject().put("code", code).put("message", message)),
                    ),
            )

    @Test
    fun llmRetryFrameBecomesARetryRow() = runTest(mainDispatcherRule.testDispatcher) {
        // 官方（dsh-Go 同款）：llm/retry 是对话里的一条普通事件行，不是静默也不是错误弹层。
        // 回归点：这条行以前不存在（整条静默），用户看到的是「转了半天什么都没有」。
        val remote = FakeRemoteApi(sessionId = "sess-1")
        val links = mutableListOf<FakeMuxLink>()
        val scope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob())
        val mux = DshMux(
            scope = scope,
            connect = { FakeMuxLink().also { links += it } },
            readyTimeoutMs = 1_000,
            retryCapsMs = listOf(10),
        )
        val viewModel = viewModel(remote, mux)
        try {
            runCurrent()
            val link = links.single()
            link.deliverHostEvent(readyFrame())
            runCurrent()
            val followStreamId = link.opened.entries
                .firstOrNull { it.value == "session/follow" }?.key
            assertTrue("session/follow 流没开出来", followStreamId != null)

            deliverFollow(link, followStreamId!!, llmRetryEvent(7, retry = 2, max = 4, code = "rate_limit", message = "配额限流"))
            runCurrent()

            val row = viewModel.uiState.value.entries.firstOrNull { it.id == "retry:7" }
            assertTrue("llm/retry 没有画成事件行", row != null)
            assertEquals("模型请求失败，正在重试（2/4）：配额限流", row?.text)
            assertEquals("retry", row?.eventKind)
            assertEquals("rate_limit", row?.eventCode)
        } finally {
            mux.close()
            scope.cancel()
        }
    }

    /** turn/end 失败事件（reason.kind=error）：handleSessionEvent 会画成 `turnfail:<seq>` 行。 */
    private fun turnEndEvent(seq: Long, timeMs: Long, message: String): JSONObject = JSONObject()
        .put("type", "event")
        .put(
            "event",
            JSONObject()
                .put("type", "turn/end")
                .put("seq", seq)
                .put("time", timeMs)
                .put(
                    "data",
                    JSONObject().put(
                        "reason",
                        JSONObject()
                            .put("kind", "error")
                            .put("error", JSONObject().put("code", "boom").put("message", message)),
                    ),
                ),
        )

    @Test
    fun followFramesFromWsThreadRunOnMainInOrder() = runTest(mainDispatcherRule.testDispatcher) {
        // follow 帧来自 WebSocket 读线程：VM 只允许把帧入队，处理串行在 Main（测试调度器）上。
        // 旧实现在 WS 线程里直接动 sessionEvents / toolEvents（与主线程竞态），这条测试锁住新语义。
        val remote = FakeRemoteApi(sessionId = "sess-1")
        val links = mutableListOf<FakeMuxLink>()
        val scope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob())
        val mux = DshMux(
            scope = scope,
            connect = { FakeMuxLink().also { links += it } },
            readyTimeoutMs = 1_000,
            retryCapsMs = listOf(10),
        )
        val viewModel = viewModel(remote, mux)
        try {
            runCurrent()
            val link = links.single()
            link.deliverHostEvent(readyFrame())
            runCurrent()
            val followStreamId = link.opened.entries
                .firstOrNull { it.value == "session/follow" }?.key
            assertTrue("session/follow 流没开出来", followStreamId != null)

            // WS 读线程按到达顺序投两帧。
            val wsThread = Thread {
                deliverFollow(link, followStreamId!!, turnEndEvent(5, System.currentTimeMillis(), "第一次失败"))
                deliverFollow(link, followStreamId, turnEndEvent(6, System.currentTimeMillis(), "第二次失败"))
            }
            wsThread.start()
            wsThread.join()
            // 此刻帧只入过队：由 runCurrent 驱动的主线程消费者按顺序处理。
            runCurrent()

            val entries = viewModel.uiState.value.entries
            val at5 = entries.indexOfFirst { it.id == "turnfail:5" }
            val at6 = entries.indexOfFirst { it.id == "turnfail:6" }
            assertTrue("第一帧失败事件没进列表", at5 >= 0)
            assertTrue("第二帧失败事件没进列表", at6 >= 0)
            assertTrue("帧处理顺序不对", at5 < at6)
        } finally {
            mux.close()
            scope.cancel()
        }
    }

    @Test
    fun watchRunEndGivesUpWhenStateKeepsFailing() = runTest(mainDispatcherRule.testDispatcher) {
        // 引擎持续拿不到 `state`：回合监视到达失败上限就按「没在跑」收尾，
        // 不再对着下线的引擎每秒空转（以前 `?: continue` 跳过失败计数，永远轮询）。
        val remote = FakeRemoteApi(failEndpoints = setOf("state"))
        val viewModel = viewModel(remote)
        runCurrent()

        viewModel.send("Hello")
        advanceUntilIdle()

        assertFalse("引擎持续失败后 running 应该被收掉", viewModel.uiState.value.running)
        assertFalse(viewModel.uiState.value.stopVisible)
    }

    @Test
    fun diagFailureRowSurvivesTimelineMerge() = runTest(mainDispatcherRule.testDispatcher) {
        // 诊断失败行写进 toolEvents（id = diag:<time>），mergeSessionEvents 剥完 base
        // 会从 toolEvents 重灌 —— 旧实现直接 append 进 entries，下一次合并就丢。
        // 让 `state` 一直失败：回合监视走「连续失败上限」收尾（无 mux 时乐观 running
        // 只有主机事件能清，state 若成功会无限轮询），诊断接口保持可用。
        val remote = FakeRemoteApi(
            failEndpoints = setOf("state"),
            diagnostics = {
                JSONObject().put(
                    "entries",
                    JSONArray().put(
                        JSONObject()
                            .put("source", "turn")
                            .put("time", System.currentTimeMillis())
                            .put("code", "rate_limit")
                            .put("text", "配额耗尽"),
                    ),
                )
            },
        )
        val viewModel = viewModel(remote)
        runCurrent()

        viewModel.send("Hello")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.entries.count { it.id.startsWith("diag:") })

        // 再触发一次合并（浏览器缩略图卡）：诊断行不能被剥掉。
        viewModel.addBrowserShot(
            ChatBrowserShot(path = "/tmp/x.png", url = "https://a.b/c", title = "t", timeMs = 42L),
        )
        runCurrent()
        assertEquals(
            "合并后诊断失败行丢了",
            1,
            viewModel.uiState.value.entries.count { it.id.startsWith("diag:") },
        )
    }

    @Test
    fun turnFailureNotDuplicatedWhenFollowAlreadyReported() = runTest(mainDispatcherRule.testDispatcher) {
        // follow 的 turn/end 已经给这一轮画过失败行：watch 收尾的诊断不能再补一条 diag: 行。
        // 同样让 `state` 失败，走失败上限收尾；diagnostics 可用。
        val remote = FakeRemoteApi(
            sessionId = "sess-1",
            failEndpoints = setOf("state"),
            diagnostics = {
                JSONObject().put(
                    "entries",
                    JSONArray().put(
                        JSONObject()
                            .put("source", "turn")
                            .put("time", System.currentTimeMillis())
                            .put("code", "boom")
                            .put("text", "失败"),
                    ),
                )
            },
        )
        val links = mutableListOf<FakeMuxLink>()
        val scope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob())
        val mux = DshMux(
            scope = scope,
            connect = { FakeMuxLink().also { links += it } },
            readyTimeoutMs = 1_000,
            retryCapsMs = listOf(10),
        )
        val viewModel = viewModel(remote, mux)
        try {
            runCurrent()
            val link = links.single()
            link.deliverHostEvent(readyFrame())
            runCurrent()
            val followStreamId = link.opened.entries
                .firstOrNull { it.value == "session/follow" }?.key
            assertTrue("session/follow 流没开出来", followStreamId != null)

            viewModel.send("Hello")
            runCurrent()
            // follow 先报同一轮失败（time 放到未来，稳落在监视窗口内）。
            deliverFollow(link, followStreamId!!, turnEndEvent(5, System.currentTimeMillis() + 60_000, "失败"))
            runCurrent()
            assertTrue(viewModel.uiState.value.entries.any { it.id == "turnfail:5" })

            advanceUntilIdle()
            assertEquals(
                "诊断补了重复的失败行",
                0,
                viewModel.uiState.value.entries.count { it.id.startsWith("diag:") },
            )
            assertEquals(1, viewModel.uiState.value.entries.count { it.id.startsWith("turnfail:") })
        } finally {
            mux.close()
            scope.cancel()
        }
    }

    /** 最小假实现：只有 `chat` / `state` / `diagnostics` 会被 ViewModel 在读历史与状态时调用。 */
    private class FakeRemoteApi(
        /** `chat` 回执里的 sessionId；非空时 ViewModel 会据此开 session/follow 流。 */
        val sessionId: String? = null,
        /** 列在这些名字里的端点一律抛错（模拟引擎下线/超时）。 */
        val failEndpoints: Set<String> = emptySet(),
        /** `state` 的固定回执；null 时回空对象。 */
        val stateResponse: JSONObject? = null,
        /** `diagnostics` 的回执构造（调用时刻生成，time 才能落在监视窗口内）。 */
        val diagnostics: (() -> JSONObject)? = null,
    ) : DshRemoteApi {
        val feedEvents = MutableSharedFlow<BotFeedEvent>(extraBufferCapacity = 32)

        override suspend fun bot(endpoint: String, payload: JSONObject): JSONObject {
            if (endpoint in failEndpoints) throw java.io.IOException("engine offline: $endpoint")
            return when (endpoint) {
                "chat", "room-chat" -> JSONObject().put("items", JSONArray()).also { response ->
                    sessionId?.let { response.put("sessionId", it) }
                }
                "state" -> stateResponse ?: JSONObject()
                "diagnostics" -> diagnostics?.invoke() ?: JSONObject()
                else -> JSONObject()
            }
        }

        override suspend fun botValue(endpoint: String, payload: JSONObject): Any? = JSONObject()

        override suspend fun rpc(method: String, args: JSONObject, timeoutMs: Int): JSONObject = JSONObject()

        override fun feed(): Flow<BotFeedEvent> = feedEvents
    }
}
