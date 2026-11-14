package com.dshm.bot.data.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * mux 状态机（照 dsh-Go `packages/dsh_client/src/mux.dart`）。
 */
class DshMuxTest {

    private val defaultSink = DshMuxLog.sink

    /**
     * 状态机里带 logcat 埋点：`android.util.Log` 在 JVM 单测里没被 mock，
     * 直接调用会让整批用例挂掉。`DshMuxLog` 抽出 sink 就是为了这里能换掉。
     */
    @Before
    fun muteLogs() {
        DshMuxLog.sink = { }
    }

    @After
    fun restoreLogs() {
        DshMuxLog.sink = defaultSink
    }

    /** 让后台协程跑一会儿。 */
    private suspend fun settle() {
        delay(120)
    }

    /** ready 帧。 */
    private fun ready(clientId: String = "client-1") = JSONObject()
        .put("type", "ready")
        .put("clientId", clientId)
        .put("host", JSONObject().put("home", "/home"))

    /** 审批 waterfall 帧。 */
    private fun approvalFrame(
        eventId: String = "ev-1",
        sessionId: String = "s-1",
        tool: String = "bash",
    ) = JSONObject()
        .put("type", "waterfall")
        .put("event", "approval/request")
        .put("eventId", eventId)
        .put("agentId", sessionId)
        .put(
            "request",
            JSONObject()
                .put("toolName", tool)
                .put("reason", "要跑一条命令")
                .put("displayReason", JSONObject().put("zh", "允许执行 $tool 吗？")),
        )

    /** 提问 waterfall 帧。 */
    private fun questionFrame(eventId: String = "q-1", sessionId: String = "s-1") = JSONObject()
        .put("type", "waterfall")
        .put("event", "user-questions/request")
        .put("eventId", eventId)
        .put("agentId", sessionId)
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

    /** 建一个 mux；每次连接造一条新链路，按顺序放进 [links]。 */
    private fun newMux(
        scope: CoroutineScope,
        links: MutableList<FakeMuxLink>,
    ): DshMux = DshMux(
        scope = scope,
        connect = { FakeMuxLink().also { links += it } },
        readyTimeoutMs = 1_000,
        retryCapsMs = listOf(10),
    )

    @Test
    fun readyEstablishesTheFirstGeneration() = runBlocking {
        val links = mutableListOf<FakeMuxLink>()
        val mux = newMux(this, links)
        try {
            mux.start()
            settle()

            val link = links.last()
            assertTrue("events stream 未打开", link.eventsStreamId().isNotEmpty())
            assertEquals(
                DshMuxFrame.HOST_EVENTS_ENDPOINT,
                link.opened[link.eventsStreamId()],
            )

            link.deliverHostEvent(ready("client-1"))
            settle()

            assertEquals(DshMuxState.CONNECTED, mux.state.value)
            val generation = mux.generation
            assertNotNull(generation)
            assertEquals(1, generation!!.number)
            assertEquals("client-1", generation!!.clientId)
        } finally {
            mux.close()
            settle()
        }
    }

    @Test
    fun anApprovalRequestBecomesPending() = runBlocking {
        val links = mutableListOf<FakeMuxLink>()
        val mux = newMux(this, links)
        try {
            mux.start()
            settle()
            val link = links.last()
            link.deliverHostEvent(ready())
            settle()

            link.deliverHostEvent(approvalFrame(tool = "bash"))
            settle()

            val pending = mux.pendingRequests()
            assertEquals(1, pending.size)
            val approval = pending.first().approval
            assertNotNull(approval)
            assertEquals("ev-1", approval!!.eventId)
            assertEquals("bash", approval.toolName)
            assertEquals("要跑一条命令", approval.reason)
            assertEquals("允许执行 bash 吗？", approval.describe("zh"))
        } finally {
            mux.close()
            settle()
        }
    }

    @Test
    fun aQuestionRequestBecomesPending() = runBlocking {
        val links = mutableListOf<FakeMuxLink>()
        val mux = newMux(this, links)
        try {
            mux.start()
            settle()
            val link = links.last()
            link.deliverHostEvent(ready())
            settle()

            link.deliverHostEvent(questionFrame())
            settle()

            val pending = mux.pendingRequests()
            assertEquals(1, pending.size)
            val question = pending.first().question
            assertNotNull(question)
            assertEquals(1, question!!.questions.size)
            assertEquals("要继续吗？", question.questions.first().question)
            assertEquals(listOf("是", "否"), question.questions.first().options.map { it.label })
        } finally {
            mux.close()
            settle()
        }
    }

    @Test
    fun aCancelledRequestIsDropped() = runBlocking {
        val links = mutableListOf<FakeMuxLink>()
        val mux = newMux(this, links)
        try {
            mux.start()
            settle()
            val link = links.last()
            link.deliverHostEvent(ready())
            settle()

            link.deliverHostEvent(approvalFrame())
            settle()
            assertEquals(1, mux.pendingRequests().size)

            link.deliverHostEvent(JSONObject().put("type", "cancel").put("eventId", "ev-1"))
            settle()
            assertTrue(mux.pendingRequests().isEmpty())
        } finally {
            mux.close()
            settle()
        }
    }

    @Test
    fun aNewGenerationReplacesTheClientId() = runBlocking {
        val links = mutableListOf<FakeMuxLink>()
        val mux = newMux(this, links)
        try {
            mux.start()
            settle()

            links.last().deliverHostEvent(ready("client-1"))
            settle()
            assertEquals("client-1", mux.generation!!.clientId)

            // 断开 → 重连 → 新的 ready
            links.last().lose("网络断了")
            settle()
            links.last().deliverHostEvent(ready("client-2"))
            settle()

            assertEquals("client-2", mux.generation!!.clientId)
            assertEquals(2, mux.generation!!.number)
        } finally {
            mux.close()
            settle()
        }
    }

    @Test
    fun pendingRequestsAreClearedOnDisconnect() = runBlocking {
        val links = mutableListOf<FakeMuxLink>()
        val mux = newMux(this, links)
        try {
            mux.start()
            settle()
            links.last().deliverHostEvent(ready())
            settle()
            links.last().deliverHostEvent(approvalFrame())
            settle()
            assertEquals(1, mux.pendingRequests().size)

            links.last().lose("网络断了")
            settle()
            // 主机会在新的一代里用同一个 eventId 重新投递，本地不留陈旧的。
            assertTrue(mux.pendingRequests().isEmpty())
        } finally {
            mux.close()
            settle()
        }
    }

    @Test
    fun eventsBeforeReadyAreAProtocolFailure() = runBlocking {
        val links = mutableListOf<FakeMuxLink>()
        val mux = newMux(this, links)
        try {
            mux.start()
            settle()

            // ready 之前先来一个 waterfall —— 不合法。
            links.last().deliverHostEvent(approvalFrame())
            settle()

            assertNull(mux.generation)
        } finally {
            mux.close()
            settle()
        }
    }

    @Test
    fun aLogicalStreamIsReopenedAfterReconnect() = runBlocking {
        val links = mutableListOf<FakeMuxLink>()
        val mux = newMux(this, links)
        try {
            mux.start()
            settle()
            links.last().deliverHostEvent(ready())
            settle()

            val streamId = mux.openStream(
                endpoint = "session/follow",
                args = JSONObject().put("sessionId", "s-1"),
                onFrame = {},
            )
            settle()
            assertEquals("session/follow", links.last().opened[streamId])

            links.last().lose("网络断了")
            settle()
            links.last().deliverHostEvent(ready("client-2"))
            settle()

            // 新的一代里同一条流要重开。
            assertEquals("session/follow", links.last().opened[streamId])
        } finally {
            mux.close()
            settle()
        }
    }

    /**
     * 读线程（onFrame / onLinkLost / ready 重开）与 openStream / closeStream 并发操作
     * streams：修复前读线程对 LinkedHashMap 的访问没有任何锁，会 CME / 结构损坏。
     * 这条测试把 get、remove、遍历 fail、遍历重开四种访问搅在一起锁住并发语义。
     */
    @Test
    fun streamBookkeepingSurvivesConcurrentFramesAndReconnects() = runBlocking {
        val links = java.util.Collections.synchronizedList(mutableListOf<FakeMuxLink>())
        val mux = newMux(this, links)
        try {
            mux.start()
            settle()
            links.last().deliverHostEvent(ready())
            settle()

            val errors = mutableListOf<Throwable>()
            val liveIds = java.util.concurrent.ConcurrentLinkedQueue<String>()
            // 模拟 OkHttp 读线程：FakeMuxLink 的 deliver 直接在调用线程上触发 onFrame。
            val reader = Thread {
                try {
                    repeat(6_000) { round ->
                        val link = synchronized(links) { links.lastOrNull() } ?: return@repeat
                        if (round % 500 == 499) {
                            // 触发 onLinkLost：快照遍历 streams 并 fail 全部逻辑流；
                            // 对已被替代的旧链路重复 lose，顺带压到「令牌比较」守卫。
                            link.lose("并发测试断链")
                        } else {
                            val known = liveIds.peek() ?: "ghost-$round"
                            val type = if (round % 4 == 3) "end" else "item"
                            link.deliver(
                                JSONObject()
                                    .put("type", type)
                                    .put("streamId", known)
                                    .put("value", JSONObject().put("n", round)),
                            )
                        }
                    }
                } catch (t: Throwable) {
                    synchronized(errors) { errors += t }
                }
            }
            reader.start()

            // 主协程交替开流 / 关流；delay 让 closeStream 的 launch 体真正跑起来，
            // 同时补 ready 让 ready 分支的「重开全部逻辑流」遍历跑起来。
            repeat(120) { round ->
                liveIds.add(mux.openStream("session/follow", JSONObject(), onFrame = {}, onEnd = {}))
                delay(2)
                if (round % 2 == 0) liveIds.poll()?.let { mux.closeStream(it) }
                synchronized(links) { links.lastOrNull() }?.deliverHostEvent(ready("client-$round"))
                delay(2)
            }
            reader.join(15_000)
            assertTrue("读线程不该有异常：${errors.firstOrNull()}", errors.isEmpty())
        } finally {
            mux.close()
            settle()
        }
    }

    /** 断链 fail 与用户 closeStream 竞争同一条流时，onEnd 只能发一次。 */
    @Test
    fun aStreamEndsAtMostOnceWhenLostAndClosedRace() = runBlocking {
        val links = mutableListOf<FakeMuxLink>()
        val mux = newMux(this, links)
        try {
            mux.start()
            settle()
            links.last().deliverHostEvent(ready())
            settle()

            val ends = mutableListOf<String?>()
            val streamId = mux.openStream(
                endpoint = "session/follow",
                args = JSONObject(),
                onFrame = {},
                onEnd = { reason -> synchronized(ends) { ends.add(reason) } },
            )
            settle()

            // 先断链（fail("网络断了")，句柄仍留在表里等重开），紧接着用户关流再 fail 一次。
            links.last().lose("网络断了")
            mux.closeStream(streamId)
            settle()

            assertEquals("同一条流的 onEnd 只许发一次：$ends", 1, ends.size)
            assertEquals("网络断了", ends.single())
        } finally {
            mux.close()
            settle()
        }
    }

    /** 旧链路迟到的失败回调不许清掉新一代（令牌比较挡在 linkLock 里）。 */
    @Test
    fun aStaleLinkLostCallbackDoesNotTearDownTheNewGeneration() = runBlocking {
        val links = mutableListOf<FakeMuxLink>()
        val mux = newMux(this, links)
        try {
            mux.start()
            settle()
            links.last().deliverHostEvent(ready("client-1"))
            settle()

            val firstLink = links.last()
            val ends = mutableListOf<String?>()
            mux.openStream(
                endpoint = "session/follow",
                args = JSONObject(),
                onFrame = {},
                onEnd = { reason -> synchronized(ends) { ends.add(reason) } },
            )
            settle()

            // 第一代断开 → 重连出第二代（同一条流在新一代里重开）。
            firstLink.lose("网络断了")
            settle()
            links.last().deliverHostEvent(ready("client-2"))
            settle()
            assertEquals("client-2", mux.generation!!.clientId)
            assertEquals(2, mux.generation!!.number)
            assertEquals(1, ends.size)

            // 旧链路的 onLost 迟到再触发一次（FakeMuxLink 不做幂等，正合适）。
            firstLink.lose("迟到的断链回调")
            settle()

            // 新一代必须完好：generation 没被顶掉，逻辑流没有被再次 fail。
            assertEquals("client-2", mux.generation!!.clientId)
            assertEquals(2, mux.generation!!.number)
            assertEquals("迟到的回调不能再次 fail 逻辑流：$ends", 1, ends.size)
        } finally {
            mux.close()
            settle()
        }
    }
}
