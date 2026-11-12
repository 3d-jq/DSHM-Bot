package com.dshm.bot.data.remote

import com.dshm.bot.domain.model.BotSummary
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

    /** A：feed 推来模型输出时，就地打补丁并对外发新列表（不再等 revision 全量拉）。 */
    @Test
    fun `feed 预览就地打进列表`() {
        val rows = listOf(summary("b1", "上一轮的话"), summary("b2", "另一个 Bot"))

        val patched = patchBotPreview(rows, "b1", "新的一轮输出")

        assertNotNull("有变化就该返回新列表", patched)
        assertEquals("新的一轮输出", patched!!.first { it.id == "b1" }.preview)
        assertEquals("别的 Bot 不能被改", "另一个 Bot", patched.first { it.id == "b2" }.preview)
    }

    @Test
    fun `预览没变时不重复 emit`() {
        val rows = listOf(summary("b1", "一样的话"))

        assertEquals("重复文本不该触发重组", null, patchBotPreview(rows, "b1", "一样的话"))
    }

    @Test
    fun `群聊预览不走这条路径`() {
        val group = summary("r1", "群上一轮", isGroup = true)

        assertEquals("群聊预览仍以 state 为准", null, patchBotPreview(listOf(group), "r1", "群新一轮"))
    }

    /** 冷启动：快照里的 preview 是 role，必须被已知预览盖掉，别先闪角色标签。 */
    @Test
    fun `冷启动快照用已知预览覆盖 role`() {
        val rows = listOf(
            summary("b1", "网络讲师"),           // state 里的 role
            summary("b2", "另一个角色"),
            summary("r1", "群上一轮", isGroup = true),
        )

        val applied = applyKnownPreviews(rows, mapOf("b1" to "刚写完的 HTML 放到工作区了"))

        assertEquals("刚写完的 HTML 放到工作区了", applied.first { it.id == "b1" }.preview)
        assertEquals("没有已知预览就保留原样（回退 role）", "另一个角色", applied.first { it.id == "b2" }.preview)
        assertEquals("群聊不被覆盖", "群上一轮", applied.first { it.id == "r1" }.preview)
    }

    private fun summary(id: String, preview: String, isGroup: Boolean = false) = BotSummary(
        id = id,
        name = id,
        preview = preview,
        lastActiveAt = java.time.Instant.EPOCH,
        isGroup = isGroup,
    )
}
