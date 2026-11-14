package com.dshm.bot.data.remote

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `DsBotCapabilities` 的协商行为：成功 / 失败 / 换端点 / 能力裁决。
 *
 * 用 `FakeDshServer` 真实走一遍 `POST /api/bot` 的 `hello` 请求，
 * 锁住「生产链路接线」这件事本身（以前只有模型和单测，没人调用）。
 */
class DsBotCapabilitiesTest {

    private fun helloValue() = """
        {"api":1,"apiMin":1,"apiMax":1,
         "plugin":{"name":"ds-bot","version":"0.2.5"},
         "rpc":{"call":"dsBot/call","feed":"dsBot/feed"},
         "capabilities":["state","chat","room-chat","usage-summary","notices"],
         "boot":"boot-1","seq":42,"revision":7,"memory":true,
         "feed":"dsBot/feed"}
    """.trimIndent()

    private fun respond(endpoint: String, helloResponse: String): FakeDshServer =
        FakeDshServer().apply {
            responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) ->
                        FakeDshServer.cookie("abc")

                    request.path == "/api/bot" -> {
                        val requested = JSONObject(request.body).optString("endpoint")
                        val value = if (requested == "hello") helloResponse else "{}"
                        FakeDshServer.Response(200, body = """{"ok":true,"value":$value}""")
                    }

                    else -> FakeDshServer.Response(404)
                }
            }
        }

    @Test
    fun noHelloYetDisablesAllCapabilities() {
        DsBotCapabilities.clear()
        assertFalse(DsBotCapabilities.supports("room-chat"))
        assertNull(DsBotCapabilities.noticeCursor())
    }

    @Test
    fun refreshNegotiatesAndGatesCapabilities() {
        DsBotCapabilities.clear()
        val ok = FakeDshServer.Response(200, body = """{"ok":true,"value":${helloValue()}}""")
        respond("unused", helloValue()).use { server ->
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) ->
                        FakeDshServer.cookie("abc")

                    request.path == "/api/bot" -> {
                        val requested = JSONObject(request.body).optString("endpoint")
                        if (requested == "hello") ok
                        else FakeDshServer.Response(200, body = """{"ok":true,"value":{}}""")
                    }

                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()
            runBlocking {
                DsBotCapabilities.refresh(server.endpoint, DshClient(server.endpoint))

                assertTrue(DsBotCapabilities.supports("room-chat"))
                assertTrue(DsBotCapabilities.supports("usage-summary"))
                assertFalse("未知能力不该通过", DsBotCapabilities.supports("does-not-exist"))
                // 旧 hello 的 capabilities 没有 timeline：Phase 1 主开关默认关。
                assertFalse(DsBotCapabilities.timelineSupported())
                assertEquals("boot-1" to 42L, DsBotCapabilities.noticeCursor())

                val helloRequest = server.requests().first { it.path == "/api/bot" }
                val body = JSONObject(helloRequest.body)
                assertEquals("hello", body.getString("endpoint"))
                assertEquals(1, body.getJSONObject("payload").getJSONArray("api").getInt(0))
            }
        }
        DsBotCapabilities.clear()
    }

    @Test
    fun failedNegotiationDisablesCapabilitiesWithoutThrowing() {
        DsBotCapabilities.clear()
        FakeDshServer().use { server ->
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) ->
                        FakeDshServer.cookie("abc")

                    // 旧版插件没有 hello → bot/not-found
                    request.path == "/api/bot" -> FakeDshServer.Response(
                        200,
                        body = """{"ok":false,"error":{"code":"bot/not-found"}}""",
                    )

                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()
            runBlocking {
                // 不抛、能力全关。
                DsBotCapabilities.refresh(server.endpoint, DshClient(server.endpoint))
                assertFalse(DsBotCapabilities.supports("room-chat"))
                assertNull(DsBotCapabilities.noticeCursor())
            }
        }
        DsBotCapabilities.clear()
    }

    /**
     * Phase 1a：宿主 hello 声明 `timeline` 能力 → 灰度主开关打开（设计 §1/§6）。
     */
    @Test
    fun timelineCapabilityGatesTheProjectionSwitch() {
        DsBotCapabilities.clear()
        val hello = JSONObject(helloValue()).put(
            "capabilities",
            org.json.JSONArray(listOf("state", "chat", "timeline")),
        )
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
            runBlocking {
                DsBotCapabilities.refresh(server.endpoint, DshClient(server.endpoint))
                assertTrue(DsBotCapabilities.supports(DsBotCapabilities.CAPABILITY_TIMELINE))
                assertTrue(DsBotCapabilities.timelineSupported())
            }
        }
        DsBotCapabilities.clear()
    }

    @Test
    fun changingEndpointClearsOldResult() {
        DsBotCapabilities.clear()
        val ok = FakeDshServer.Response(200, body = """{"ok":true,"value":${helloValue()}}""")
        FakeDshServer().use { server ->
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) ->
                        FakeDshServer.cookie("abc")

                    request.path == "/api/bot" -> ok
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()
            runBlocking {
                DsBotCapabilities.refresh(server.endpoint, DshClient(server.endpoint))
                assertTrue(DsBotCapabilities.supports("room-chat"))

                // 新端点（不同 token）= 新引擎，不能沿用上一次的能力。
                val other = server.endpoint.copy(token = "another-token")
                val failing = FakeDshServer().apply {
                    responder = { request ->
                        when {
                            request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) ->
                                FakeDshServer.cookie("abc")

                            request.path == "/api/bot" -> FakeDshServer.Response(
                                200,
                                body = """{"ok":false,"error":{"code":"bot/not-found"}}""",
                            )

                            else -> FakeDshServer.Response(404)
                        }
                    }
                }
                failing.use {
                    it.start()
                    DsBotCapabilities.refresh(other, DshClient(it.endpoint.copy(token = "another-token")))
                    assertFalse("换端点后不该沿用旧能力", DsBotCapabilities.supports("room-chat"))
                }
            }
        }
        DsBotCapabilities.clear()
    }
}
