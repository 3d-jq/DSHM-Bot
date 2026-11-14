package com.dshm.bot.data.remote

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
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

    /** 重登一次之后仍 401：认证失效要带着原因抛出去，不能被吞成空结果。 */
    @Test
    fun reLoginExhaustedThrowsAuthExpired() {
        FakeDshServer().use { server ->
            val logins = AtomicInteger()
            val botCalls = AtomicInteger()
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) -> {
                        logins.incrementAndGet()
                        FakeDshServer.cookie("x${logins.get()}")
                    }
                    request.path == "/api/bot" -> {
                        botCalls.incrementAndGet()
                        FakeDshServer.Response(401, body = "expired")
                    }
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            val failure = runCatching {
                runBlocking { DshClient(server.endpoint).bot("state") }
            }.exceptionOrNull()

            assertTrue("重登后仍 401 要抛认证失效：$failure", failure != null)
            assertTrue(
                "错误信息要带上 HTTP 状态：$failure",
                failure!!.message.orEmpty().contains("认证已失效") && failure.message.orEmpty().contains("401"),
            )
            assertEquals("只重登一次", 2, logins.get())
            assertEquals("只重试一次", 2, botCalls.get())
        }
    }

    /** readFileBytes 拿原始字节，Cookie 要带上。 */
    @Test
    fun readFileBytesReturnsRawBytesWithCookie() {
        FakeDshServer().use { server ->
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) -> FakeDshServer.cookie("file")
                    request.path.startsWith("/api/file?path=") -> FakeDshServer.Response(200, body = "文件内容")
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            val bytes = runBlocking { DshClient(server.endpoint).readFileBytes("/tmp/你好.txt") }

            assertEquals("文件内容", String(bytes, Charsets.UTF_8))
            val fileRequest = server.requests().last { it.path.startsWith("/api/file?path=") }
            assertTrue("读文件必须带认证 cookie", fileRequest.cookie.orEmpty().startsWith("dsh-auth-file="))
        }
    }

    /** readFileBytes 的 404 要映射成业务错误码，别变成裸 require 消息。 */
    @Test
    fun readFileBytesMapsNotFoundToWorkspaceFileCode() {
        FakeDshServer().use { server ->
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) -> FakeDshServer.cookie("file")
                    request.path.startsWith("/api/file?path=") -> FakeDshServer.Response(404)
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            val failure = runCatching {
                runBlocking { DshClient(server.endpoint).readFileBytes("/tmp/missing.txt") }
            }.exceptionOrNull()

            assertTrue("404 应映射成 workspace-file/not-found：$failure", failure is DshRpcException)
            assertEquals("workspace-file/not-found", (failure as DshRpcException).code)
        }
    }

    /** readFileBytes 的 403 与其它路径对齐：重登一次，重登后成功就恢复。 */
    @Test
    fun readFileBytesReLogsOnForbiddenLikeOtherPaths() {
        FakeDshServer().use { server ->
            val logins = AtomicInteger()
            val fileCalls = AtomicInteger()
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) -> {
                        logins.incrementAndGet()
                        FakeDshServer.cookie("f${logins.get()}")
                    }
                    request.path.startsWith("/api/file?path=") -> {
                        fileCalls.incrementAndGet()
                        if (fileCalls.get() == 1) {
                            FakeDshServer.Response(403)
                        } else {
                            FakeDshServer.Response(200, body = "ok")
                        }
                    }
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            val bytes = runBlocking { DshClient(server.endpoint).readFileBytes("/tmp/a.txt") }

            assertEquals("ok", String(bytes, Charsets.UTF_8))
            assertEquals("403 要触发重登", 2, logins.get())
            assertEquals(2, fileCalls.get())
        }
    }

    /** uploadAttachment：octet-stream 原样到达 + receipt 解析。 */
    @Test
    fun uploadAttachmentPostsBinaryAndParsesReceipt() {
        FakeDshServer().use { server ->
            val payload = "PNGDATA" // ASCII，FakeDshServer 按 UTF-8 解码 body
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) -> FakeDshServer.cookie("up")
                    request.path.startsWith("/api/session/uploadFileBinary?") -> {
                        FakeDshServer.Response(
                            200,
                            body = JSONObject()
                                .put("ok", true)
                                .put(
                                    "value",
                                    JSONObject()
                                        .put("receiptId", "r-1")
                                        .put(
                                            "file",
                                            JSONObject()
                                                .put("attachmentId", "att-1")
                                                .put("name", "图.png")
                                                .put("bytes", 7),
                                        ),
                                )
                                .toString(),
                        )
                    }
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            val receipt = runBlocking {
                DshClient(server.endpoint).uploadAttachment("s-1", "图.png", payload.toByteArray(Charsets.UTF_8))
            }

            assertEquals("r-1", receipt.receiptId)
            assertEquals("att-1", receipt.attachmentId)
            assertEquals("图.png", receipt.name)
            assertEquals(7L, receipt.bytes)
            val upload = server.requests().last { it.path.startsWith("/api/session/uploadFileBinary") }
            assertEquals("POST", upload.method)
            assertTrue("sessionId 要进 query", upload.path.contains("sessionId=s-1"))
            assertTrue("name 要 URL 编码", upload.path.contains("name=%E5%9B%BE.png"))
            assertEquals("字节要原样到达", payload, upload.body)
            assertTrue("上传必须带认证 cookie", upload.cookie.orEmpty().startsWith("dsh-auth-up="))
        }
    }

    /**
     * speech/transcribe 的一跳契约：envelope 里带 request.audioBase64、大 body 完整到达、
     * text 从 result.value 提取。
     *
     * 注：`DshEngineSettingsRepository.transcribeSpeech` 本体用 android.util.Base64，
     * JVM 单测没 mock，只能真机跑；这里用 java.util.Base64 锁它下面的网络契约。
     */
    @Test
    fun transcribeRpcCarriesLargeBase64BodyAndReturnsText() {
        FakeDshServer().use { server ->
            val audio = ByteArray(64 * 1024) { (it % 251).toByte() }
            val audioBase64 = java.util.Base64.getEncoder().encodeToString(audio)
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) -> FakeDshServer.cookie("tts")
                    request.path == "/api/speech/transcribe" -> {
                        val envelope = JSONObject(request.body)
                        val inner = envelope
                            .getJSONObject("payload")
                            .getJSONObject("args")
                            .getJSONObject("request")
                        assertEquals("audioBase64 要原样到达", audioBase64, inner.getString("audioBase64"))
                        FakeDshServer.Response(
                            200,
                            body = JSONObject()
                                .put(
                                    "result",
                                    JSONObject()
                                        .put("ok", true)
                                        .put("value", JSONObject().put("text", "识别结果")),
                                )
                                .toString(),
                        )
                    }
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            val result = runBlocking {
                DshClient(server.endpoint).rpc(
                    "speech/transcribe",
                    JSONObject().put("request", JSONObject().put("audioBase64", audioBase64)),
                    timeoutMs = 180_000,
                )
            }

            assertEquals("识别结果", result.getString("text"))
        }
    }
}
