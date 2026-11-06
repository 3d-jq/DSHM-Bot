package com.dshm.bot.data.remote

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** 冷启动时引擎接口可能还没就绪：要能自愈，但不能把真实故障吞掉。 */
class DsBotRepositoryTest {

    @Test
    fun retriesInitialFetchWhileEngineIsStarting() {
        FakeDshServer().use { server ->
            val stateCalls = AtomicInteger()
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) -> FakeDshServer.cookie("repo")
                    request.path == "/api/bot" -> {
                        // 只数 state 请求 —— 主页还会问 usage / chat 拿预览。
                        val endpoint = Regex("\"endpoint\":\"([^\"]+)\"")
                            .find(request.body)?.groupValues?.get(1)
                        if (endpoint != "state") {
                            FakeDshServer.Response(200, body = "{\"ok\":true,\"value\":{}}")
                        } else if (stateCalls.incrementAndGet() == 1) {
                            FakeDshServer.Response(500, body = "engine starting")
                        } else {
                            FakeDshServer.Response(
                                200,
                                body = """{"ok":true,"value":{"bots":[{"id":"b1","name":"Bot"}]}}""",
                            )
                        }
                    }
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            val rows = runBlocking {
                withTimeout(5_000) {
                    DsBotRepository(DshClient(server.endpoint)).observeBots().first { it.isNotEmpty() }
                }
            }

            assertEquals("b1", rows.single().id)
            assertEquals(2, stateCalls.get())
        }
    }

    @Test
    fun stillPropagatesErrorAfterRetriesAreExhausted() {
        FakeDshServer().use { server ->
            val stateCalls = AtomicInteger()
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) -> FakeDshServer.cookie("repo")
                    request.path == "/api/bot" -> {
                        stateCalls.incrementAndGet()
                        FakeDshServer.Response(500, body = "always down")
                    }
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            val failure = runCatching {
                runBlocking {
                    withTimeout(5_000) {
                        DsBotRepository(DshClient(server.endpoint)).observeBots().first()
                    }
                }
            }.exceptionOrNull()

            assertNotNull("重试耗尽后仍应把错误交给界面：$failure", failure)
            assertEquals(3, stateCalls.get())
        }
    }
}
