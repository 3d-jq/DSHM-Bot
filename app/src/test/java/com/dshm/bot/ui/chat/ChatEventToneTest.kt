package com.dshm.bot.ui.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        // 文案对齐官方 DS Bot（`diagnostics.js` 的 FAILURES 表 + `i18n.js` 译文）。
        assertEquals("API 密钥无效", failureLabel("AUTH", "ignored"))
        assertEquals("API 密钥无效", failureLabel("INVALID_CREDENTIAL", ""))
        assertEquals("缺少 API 密钥", failureLabel("MISSING_CREDENTIAL", ""))
        assertEquals("账户额度已用尽", failureLabel("QUOTA", ""))
        assertEquals("账户额度已用尽", failureLabel("ACCOUNT_QUOTA", ""))
        assertEquals("模型请求太频繁", failureLabel("RATE_LIMIT", ""))
        assertEquals("连不上模型服务", failureLabel("TRANSPORT", ""))
        assertEquals("模型响应超时", failureLabel("TIMEOUT", ""))
        assertEquals("模型服务出错", failureLabel("SERVER", ""))
        assertEquals("模型服务出错", failureLabel("HTTP_503", ""))
        assertEquals("模型没有返回内容", failureLabel("EMPTY_RESPONSE", ""))
        assertEquals("这个模型需要新版引擎", failureLabel("CLIENT_UPGRADE_REQUIRED", ""))
        assertEquals("当前模型不可用", failureLabel("MODEL_UNSUPPORTED", ""))
        assertEquals("它的群聊会话打不开，下次会换一个新的", failureLabel("SESSION_RESET", ""))
    }

    @Test
    fun unknownCodesFallBackToTheOriginalText() {
        assertEquals("socket closed", failureLabel("WEIRD", "socket closed"))
        assertEquals("WEIRD", failureLabel("WEIRD", ""))
        assertEquals("未知错误", failureLabel(null, ""))
    }

    /** `turn/end` 的 `reason.kind == "error"` 才是运行失败（重试耗尽 / 不可重试）。 */
    @Test
    fun turnEndErrorReadsCodeAndMessage() {
        val (code, message) = turnEndError(
            JSONObject()
                .put("kind", "error")
                .put(
                    "error",
                    JSONObject().put("code", "QUOTA").put("message", "balance exhausted"),
                ),
        )!!
        assertEquals("QUOTA", code)
        assertEquals("balance exhausted", message)
    }

    /** 失败但没有细节（没有 error 对象）也要算失败：code / message 都是 null。 */
    @Test
    fun turnEndErrorWithoutDetailsStillFails() {
        val (code, message) = turnEndError(JSONObject().put("kind", "error"))!!
        assertNull(code)
        assertNull(message)
    }

    /** completed / aborted 不是运行失败；reason 缺失也不是。 */
    @Test
    fun nonErrorReasonsAreNotFailures() {
        assertNull(turnEndError(JSONObject().put("kind", "completed")))
        assertNull(turnEndError(JSONObject().put("kind", "aborted")))
        assertNull(turnEndError(JSONObject()))
        assertNull(turnEndError(null))
    }
}
