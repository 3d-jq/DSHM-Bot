package com.dshm.bot.data.remote

import java.util.Collections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `timeline` 端点的真实 HTTP 契约（`docs/phase1-design.md` §3 快照帧）：
 * envelope 对齐 `bot()`（body `{endpoint, payload}`、响应 `{ok, value}`）、
 * 请求形状 `{botId|roomId, since?, cursor?, max?}`、响应解析含行/游标/群聊状态。
 */
class TimelineEndpointContractTest {

    private fun timelineValue() = JSONObject()
        .put("sessionId", "s-1")
        .put("seq", 42)
        .put(
            "rows",
            JSONArray()
                .put(
                    JSONObject()
                        .put("key", "user:40")
                        .put("kind", "user")
                        .put("seq", 40)
                        .put("turn", 1)
                        .put("timeMs", 1_000L)
                        .put("payload", JSONObject().put("text", "问题").put("requestId", "req-1")),
                )
                .put(
                    JSONObject()
                        .put("key", "present:1")
                        .put("kind", "artifact")
                        .put("seq", 41)
                        .put("payload", JSONObject().put("delivered", true)),
                ),
        )
        .put("cursor", JSONObject().put("beforeSeq", 40))
        .put("running", false)

    private fun server(value: String, bodies: MutableList<String>): FakeDshServer =
        FakeDshServer().apply {
            responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) ->
                        FakeDshServer.cookie("sess")

                    request.path == "/api/bot" -> {
                        val requested = JSONObject(request.body).optString("endpoint")
                        bodies.add(request.body)
                        if (requested == "timeline") {
                            FakeDshServer.Response(200, body = """{"ok":true,"value":$value}""")
                        } else {
                            FakeDshServer.Response(200, body = """{"ok":true,"value":{}}""")
                        }
                    }

                    else -> FakeDshServer.Response(404)
                }
            }
        }

    @Test
    fun timelineSendsBotEnvelopeAndParsesSnapshot() {
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        server(timelineValue().toString(), bodies).use { fake ->
            fake.start()

            val snapshot = runBlocking {
                RemoteDshApi(DshClient(fake.endpoint)).timeline(
                    TimelineRequest(botId = "bot-1", since = 30L, max = 60),
                )
            }

            assertTrue(snapshot != null)
            assertEquals("s-1", snapshot!!.sessionId)
            assertEquals(42L, snapshot.seq)
            assertEquals(listOf("user:40", "present:1"), snapshot.rows.map { it.key })
            assertEquals(40L, snapshot.cursor?.optLong("beforeSeq"))
            assertEquals(false, snapshot.running)
            assertNull(snapshot.speaking)
            val user = snapshot.rows.first().payload as TimelineRowPayload.User
            assertEquals("问题", user.text)
            assertEquals("req-1", user.requestId)

            val envelope = JSONObject(bodies.single())
            assertEquals("timeline", envelope.optString("endpoint"))
            val payload = envelope.getJSONObject("payload")
            assertEquals("bot-1", payload.optString("botId"))
            assertEquals(30L, payload.optLong("since"))
            assertEquals(60, payload.optInt("max"))
        }
    }

    @Test
    fun timelineRoomRequestUsesRoomIdAndParsesRoomState() {
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        val value = JSONObject()
            .put("roomId", "room-1")
            .put("seq", 9)
            .put("rows", JSONArray())
            .put("running", true)
            .put("speaking", "member-1")
        server(value.toString(), bodies).use { fake ->
            fake.start()

            val snapshot = runBlocking { RemoteDshApi(DshClient(fake.endpoint)).timeline(TimelineRequest(roomId = "room-1")) }

            assertTrue(snapshot != null)
            assertEquals("room-1", snapshot!!.roomId)
            assertNull(snapshot.sessionId)
            assertEquals(true, snapshot.running)
            assertEquals("member-1", snapshot.speaking)
            val payload = JSONObject(bodies.single()).getJSONObject("payload")
            assertEquals("room-1", payload.optString("roomId"))
            assertTrue("群聊请求不该带 botId", !payload.has("botId"))
        }
    }

    @Test
    fun timelineBusinessErrorPropagatesAsRpcException() {
        // 错误用例不走 value 包装 helper：直接回 ok:false 的业务错误 envelope。
        FakeDshServer().use { fake ->
            fake.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) ->
                        FakeDshServer.cookie("sess")

                    request.path == "/api/bot" -> FakeDshServer.Response(
                        200,
                        body = """{"ok":false,"error":{"code":"bot/not-found","message":"没有这个 Bot"}}""",
                    )

                    else -> FakeDshServer.Response(404)
                }
            }
            fake.start()
            try {
                runBlocking { RemoteDshApi(DshClient(fake.endpoint)).timeline(TimelineRequest(botId = "ghost")) }
                fail("业务错误应该抛 DshRpcException，shadow 调用方才好 runCatching")
            } catch (expected: DshRpcException) {
                assertEquals("bot/not-found", expected.code)
            }
        }
    }

    @Test
    fun malformedTimelineValueThrowsInsteadOfSilentNull() {
        FakeDshServer().use { fake ->
            fake.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) ->
                        FakeDshServer.cookie("sess")

                    request.path == "/api/bot" -> FakeDshServer.Response(
                        200,
                        body = """{"ok":true,"value":{"rows":"不是数组"}}""",
                    )

                    else -> FakeDshServer.Response(404)
                }
            }
            fake.start()
            try {
                runBlocking { RemoteDshApi(DshClient(fake.endpoint)).timeline(TimelineRequest(botId = "bot-1")) }
                fail("形状不对应该抛错（调用方决定怎么吞）")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("timeline"))
            }
        }
    }

    /** 接口默认实现 = 「不支持」：旧假实现零改动，shadow 据此静默关闭。 */
    @Test
    fun interfaceDefaultReturnsNull() {
        val bare = object : DshRemoteApi {
            override suspend fun bot(endpoint: String, payload: JSONObject): JSONObject = JSONObject()
            override suspend fun botValue(endpoint: String, payload: JSONObject): Any? = JSONObject()
            override suspend fun rpc(method: String, args: JSONObject, timeoutMs: Int): JSONObject = JSONObject()
            override fun feed(): Flow<BotFeedEvent> = kotlinx.coroutines.flow.emptyFlow()
        }
        val snapshot = runBlocking { bare.timeline(TimelineRequest(botId = "bot-1")) }
        assertNull(snapshot)
    }
}
