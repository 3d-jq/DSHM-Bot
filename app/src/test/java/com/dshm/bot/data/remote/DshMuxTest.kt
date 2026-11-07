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
import org.junit.Test

/**
 * mux 状态机（照 dsh-Go `packages/dsh_client/src/mux.dart`）。
 */
class DshMuxTest {

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
}
