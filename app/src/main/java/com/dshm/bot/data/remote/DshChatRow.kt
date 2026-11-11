package com.dshm.bot.data.remote

import org.json.JSONObject

/**
 * 把 DSH 持久日志事件映射成「一行可渲染内容」的纯逻辑层。
 *
 * 对齐 dsh-Go `models.dart` 的 SessionLogEvent 分派与 `transcript.dart` 的展示语义：
 * 思考块从助手消息里拆成独立行（UI 折叠显示），工具调用 / 审批 / 提问各自成行，
 * 未知事件保留 raw 而不是丢掉 —— 这是「服务端下发的卡片静默消失」的根治点。
 *
 * 这一层不依赖 Android / Compose，可用 JVM 单测完整覆盖；Composable 只负责按行渲染。
 */
sealed interface DshChatRow {
    data class UserMessage(val seq: Long, val parts: List<DshPart>) : DshChatRow
    data class AssistantMessage(
        val seq: Long,
        val parts: List<DshPart>,
        val usage: JSONObject?,
        val interrupted: Boolean,
    ) : DshChatRow
    data class Reasoning(val seq: Long, val text: String) : DshChatRow
    data class ToolCall(val seq: Long, val callId: String, val name: String, val arguments: String) : DshChatRow
    data class ToolResult(val seq: Long, val parts: List<DshPart>, val error: JSONObject?) : DshChatRow
    data class ApprovalAsked(
        val seq: Long,
        val approvalId: String,
        val toolName: String,
        val callId: String?,
        val reason: String?,
    ) : DshChatRow
    data class Question(val seq: Long, val raw: JSONObject) : DshChatRow
    data class Event(val seq: Long, val type: String, val raw: JSONObject) : DshChatRow
    data class Unknown(val seq: Long, val type: String, val raw: JSONObject) : DshChatRow

    companion object {
        /** 已识别但只需折叠成活动行的事件；其余新类型落到 [Unknown]。 */
        private val FOLDED_EVENTS = setOf(
            "turn/start", "turn/end", "step/start", "step/end",
            "session/title", "model/selection", "assistant/attempt",
            "approval/decided", "approval/policy", "permission/preset", "sandbox/mode",
            "agent/inbox/spliced", "todo/write", "goal/change",
        )

        fun fromEvent(event: DshLogEvent): List<DshChatRow> {
            val data = event.data
            return when (event.type) {
                "user/message" -> listOf(UserMessage(event.seq, partsOf(data)))
                "assistant/message" -> expandAssistant(event, data)
                "tool/call" -> listOf(
                    ToolCall(
                        event.seq,
                        data.optString("callId"),
                        data.optString("name"),
                        data.optString("arguments"),
                    ),
                )
                "tool/result" -> listOf(
                    ToolResult(
                        event.seq,
                        partsOf(data.optJSONObject("message") ?: JSONObject()),
                        data.optJSONObject("error"),
                    ),
                )
                "approval/asked" -> listOf(
                    ApprovalAsked(
                        seq = event.seq,
                        approvalId = data.optString("id"),
                        toolName = data.optString("toolName"),
                        callId = data.optString("callId").takeIf { it.isNotEmpty() },
                        reason = data.optString("reason").takeIf { it.isNotEmpty() },
                    ),
                )
                "user-questions/request" -> listOf(Question(event.seq, data))
                in FOLDED_EVENTS -> listOf(Event(event.seq, event.type, event.raw))
                else -> listOf(Unknown(event.seq, event.type, event.raw))
            }
        }

        fun fromEvents(events: List<DshLogEvent>): List<DshChatRow> = events.flatMap(::fromEvent)

        private fun partsOf(message: JSONObject): List<DshPart> =
            DshPart.listFromJson(message.optJSONArray("parts") ?: message.optJSONArray("content"))

        private fun expandAssistant(event: DshLogEvent, data: JSONObject): List<DshChatRow> {
            val parts = partsOf(data.optJSONObject("message") ?: data)
            val rows = mutableListOf<DshChatRow>()
            parts.filterIsInstance<DshPart.Reasoning>().forEach {
                rows.add(Reasoning(event.seq, it.text))
            }
            val visible = parts.filterNot { it is DshPart.Reasoning }
            rows.add(
                AssistantMessage(
                    seq = event.seq,
                    parts = visible,
                    usage = data.optJSONObject("usage"),
                    interrupted = data.optBoolean("interrupted", false),
                ),
            )
            return rows
        }
    }
}

/** 一条消息的多个 Part 合成预览文本，供列表/通知使用。 */
fun List<DshPart>.previewText(): String = buildString {
    this@previewText.forEach { part ->
        when (part) {
            is DshPart.Text -> append(part.text)
            is DshPart.Reasoning -> Unit
            is DshPart.Image -> append("[图片]")
            is DshPart.File -> append("[文件]")
            is DshPart.ToolCall -> append("[工具 ${part.name}]")
            is DshPart.Unknown -> Unit
        }
    }
}.trim()
