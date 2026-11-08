package com.dshm.bot.ui.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolEventLineTest {

    private fun line(
        tool: String,
        args: JSONObject = JSONObject(),
        result: String = "",
        isError: Boolean = false,
    ) = toolEventLine(tool, args, result, isError, selfName = "大总管")

    @Test
    fun `message_bot names the target and carries it for the avatar`() {
        val sent = line("message_bot", JSONObject().put("to", "小助手"))
        assertEquals("消息发给了 小助手", sent?.label)
        assertEquals("小助手", sent?.target)

        val failed = line("message_bot", JSONObject().put("to", "小助手"), isError = true)
        assertEquals("没能给 小助手 发消息", failed?.label)
        assertNull(failed?.target)
    }

    @Test
    fun `message_bot without a target falls back to a generic bot`() {
        val sent = line("message_bot")
        assertEquals("消息发给了其他 Bot", sent?.label)
        assertNull(sent?.target)
    }

    @Test
    fun `stop_bot and create_bot point at their target`() {
        assertEquals("叫停了 小助手", line("stop_bot", JSONObject().put("bot", "小助手"))?.label)
        assertEquals(
            "小助手",
            line("stop_bot", JSONObject().put("bot", "小助手"))?.target,
        )
        val created = line("create_bot", JSONObject().put("name", "研究员"))
        assertEquals("创建了 研究员", created?.label)
        assertEquals("研究员", created?.target)
    }

    @Test
    fun `update_bot without a bot argument updates itself`() {
        val updated = line("update_bot", JSONObject())
        assertEquals("更新了 大总管", updated?.label)
        assertEquals("大总管", updated?.target)

        val other = line("update_bot", JSONObject().put("name", "小助手"))
        assertEquals("更新了 小助手", other?.label)
    }

    @Test
    fun `read_group_chat shows the group it read`() {
        val read = line("read_group_chat", JSONObject().put("group", "团队小群"))
        assertEquals("阅读了群聊「团队小群」", read?.label)
        assertEquals("团队小群", read?.target)
    }

    @Test
    fun `group tools read the room name out of the result`() {
        val posted = line(
            "post_to_group",
            JSONObject().put("group", "团队小群"),
            result = """Posted in "团队小群" (1 message)""",
        )
        assertEquals("在 团队小群 发言", posted?.label)
        assertEquals("团队小群", posted?.target)

        val created = line(
            "create_group",
            result = """Created group "产品群" with 2 bots""",
        )
        assertEquals("创建了群聊：产品群", created?.label)
        assertEquals("产品群", created?.target)
    }

    @Test
    fun `unknown tools draw nothing`() {
        assertNull(line("bash"))
        assertNull(line("web_search"))
    }
}
