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

    /** 最小假实现：只有 `chat` / `state` 会被 ViewModel 在读历史与状态时调用。 */
    private class FakeRemoteApi : DshRemoteApi {
        val feedEvents = MutableSharedFlow<BotFeedEvent>(extraBufferCapacity = 32)

        override suspend fun bot(endpoint: String, payload: JSONObject): JSONObject = when (endpoint) {
            "chat", "room-chat" -> JSONObject().put("items", JSONArray())
            else -> JSONObject()
        }

        override suspend fun botValue(endpoint: String, payload: JSONObject): Any? = JSONObject()

        override suspend fun rpc(method: String, args: JSONObject, timeoutMs: Int): JSONObject = JSONObject()

        override fun feed(): Flow<BotFeedEvent> = feedEvents
    }
}
