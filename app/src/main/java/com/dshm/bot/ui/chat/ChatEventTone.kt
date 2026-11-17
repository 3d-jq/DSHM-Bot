package com.dshm.bot.ui.chat

import org.json.JSONObject

/** 事件行的语气：`ERROR` 用危险色，跟普通事件行（灰字）区分开。 */
enum class ChatEventTone { NORMAL, ERROR }

/**
 * 哪些事件是「出事了」。
 *
 * 对齐 DS Bot：群里 `failed` 是 `bt-event-error`（红），`missed` / `cut` 也是
 * 「没能回答 / 被打断」，同样按异常处理；`done / working / stopped / lookup / resumed /
 * approval / request / reply / report` 是普通事件行。
 */
fun chatEventTone(kind: String?): ChatEventTone = when (kind) {
    "failed", "missed", "cut", "paused" -> ChatEventTone.ERROR
    else -> ChatEventTone.NORMAL
}

/**
 * 模型调用失败的码 → 人话，对齐 DS Bot `client.js` 的 `FAILURES`。
 *
 * 拿不到映射就用原文，再没有就用「未知错误」。
 */
/**
 * 失败码 → 人话。正文照官方 `DS-Bot/ds-bot/src/client/diagnostics.js` 的 `FAILURES`
 * 表 + `i18n.js` 的译文（用户 2026-11-16 要求逐条对齐）；表里没有的码用我们补的措辞。
 */
fun failureLabel(code: String?, fallback: String): String {
    val mapped = when (code) {
        "AUTH", "INVALID_CREDENTIAL" -> "API 密钥无效"
        "MISSING_CREDENTIAL" -> "缺少 API 密钥"
        "QUOTA" -> "账户额度已用尽"
        "ACCOUNT_QUOTA" -> "账户额度已用尽"
        "RATE_LIMIT" -> "模型请求太频繁"
        "CONTEXT_WINDOW_EXCEEDED" -> "对话太长，超出模型上限"
        "INVALID_REQUEST" -> "模型服务拒绝了请求"
        "SERVER" -> "模型服务出错"
        "TIMEOUT" -> "模型响应超时"
        "TRANSPORT" -> "连不上模型服务"
        "EMPTY_RESPONSE" -> "模型没有返回内容"
        "CLIENT_UPGRADE_REQUIRED" -> "这个模型需要新版引擎"
        "MODEL_UNSUPPORTED" -> "当前模型不可用"
        "SESSION" -> "它的群聊会话打不开"
        "SESSION_RESET" -> "它的群聊会话打不开，下次会换一个新的"
        else -> null
    }
    if (code != null && code.startsWith("HTTP_5")) return "模型服务出错"
    if (mapped != null) return mapped
    return fallback.takeIf { it.isNotBlank() }
        ?: code?.takeIf { it.isNotBlank() }
        ?: "未知错误"
}

/**
 * `turn/end` 的 `reason` 里的**最终失败**信息。
 *
 * 模型请求重试耗尽（或错误码不可重试）时，引擎记的是
 * `turn/end { reason: { kind: "error", error: { code, message } } }`
 * （`dsh-agent-loop` 的 catch 分支）—— 之前我们对 `turn/end` 只记 seq，
 * 这条失败**整条静默**，表现就是「运行失败在界面上看不到」。
 *
 * 返回 `(code, message)`；不是以错误结尾（completed / aborted…）返回 null。
 */
internal fun turnEndError(reason: JSONObject?): Pair<String?, String?>? {
    if (reason == null || reason.optString("kind") != "error") return null
    val failure = reason.optJSONObject("error")
    val code = failure?.optString("code")?.takeIf { it.isNotEmpty() }
    val message = failure?.optString("message")?.takeIf { it.isNotEmpty() }
    return code to message
}
