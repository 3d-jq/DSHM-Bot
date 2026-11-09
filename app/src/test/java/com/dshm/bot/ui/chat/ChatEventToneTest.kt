package com.dshm.bot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** 事件行分级 + 失败码文案（对齐 DS Bot `client.js` 的 `FAILURES`）。 */
class ChatEventToneTest {

    @Test
    fun failuresAreErrors() {
        assertEquals(ChatEventTone.ERROR, chatEventTone("failed"))
        assertEquals(ChatEventTone.ERROR, chatEventTone("missed"))
        assertEquals(ChatEventTone.ERROR, chatEventTone("cut"))
    }

    @Test
    fun ordinaryEventsStayNormal() {
        listOf(
            "request", "reply", "report", "stopped", "done", "working",
            "approval", "lookup", "resumed", "schedule", null,
        ).forEach { kind ->
            assertEquals("kind=$kind", ChatEventTone.NORMAL, chatEventTone(kind))
        }
    }

    @Test
    fun failureCodesReadInChinese() {
        assertEquals("API 密钥无效", failureLabel("AUTH", "ignored"))
        assertEquals("账户额度已用尽", failureLabel("QUOTA", ""))
        assertEquals("账户额度已用尽", failureLabel("ACCOUNT_QUOTA", ""))
        assertEquals("连不上模型服务", failureLabel("TRANSPORT", ""))
        assertEquals("模型响应超时", failureLabel("TIMEOUT", ""))
        assertEquals("它的群聊会话打不开，下次会换一个新的", failureLabel("SESSION_RESET", ""))
    }

    @Test
    fun unknownCodesFallBackToTheOriginalText() {
        assertEquals("socket closed", failureLabel("WEIRD", "socket closed"))
        assertEquals("WEIRD", failureLabel("WEIRD", ""))
        assertEquals("未知错误", failureLabel(null, ""))
    }
}
