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
 * shadow 双跑的 VM 接线（Phase 1a）：依赖注入位点亮 shadow 后，
 * timeline 快照 + feed `timeline` 增量进对比器；主管线 entries 不受任何影响。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BotChatTimelineShadowTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val defaultSink = DshMuxLog.sink
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        DshMuxLog.sink = { logs += it }
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
        val feedEvents = MutableSharedFlow<BotFeedEvent>(extraBufferCapacity = 8)
        val timelineRequests = mutableListOf<TimelineRequest>()
        var snapshotRows: List<TimelineRow> = emptyList()

        override suspend fun bot(endpoint: String, payload: JSONObject): JSONObject = when (endpoint) {
            "chat", "room-chat" -> JSONObject()
                .put("sessionId", "s-1")
                .put(
                    "items",
                    JSONArray()
                        .put(JSONObject().put("kind", "user").put("text", "历史提问").put("seq", 1))
                        .put(JSONObject().put("kind", "bot").put("text", "历史回复").put("seq", 2)),
                )

            else -> JSONObject()
        }

        override suspend fun timeline(request: TimelineRequest): TimelineSnapshot {
            timelineRequests += request
            return TimelineSnapshot(
                sessionId = "s-1",
                roomId = null,
                seq = 40,
                rows = snapshotRows,
                cursor = null,
                running = null,
                speaking = null,
            )
        }

        override suspend fun botValue(endpoint: String, payload: JSONObject): Any? = JSONObject()
        override suspend fun rpc(method: String, args: JSONObject, timeoutMs: Int): JSONObject = JSONObject()
        override fun feed(): Flow<BotFeedEvent> = feedEvents

        fun row(key: String, kind: String, seq: Long) = TimelineRow(
            key = key,
            kind = kind,
            seq = seq,
            turn = null,
            timeMs = null,
            payload = TimelineRowPayload.Raw(JSONObject()),
        )

        fun emitTimeline(vararg rows: TimelineRow) {
            val data = JSONObject()
                .put("botId", "bot-1")
                .put("seq", rows.maxOfOrNull { it.seq ?: 0L } ?: 0L)
                .put("rows", JSONArray().apply { rows.forEach { row -> put(JSONObject().put("key", row.key).put("kind", row.kind).put("seq", row.seq)) } })
            feedEvents.tryEmit(BotFeedEvent(event = "timeline", data = data.toString()))
        }
    }

    private fun viewModel(api: FakeApi, scheduler: TestCoroutineScheduler, shadow: Boolean): BotChatViewModel {
        val viewModel = BotChatViewModel(
            botId = "bot-1",
            botName = "小助手",
            isGroup = false,
            endpoint = null,
            appContext = null,
            dependencies = BotChatDependencies(remote = api, transport = null, speech = null, mux = null, timelineShadow = shadow),
        )
        scheduler.runCurrent()
        return viewModel
    }

    @Test
    fun shadowModeFetchesSnapshotAndLogsDivergenceWithoutTouchingTheMainPipeline() {
        negotiateTimelineCapability()
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        // 行管线首屏与旧管线对齐：user:1 / bot:2。
        api.snapshotRows = listOf(api.row("user:1", "user", 1L), api.row("bot:2", "bot", 2L))

        val viewModel = viewModel(api, scheduler, shadow = true)
        scheduler.runCurrent()

        // 快照进来了（reset 全量，since 空）。
        assertTrue("shadow 开着就该拉快照", api.timelineRequests.isNotEmpty())
        assertNull(api.timelineRequests.first().since)

        // 行管线与旧管线对齐 → 无差异日志。
        assertTrue("对齐时不该写差异日志：$logs", logs.none { it.contains("timeline shadow 分歧") })

        // 行管线多出一条 bot:3（增量帧）→ 对比器报分歧。
        api.emitTimeline(api.row("bot:3", "bot", 3L))
        scheduler.runCurrent()
        assertTrue(
            "增量分歧没有进对比日志：$logs",
            logs.any { it.contains("timeline shadow 分歧") && it.contains("@bot-1") },
        )
        assertTrue(logs.last().contains("旧 2 行 / 新 3 行"))

        // 主管线零影响：feed timeline 事件不给 entries 添任何行。
        val entries = viewModel.uiState.value.entries
        assertEquals(listOf("s-1:1", "s-1:2"), entries.map { it.id })
        assertEquals(listOf(ChatEntryKind.USER, ChatEntryKind.BOT), entries.map { it.kind })
    }

    @Test
    fun resetFrameRefetchesTheWholeSnapshot() {
        negotiateTimelineCapability()
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        api.snapshotRows = listOf(api.row("user:1", "user", 1L), api.row("bot:2", "bot", 2L))
        val viewModel = viewModel(api, scheduler, shadow = true)
        scheduler.runCurrent()
        val before = api.timelineRequests.size

        api.feedEvents.tryEmit(BotFeedEvent(event = "timeline", data = JSONObject().put("reset", true).toString()))
        scheduler.runCurrent()

        assertTrue("reset 应重拉快照", api.timelineRequests.size > before)
        assertNull("重拉是全量（不带 since）", api.timelineRequests.last().since)
        assertEquals(listOf("s-1:1", "s-1:2"), viewModel.uiState.value.entries.map { it.id })
    }

    @Test
    fun shadowStaysOffWithoutTheTimelineCapability() {
        // 不协商 hello → capability 关 → 依赖注入位也点不亮 shadow。
        val scheduler = mainDispatcherRule.testDispatcher.scheduler
        val api = FakeApi()
        val viewModel = viewModel(api, scheduler, shadow = true)
        scheduler.runCurrent()

        assertEquals("能力缺席不应发 timeline 请求", 0, api.timelineRequests.size)
        api.emitTimeline(api.row("bot:3", "bot", 3L))
        scheduler.runCurrent()
        assertTrue("shadow 关着不该有对比日志", logs.none { it.contains("timeline shadow 分歧") })
        assertEquals(listOf("s-1:1", "s-1:2"), viewModel.uiState.value.entries.map { it.id })
    }
}
