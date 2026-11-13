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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 产物卡在**实时**会话流下就该出现。
 *
 * bug：Bot 说「交付了 N 个文件」后，对话里要重新进一次才看到产物卡 ——
 * 说明本轮事件没有在实时流里被用上。这里用假 mux 把一轮真实事件喂进去，
 * 断言产物卡当场出现（对齐 dsh-Go：卡贴在 `turn/end` 处）。
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

    @Test
    fun presentDeclaredInThisTurnShowsTheArtifactCard() {
        val scheduler: TestCoroutineScheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        val links = mutableListOf<FakeMuxLink>()
        val scope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob())
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
        try {
            scheduler.runCurrent()
            val link = links.single()
            link.deliverHostEvent(link.eventsStreamId(), ready())
            scheduler.runCurrent()

            val followStream = link.opened.entries.firstOrNull { it.value == "session/follow" }
            assertTrue("单聊没有订阅 session/follow，产物卡永远不会实时出现", followStream != null)

            // 一轮：turn/start → present 声明交付 → turn/end。
            link.deliverHostEvent(followStream!!.key, frame(sessionEvent(10, "turn/start", JSONObject().put("turn", 1))))
            link.deliverHostEvent(
                followStream.key,
                frame(
                    sessionEvent(
                        seq = 11,
                        type = "deliverables/presented",
                        data = JSONObject()
                            .put("turn", 1)
                            .put("callId", "call-1")
                            .put(
                                "files",
                                JSONArray().put(
                                    JSONObject()
                                        .put("path", "/workspace/报告.pptx")
                                        .put("description", "幻灯片"),
                                ),
                            ),
                    ),
                ),
            )
            link.deliverHostEvent(followStream.key, frame(sessionEvent(12, "turn/end", JSONObject().put("turn", 1))))
            scheduler.runCurrent()

            val cards = viewModel.uiState.value.entries.filter { it.kind == ChatEntryKind.ARTIFACTS }
            assertTrue(
                "本轮 `deliverables/presented` 没有推出产物卡（现有条目：" +
                    viewModel.uiState.value.entries.joinToString { "${it.kind}@${it.seq}" } + "）",
                cards.isNotEmpty(),
            )
        } finally {
            mux.close()
            scope.cancel()
        }
    }

    @Test
    fun presentAloneShowsCardBeforeTurnEnd() {
        val scheduler: TestCoroutineScheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        val links = mutableListOf<FakeMuxLink>()
        val scope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob())
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
        try {
            scheduler.runCurrent()
            val link = links.single()
            link.deliverHostEvent(link.eventsStreamId(), ready())
            scheduler.runCurrent()
            val followStream = link.opened.entries.firstOrNull { it.value == "session/follow" }

            // 只给 turn/start + present，**不给 turn/end** —— 产物卡必须当场出现（回归点）。
            link.deliverHostEvent(followStream!!.key, frame(sessionEvent(30, "turn/start", JSONObject().put("turn", 2))))
            link.deliverHostEvent(
                followStream.key,
                frame(
                    sessionEvent(
                        seq = 31,
                        type = "deliverables/presented",
                        data = JSONObject()
                            .put("turn", 2)
                            .put("callId", "call-2")
                            .put(
                                "files",
                                JSONArray().put(JSONObject().put("path", "/workspace/图.png")),
                            ),
                    ),
                ),
            )
            scheduler.runCurrent()

            val cards = viewModel.uiState.value.entries.filter { it.kind == ChatEntryKind.ARTIFACTS }
            assertTrue(
                "present 已到但轮未结束，产物卡没实时出现（现有条目：" +
                    viewModel.uiState.value.entries.joinToString { "${it.kind}@${it.seq}" } + "）",
                cards.isNotEmpty(),
            )
        } finally {
            mux.close()
            scope.cancel()
        }
    }

    private fun ready(clientId: String = "client-1") = JSONObject()
        .put("type", "ready")
        .put("clientId", clientId)
        .put("host", JSONObject().put("home", "/"))

    private fun frame(event: JSONObject) = JSONObject()
        .put("type", "event")
        .put("event", event)

    private fun sessionEvent(seq: Long, type: String, data: JSONObject) = JSONObject()
        .put("type", type)
        .put("seq", seq)
        .put("time", 1_700_000_000_000L + seq)
        .put("data", data)

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
