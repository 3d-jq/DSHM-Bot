package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.DshRpcException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DS Bot 错误的中文文案（对齐 dsh-Go 的 `describeDsBotError`）。
 */
class ChatErrorsTest {

    private fun rpc(code: String, message: String) =
        DshRpcException(code = code, message = message, details = null)

    @Test
    fun readOnlyTeamGetsItsOwnMessage() {
        // 引擎的原文很长很技术，用户看不懂 —— 要给中文。
        val text = describeChatError(
            rpc(
                "bot/read-only",
                "The Bot team in /root/.dsh/bot is open in another dsh " +
                    "(Web or Desktop, pid 23537 on localhost) This window shows the team " +
                    "but cannot change it; close the other one and reload",
            ),
        )
        assertTrue(text.contains("另一个 DSH"))
        // 原始英文不该直接甩给用户。
        assertTrue(!text.contains("pid 23537"))
    }

    @Test
    fun unknownEndpointsGetTheirOwnMessage() {
        assertEquals("没有这个功能或条目", describeChatError(rpc("bot/not-found", "Unknown endpoint x")))
    }

    @Test
    fun refusedKeepsTheHostsOwnWords() {
        // 宿主给的是面向用户的英文（比如 GitHub 不认这个 token），照原样给。
        val text = describeChatError(rpc("bot/refused", "GitHub did not accept this token"))
        assertEquals("GitHub did not accept this token", text)
    }

    @Test
    fun otherFailuresFallBackToTheMessage() {
        assertEquals("boom", describeChatError(rpc("other/code", "boom")))
        assertEquals("网络不通", describeChatError(java.io.IOException("网络不通")))
    }
}
