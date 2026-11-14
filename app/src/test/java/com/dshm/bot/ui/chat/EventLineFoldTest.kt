package com.dshm.bot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** 连续相同事件行折叠成「 ×N」：retry 重灾区的堆叠修复（有意偏离官方的一条一行）。 */
class EventLineFoldTest {

    private fun event(
        id: String,
        kind: String? = "retry",
        text: String = "已要求重试写成文本的工具调用",
        author: String? = "小助手",
        target: String? = null,
        tool: String? = null,
    ) = ChatEntry(
        id = id,
        kind = ChatEntryKind.EVENT,
        text = text,
        author = author,
        eventKind = kind,
        target = target,
        tool = tool,
    )

    private fun bubble(id: String, text: String) =
        ChatEntry(id = id, kind = ChatEntryKind.BOT, text = text)

    @Test
    fun `连续相同的 retry 行折叠成一条计数行`() {
        val display = foldRepeatedEventLines(
            listOf(bubble("b1", "好的"), event("e1"), event("e2"), event("e3")),
        )

        assertEquals(2, display.size)
        assertEquals("b1", display[0].entry.id)
        assertEquals(1, display[0].repeatCount)
        // 代表条目 = 组内第一条：同类事件继续追加时 LazyColumn key 不变，行原位更新。
        assertEquals("e1", display[1].entry.id)
        assertEquals(3, display[1].repeatCount)
    }

    @Test
    fun `单条事件行不加计数`() {
        val display = foldRepeatedEventLines(listOf(event("e1")))

        assertEquals(1, display.size)
        assertEquals(1, display[0].repeatCount)
    }

    @Test
    fun `文案不同打断折叠组`() {
        val display = foldRepeatedEventLines(
            listOf(
                event("e1", text = "已要求重试写成文本的工具调用"),
                event("e2", text = "X 开始做了", kind = "working"),
                event("e3", text = "已要求重试写成文本的工具调用"),
            ),
        )

        assertEquals(3, display.size)
        assertEquals(1, display[0].repeatCount)
        assertEquals(1, display[1].repeatCount)
        assertEquals(1, display[2].repeatCount)
    }

    @Test
    fun `中间隔了气泡就不算连续`() {
        val display = foldRepeatedEventLines(
            listOf(event("e1"), bubble("b1", "继续"), event("e2")),
        )

        assertEquals(3, display.size)
        assertEquals(1, display[0].repeatCount)
        assertEquals(1, display[2].repeatCount)
    }

    @Test
    fun `relay 行与带目标或工具的行不折叠`() {
        val display = foldRepeatedEventLines(
            listOf(
                event("r1", kind = "request", target = "其他 Bot", text = "消息来自 其他 Bot"),
                event("r2", kind = "request", target = "其他 Bot", text = "消息来自 其他 Bot"),
                event("m1", kind = null, tool = "remember", text = "记住了"),
                event("m2", kind = null, tool = "remember", text = "记住了"),
            ),
        )

        assertEquals(4, display.size)
        display.forEach { assertEquals("可点击/带图标的行折叠会藏掉去处", 1, it.repeatCount) }
    }

    @Test
    fun `不同作者的同名文案不折叠`() {
        val display = foldRepeatedEventLines(
            listOf(event("e1", author = "小助手"), event("e2", author = "深度君")),
        )

        assertEquals(2, display.size)
        assertEquals(1, display[0].repeatCount)
        assertEquals(1, display[1].repeatCount)
    }
}
