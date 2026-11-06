package com.dshm.bot.data.remote

import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DS Bot 移动端 hello 能力协商与 notices 补读。 */
class DsBotHelloTest {

    private fun helloJson(api: String): String = """
        {
          "api": $api,
          "apiMin": 1,
          "apiMax": 1,
          "plugin": {"name": "ds-bot", "version": "0.2.1"},
          "capabilities": ["state", "chat", "room-chat", "notices", "avatar"],
          "boot": "boot-1",
          "seq": 7,
          "revision": 9,
          "readOnly": null,
          "memory": true,
          "feed": "api/bot/feed",
          "rpc": {"call": "dsBot/call", "feed": "dsBot/feed"}
        }
    """.trimIndent()

    @Test
    fun parsesHelloAndCapabilities() {
        val hello = DsBotHello.fromJson(JSONObject(helloJson("1")))
        assertEquals(1, hello.api)
        assertEquals(1, hello.apiMin)
        assertEquals(1, hello.apiMax)
        assertEquals("ds-bot", hello.pluginName)
        assertEquals("0.2.1", hello.pluginVersion)
        assertEquals("boot-1", hello.boot)
        assertEquals(7L, hello.seq)
        assertEquals(9L, hello.revision)
        assertNull(hello.readOnly)
        assertTrue(hello.memory)
        assertEquals("api/bot/feed", hello.feed)
        assertEquals("dsBot/call", hello.rpcCall)
        assertEquals("dsBot/feed", hello.rpcFeed)
        assertTrue(hello.supports("room-chat"))
        assertFalse(hello.supports("not-a-capability"))
    }

    @Test
    fun incompatibleApiDisablesAllCapabilities() {
        val hello = DsBotHello.fromJson(JSONObject(helloJson("null")))
        assertNull(hello.api)
        assertFalse("协商失败时不应假装支持移动端能力", hello.supports("state"))
    }

    @Test
    fun noticesPageParsesCursorResetAndItems() {
        val page = DsBotNoticePage.fromJson(
            JSONObject(
                """
                {
                  "boot": "boot-2", "seq": 12, "reset": true,
                  "notices": [
                    {"seq": 11, "time": 1700000000000, "kind": "approval", "botId": "b1", "title": "等确认", "text": "允许运行命令？"},
                    {"seq": 12, "time": 1700000001000, "kind": "message", "text": "完成了"}
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertEquals("boot-2", page.boot)
        assertEquals(12L, page.seq)
        assertTrue(page.reset)
        assertEquals(2, page.notices.size)
        assertEquals("approval", page.notices[0].kind)
        assertEquals("b1", page.notices[0].botId)
        assertEquals("允许运行命令？", page.notices[0].text)
        assertNull(page.notices[1].botId)
    }

    @Test
    fun helloAndNoticesUseTheBotEndpointWithCorrectPayload() {
        FakeDshServer().use { server ->
            val bodies = Collections.synchronizedList(mutableListOf<String>())
            server.responder = { request ->
                when {
                    request.path.startsWith(FakeDshServer.LOGIN_PATH_PREFIX) -> FakeDshServer.cookie("hello")
                    request.path == "/api/bot" -> {
                        bodies.add(request.body)
                        val endpoint = JSONObject(request.body).optString("endpoint")
                        val value = if (endpoint == "hello") {
                            helloJson("1")
                        } else {
                            """{"boot":"boot-1","seq":7,"reset":false,"notices":[]}"""
                        }
                        FakeDshServer.Response(200, body = """{"ok":true,"value":$value}""")
                    }
                    else -> FakeDshServer.Response(404)
                }
            }
            server.start()

            runBlocking {
                val repository = DsBotRepository(DshClient(server.endpoint))
                val hello = repository.hello()
                assertEquals(1, hello.api)
                repository.hello(listOf(1)) // 显式声明版本
                val page = repository.notices(boot = "boot-1", since = 6L)
                assertEquals(7L, page.seq)
            }

            assertEquals(3, bodies.size)
            val first = JSONObject(bodies[0])
            assertEquals("hello", first.optString("endpoint"))
            assertFalse("未传 offeredApi 时不应写死协议号", first.getJSONObject("payload").has("api"))
            val second = JSONObject(bodies[1]).getJSONObject("payload")
            assertEquals(1, second.getJSONArray("api").optInt(0))
            val third = JSONObject(bodies[2])
            assertEquals("notices", third.optString("endpoint"))
            assertEquals("boot-1", third.getJSONObject("payload").optString("boot"))
            assertEquals(6L, third.getJSONObject("payload").optLong("since"))
        }
    }
}
