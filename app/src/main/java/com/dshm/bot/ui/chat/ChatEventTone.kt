package com.dshm.bot.ui.chat

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
fun failureLabel(code: String?, fallback: String): String {
    val mapped = when (code) {
        "AUTH" -> "API 密钥无效"
        "QUOTA", "ACCOUNT_QUOTA" -> "账户额度已用尽"
        "RATE_LIMIT" -> "模型请求太频繁"
        "CONTEXT_WINDOW_EXCEEDED" -> "对话太长，超出模型上限"
        "INVALID_REQUEST" -> "模型服务拒绝了请求"
        "SERVER" -> "模型服务出错"
        "TIMEOUT" -> "模型响应超时"
        "TRANSPORT" -> "连不上模型服务"
        "SESSION" -> "它的群聊会话打不开"
        "SESSION_RESET" -> "它的群聊会话打不开，下次会换一个新的"
        else -> null
    }
    if (mapped != null) return mapped
    return fallback.takeIf { it.isNotBlank() }
        ?: code?.takeIf { it.isNotBlank() }
        ?: "未知错误"
}
