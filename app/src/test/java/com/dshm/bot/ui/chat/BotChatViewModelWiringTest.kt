package com.dshm.bot.ui.chat

import com.dshm.bot.MainDispatcherRule
import com.dshm.bot.data.remote.BotFeedEvent
import com.dshm.bot.data.remote.DshMux
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
 * `BotChatViewModel` 的宿主级接线测试（不走会话行流的那些）：
 * 主机事件 → 审批/提问卡、feed 流式侧通道、回合监视的失败收尾。
 *
 * 这些通道是宿主级交互状态与客户端侧通道（设计 §5），不属于行管线；
 * 会话行的映射/快照/增量测试在 [BotChatParityPipelineTest]。
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

    /** 流式 delta 走 feed `stream` 事件的客户端侧通道（行管线不承载流式文本）。 */
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

    /** 最小假实现：`state` 会被 ViewModel 轮询；`send` / `timeline` 给空回执。 */
    private class FakeRemoteApi(
        /** 列在这些名字里的端点一律抛错（模拟引擎下线/超时）。 */
        val failEndpoints: Set<String> = emptySet(),
    ) : DshRemoteApi {
        val feedEvents = MutableSharedFlow<BotFeedEvent>(extraBufferCapacity = 32)

        override suspend fun bot(endpoint: String, payload: JSONObject): JSONObject {
            if (endpoint in failEndpoints) throw java.io.IOException("engine offline: $endpoint")
            return JSONObject()
        }

        override suspend fun botValue(endpoint: String, payload: JSONObject): Any? = JSONObject()

        override suspend fun rpc(method: String, args: JSONObject, timeoutMs: Int): JSONObject {
            if (method in failEndpoints) throw java.io.IOException("engine offline: $method")
            return JSONObject()
        }

        override fun feed(): Flow<BotFeedEvent> = feedEvents

        override suspend fun timeline(request: com.dshm.bot.data.remote.TimelineRequest) =
            null as com.dshm.bot.data.remote.TimelineSnapshot?
    }
}
