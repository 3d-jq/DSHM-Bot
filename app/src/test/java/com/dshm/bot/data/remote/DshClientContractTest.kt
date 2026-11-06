package com.dshm.bot.data.remote

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DshClient 的真实 HTTP 契约：认证失效、业务错误、SSE 帧结尾。
 * 用本机 FakeDshServer，不依赖网络、不引入新测试库。
 */
class DshClientContractTest {

    @Test
    fun reLoginsAndRetriesOnceWhenCookieIsRejected() {
        FakeDshServer().use { server ->
            val logins = AtomicInteger()
            val botCalls = AtomicInteger()
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) -> {
                        FakeDshServer.cookie("v${logins.incrementAndGet()}")
                    }
                    request.path == "/api/bot" -> {
                        if (botCalls.incrementAndGet() == 1) {
                            FakeDshServer.Response(401, body = "expired")
                        } else {
                            FakeDshServer.Response(200, body = """{"ok":true,"value":{"bots":[]}}""")
                        }
                    }
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            val value = runBlocking { DshClient(server.endpoint).bot("state") }

            assertTrue(value.has("bots"))
            assertEquals(2, logins.get())
            assertEquals(2, botCalls.get())
            assertTrue(
                "重试必须带重新登录拿到的新 cookie",
                server.requests().any { it.cookie == "dsh-auth-v2=abc" },
            )
        }
    }

    @Test
    fun rpcAlsoReLoginsOnForbidden() {
        FakeDshServer().use { server ->
            val logins = AtomicInteger()
            val rpcCalls = AtomicInteger()
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) -> {
                        FakeDshServer.cookie("r${logins.incrementAndGet()}")
                    }
                    request.path == "/api/settings/describe" -> {
                        if (rpcCalls.incrementAndGet() == 1) {
                            FakeDshServer.Response(403, body = "forbidden")
                        } else {
                            FakeDshServer.Response(
                                200,
                                body = """{"result":{"ok":true,"value":{"namespaces":[]}}}""",
                            )
                        }
                    }
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            val value = runBlocking { DshClient(server.endpoint).rpc("settings/describe") }

            assertTrue(value.has("namespaces"))
            assertEquals(2, logins.get())
            assertEquals(2, rpcCalls.get())
        }
    }

    @Test
    fun businessErrorDoesNotTriggerReLoginOrRetry() {
        FakeDshServer().use { server ->
            val logins = AtomicInteger()
            val botCalls = AtomicInteger()
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) -> {
                        logins.incrementAndGet()
                        FakeDshServer.cookie("b")
                    }
                    request.path == "/api/bot" -> {
                        botCalls.incrementAndGet()
                        FakeDshServer.Response(
                            200,
                            body = """{"ok":false,"error":{"code":"settings/rejected","message":"no"}}""",
                        )
                    }
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            val failure = runCatching {
                runBlocking { DshClient(server.endpoint).bot("state") }
            }.exceptionOrNull()

            assertTrue("业务错误应原样抛出：$failure", failure is DshRpcException)
            assertEquals("settings/rejected", (failure as DshRpcException).code)
            assertEquals(1, botCalls.get())
            assertEquals(1, logins.get())
        }
    }

    @Test
    fun emitsTrailingSseEventEvenWithoutBlankLine() {
        FakeDshServer().use { server ->
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) -> FakeDshServer.cookie("sse")
                    request.path == "/api/bot/feed" -> FakeDshServer.Response(
                        200,
                        mapOf("Content-Type" to "text/event-stream"),
                        body = "event: revision\ndata: {\"n\":1}",
                    )
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            val event = runBlocking {
                withTimeout(5_000) { DshClient(server.endpoint).feed().first() }
            }

            assertEquals("revision", event.event)
            assertEquals("{\"n\":1}", event.data)
        }
    }

    @Test
    fun keepsDispatchingEventsSeparatedByBlankLines() {
        FakeDshServer().use { server ->
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) -> FakeDshServer.cookie("sse")
                    request.path == "/api/bot/feed" -> FakeDshServer.Response(
                        200,
                        mapOf("Content-Type" to "text/event-stream"),
                        body = "event: revision\ndata: one\n\nevent: live\ndata: two\n",
                    )
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            val events = runBlocking {
                withTimeout(5_000) { DshClient(server.endpoint).feed().take(2).toList() }
            }

            assertEquals(
                listOf("revision" to "one", "live" to "two"),
                events.map { it.event to it.data },
            )
        }
    }
}
