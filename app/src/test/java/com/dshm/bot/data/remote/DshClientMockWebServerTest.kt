package com.dshm.bot.data.remote

import com.dshm.bot.data.runtime.RuntimeEndpoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * `DshClient` 的 MockWebServer 契约测试（roadmap 的「正常 / 断线 / 超大消息 / 取消」）。
 *
 * 之前只有手写 `FakeDshServer`：它一次把整个响应写完，没法模拟
 * 「流开着的时候断开」「大响应的读取」「取消后不再重连」。
 */
class DshClientMockWebServerTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun client() = DshClient(RuntimeEndpoint(server.url("/").toString(), "test-token"))

    private fun loginResponse(): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Set-Cookie", "dsh-auth-x=abc; Path=/")

    private fun okResponse(value: Any): MockResponse = MockResponse()
        .setResponseCode(200)
        .setBody(JSONObject().put("ok", true).put("value", value).toString())

    @Test
    fun normalRequestsCarryCookieAndEnvelope() = runBlocking {
        server.enqueue(loginResponse())
        server.enqueue(okResponse(JSONObject().put("items", org.json.JSONArray())))

        val result = client().bot("chat", JSONObject().put("botId", "bot-1"))

        assertTrue(result.has("items"))
        val login = server.takeRequest()
        assertEquals("/?token=test-token", login.path)
        val bot = server.takeRequest()
        assertEquals("/api/bot", bot.path)
        assertEquals("POST", bot.method)
        assertTrue(bot.getHeader("Cookie").orEmpty().contains("dsh-auth-x=abc"))
        val body = JSONObject(bot.body.readUtf8())
        assertEquals("chat", body.getString("endpoint"))
        assertEquals("bot-1", body.getJSONObject("payload").getString("botId"))
    }

    @Test
    fun normalSseDispatchesEventsOnBlankLines() = runBlocking {
        server.enqueue(loginResponse())
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "event: hello\ndata: {\"a\":1}\n\n" +
                        "event: message\ndata: {\"b\":2}\n\n",
                ),
        )

        val events = withTimeout(5_000) {
            client().feed().take(2).toList()
        }

        assertEquals(listOf("hello", "message"), events.map { it.event })
        assertEquals("{\"a\":1}", events[0].data)
    }

    @Test
    fun disconnectDuringSseReconnects() = runBlocking {
        // 第一条 feed 写完一条事件后断开连接；客户端应该退避重连。
        // 用 Dispatcher 而不是队列：断线后客户端会不会重新 login 取决于
        // EOF 还是 IOException，两条路都要能拿到响应。
        val feedRequests = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/?token=") -> loginResponse()
                    path == "/api/bot/feed" -> {
                        val attempt = feedRequests.incrementAndGet()
                        val body = if (attempt == 1) {
                            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                        } else {
                            MockResponse()
                        }
                        body
                            .setResponseCode(200)
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody(
                                if (attempt == 1) {
                                    "event: hello\ndata: {}\n\n"
                                } else {
                                    "event: message\ndata: {\"reconnected\":true}\n\n"
                                },
                            )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val events = withTimeout(15_000) {
            client().feed().take(2).toList()
        }

        assertTrue("断线后没有重连", feedRequests.get() >= 2)
        // 断线点由 MockWebServer 控制，第一条事件可能在断开前丢失；
        // 契约要保证的是「重连后继续收到事件」。
        assertTrue(
            "重连后没有收到事件：${events.map { it.data }}",
            events.any { it.data.contains("\"reconnected\":true") },
        )
    }

    @Test
    fun oversizedSseEventIsDeliveredWhole() = runBlocking {
        val big = "x".repeat(2 * 1024 * 1024)
        server.enqueue(loginResponse())
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: message\ndata: $big\n\n"),
        )

        val event = withTimeout(10_000) {
            client().feed().first()
        }

        assertEquals("message", event.event)
        assertEquals(big.length, event.data.length)
        assertTrue(event.data.all { it == 'x' })
    }

    @Test
    fun cancellingFeedDoesNotReconnect() = runBlocking {
        server.enqueue(loginResponse())
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: hello\ndata: {}\n\n"),
        )

        withTimeout(5_000) {
            client().feed().first()
        }
        // 正常 EOF 会等 2s 再重连；取消后不应该再有第二条 feed 请求。
        delay(2_500)
        assertEquals(2, server.requestCount)
    }
}
