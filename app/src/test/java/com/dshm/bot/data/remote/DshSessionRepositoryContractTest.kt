package com.dshm.bot.data.remote

import com.dshm.bot.data.runtime.RuntimeEndpoint
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DSH 会话 RPC 的真实 HTTP 契约：envelope、payload 形状、数组型 value 的保留。 */
class DshSessionRepositoryContractTest {

    private fun rpcResult(value: String) = FakeDshServer.Response(
        200,
        body = """{"result":{"ok":true,"value":$value}}""",
    )

    @Test
    fun listSessionsSendsRpcEnvelopeAndParsesItems() {
        FakeDshServer().use { server ->
            val bodies = Collections.synchronizedList(mutableListOf<String>())
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) -> FakeDshServer.cookie("sess")
                    request.path == "/api/session/list" -> {
                        bodies.add(request.body)
                        rpcResult("""{"items":[{"sessionId":"s1","title":"T"}]}""")
                    }
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            val sessions = runBlocking { DshSessionRepository(DshClient(server.endpoint)).listSessions() }

            assertEquals("s1", sessions.single().id)
            val envelope = JSONObject(bodies.single())
            assertEquals("client-request", envelope.optString("type"))
            assertEquals("session/list", envelope.optString("method"))
            assertTrue(envelope.has("rpcId"))
            val args = envelope.getJSONObject("payload").getJSONObject("args")
            assertTrue(args.has("_request"))
        }
    }

    @Test
    fun promptBuildsContentAndReturnsStableRequestId() {
        FakeDshServer().use { server ->
            val bodies = Collections.synchronizedList(mutableListOf<String>())
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) -> FakeDshServer.cookie("sess")
                    request.path == "/api/session/prompt" -> {
                        bodies.add(request.body)
                        rpcResult("{}")
                    }
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            val id = runBlocking {
                DshSessionRepository(DshClient(server.endpoint)).prompt(
                    sessionId = "s1",
                    text = "你好",
                    requestId = "rid-1",
                    timeZone = "Asia/Shanghai",
                    fileReceiptIds = listOf("receipt-a"),
                )
            }

            assertEquals("rid-1", id)
            val sent = JSONObject(bodies.single())
                .getJSONObject("payload").getJSONObject("args").getJSONObject("request")
            assertEquals("s1", sent.optString("sessionId"))
            assertEquals(DshSessionRepository.PROMPT_MODE_QUEUE, sent.optString("mode"))
            assertEquals("Asia/Shanghai", sent.optString("clientTimeZone"))
            val content = sent.getJSONArray("content")
            assertEquals("你好", content.getJSONObject(0).optString("text"))
            assertEquals("receipt-a", content.getJSONObject(1).optString("receiptId"))
        }
    }

    @Test
    fun promptRejectsBlankTextWithoutAttachmentsBeforeAnyRequest() {
        val repository = DshSessionRepository(DshClient(RuntimeEndpoint("http://127.0.0.1:1/", "t")))
        val failure = runCatching {
            runBlocking { repository.prompt("s1", "   ") }
        }.exceptionOrNull()
        assertTrue("空白消息应被本地拦下：$failure", failure is IllegalArgumentException)
    }

    @Test
    fun cancelAndUpdateQueueUseExpectedPathsAndActions() {
        FakeDshServer().use { server ->
            val seen = Collections.synchronizedList(mutableListOf<String>())
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) -> FakeDshServer.cookie("sess")
                    request.path.startsWith("/api/session/") -> {
                        seen.add(request.path + " " + request.body)
                        rpcResult("{}")
                    }
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            runBlocking {
                val repository = DshSessionRepository(DshClient(server.endpoint))
                repository.cancel("s1")
                repository.updateQueue("s1", "item-1", editText = "改一下")
            }

            assertEquals(2, seen.size)
            assertTrue(seen[0].startsWith("/api/session/cancel "))
            assertTrue(seen[1].startsWith("/api/session/updateQueue "))
            val action = JSONObject(seen[1].substringAfter(' '))
                .getJSONObject("payload").getJSONObject("args").getJSONObject("request")
                .getJSONObject("action")
            assertEquals("edit", action.optString("kind"))
            assertEquals("改一下", action.getJSONArray("content").getJSONObject(0).optString("text"))
        }
    }

    /**
     * 排队消息的操作面（对齐官方 `session/updateQueue`，dsh-client-ui-conversation
     * 的 steerQueue / QueueDock：`{kind:'steer'}` 插话、`{kind:'remove'}` 撤回）。
     * edit 已在上面锁过，这里补 steer / remove 两个动作的形状。
     */
    @Test
    fun updateQueueSteerAndRemoveActions() {
        FakeDshServer().use { server ->
            val seen = Collections.synchronizedList(mutableListOf<String>())
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) -> FakeDshServer.cookie("sess")
                    request.path == "/api/session/updateQueue" -> {
                        seen.add(request.body)
                        rpcResult("{}")
                    }
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            runBlocking {
                val repository = DshSessionRepository(DshClient(server.endpoint))
                repository.updateQueue("s1", "item-1", steer = true)
                repository.updateQueue("s1", "item-2", remove = true)
            }

            assertEquals(2, seen.size)
            val actions = seen.map { body ->
                JSONObject(body).getJSONObject("payload").getJSONObject("args").getJSONObject("request")
            }
            assertEquals("item-1", actions[0].optString("itemId"))
            assertEquals("steer", actions[0].getJSONObject("action").optString("kind"))
            assertEquals("item-2", actions[1].optString("itemId"))
            assertEquals("remove", actions[1].getJSONObject("action").optString("kind"))
        }
    }

    @Test
    fun listProvidersKeepsArrayShapedValue() {
        FakeDshServer().use { server ->
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) -> FakeDshServer.cookie("sess")
                    request.path == "/api/llm/listProviders" ->
                        rpcResult("""[{"id":"p1","name":"Provider One"}]""")
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            val providers = runBlocking { DshSessionRepository(DshClient(server.endpoint)).listProviders() }

            assertEquals(listOf(DshProvider("p1", "Provider One")), providers)
        }
    }

    @Test
    fun createSessionOmitsUnsetOptionalFields() {
        FakeDshServer().use { server ->
            val bodies = Collections.synchronizedList(mutableListOf<String>())
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) -> FakeDshServer.cookie("sess")
                    request.path == "/api/session/create" -> {
                        bodies.add(request.body)
                        rpcResult("""{"sessionId":"new-1"}""")
                    }
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            val id = runBlocking {
                DshSessionRepository(DshClient(server.endpoint)).createSession(workspaceId = "ws-1")
            }

            assertEquals("new-1", id)
            val sent = JSONObject(bodies.single())
                .getJSONObject("payload").getJSONObject("args").getJSONObject("request")
            assertEquals("ws-1", sent.optString("workspaceId"))
            assertTrue(!sent.has("cwd"))
            assertTrue(!sent.has("agentPreset"))
        }
    }
}
