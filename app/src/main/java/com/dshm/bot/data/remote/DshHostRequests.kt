package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次待审批的请求（DSH 主机的 `approval/request`）。
 *
 * 规格照 dsh-Go `packages/dsh_client/src/models.dart` 的 `ApprovalRequest`。
 */
data class DshApprovalRequest(
    /** 主机事件 id —— 回执时必须带上它。 */
    val eventId: String,
    /** Agent id；普通会话里等于 session id。 */
    val sessionId: String,
    /** 要放行的工具名。 */
    val toolName: String,
    /** 被决定的 `tool/call` 的 id（如果有）。 */
    val callId: String? = null,
    /** 原因。 */
    val reason: String? = null,
    /** 按语言代码索引的展示文案（`en` 一定有）。 */
    val displayReason: Map<String, String> = emptyMap(),
    val raw: JSONObject = JSONObject(),
) {
    /** 最适合显示的文字：本地化文案 → 英文 → 原因 → 兜底。 */
    fun describe(locale: String = "zh"): String =
        displayReason[locale]
            ?: displayReason["en"]
            ?: reason
            ?: "允许 $toolName？"

    companion object {
        /** 从 waterfall 帧的 `request` 解析。 */
        fun fromWaterfall(eventId: String, agentId: String, request: JSONObject?): DshApprovalRequest {
            val map = request ?: JSONObject()
            val display = map.optJSONObject("displayReason") ?: JSONObject()
            val texts = mutableMapOf<String, String>()
            display.keys().forEach { key ->
                display.optString(key).takeIf { it.isNotEmpty() }?.let { texts[key] = it }
            }
            return DshApprovalRequest(
                eventId = eventId,
                sessionId = agentId,
                toolName = map.optString("toolName").ifEmpty { "tool" },
                callId = map.optString("callId").takeIf { it.isNotEmpty() },
                reason = map.optString("reason").takeIf { it.isNotEmpty() },
                displayReason = texts,
                raw = map,
            )
        }
    }
}

/** 提问里的一个选项。 */
data class DshQuestionOption(
    val label: String,
    val description: String? = null,
)

/** 一次提问。 */
data class DshUserQuestion(
    val id: String,
    val question: String,
    val detail: String? = null,
    val header: String? = null,
    val options: List<DshQuestionOption> = emptyList(),
    val multiSelect: Boolean = false,
    val intent: String? = null,
) {
    companion object {
        fun fromJson(json: JSONObject?): DshUserQuestion {
            val map = json ?: JSONObject()
            val options = mutableListOf<DshQuestionOption>()
            map.optJSONArray("options")?.let { array ->
                for (index in 0 until array.length()) {
                    val option = array.optJSONObject(index) ?: continue
                    options += DshQuestionOption(
                        label = option.optString("label"),
                        description = option.optString("description").takeIf { it.isNotEmpty() },
                    )
                }
            }
            return DshUserQuestion(
                id = map.optString("id"),
                question = map.optString("question"),
                detail = map.optString("detail").takeIf { it.isNotEmpty() },
                header = map.optString("header").takeIf { it.isNotEmpty() },
                options = options,
                multiSelect = map.optBoolean("multiSelect", false),
                intent = map.optString("intent").takeIf { it.isNotEmpty() },
            )
        }
    }
}

/**
 * 一次待回答的提问（DSH 主机的 `user-questions/request`）。
 *
 * 规格照 dsh-Go 的 `UserQuestionRequest`。
 */
data class DshQuestionRequest(
    val eventId: String,
    val sessionId: String,
    val questions: List<DshUserQuestion> = emptyList(),
    /** 问题卡对应的工具调用 id。 */
    val waitCallId: String? = null,
    /** 前台定时等待。 */
    val timed: Boolean = false,
    val raw: JSONObject = JSONObject(),
) {
    companion object {
        fun fromWaterfall(eventId: String, agentId: String, request: JSONObject?): DshQuestionRequest {
            val map = request ?: JSONObject()
            val wait = map.optJSONObject("wait")
            val questions = mutableListOf<DshUserQuestion>()
            map.optJSONArray("questions")?.let { array ->
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { questions += DshUserQuestion.fromJson(it) }
                }
            }
            return DshQuestionRequest(
                eventId = eventId,
                sessionId = agentId,
                questions = questions,
                waitCallId = wait?.optString("callId")?.takeIf { it.isNotEmpty() },
                timed = wait?.optBoolean("timed", false) ?: false,
                raw = map,
            )
        }
    }
}

/** 一次提问的回答（对应一个问题的选项）。 */
data class DshQuestionAnswer(
    val questionId: String,
    val optionLabel: String,
)

/** 把回答打包成主机要的形状。 */
internal fun answersToJson(answers: List<DshQuestionAnswer>): JSONArray {
    val array = JSONArray()
    answers.forEach { answer ->
        array.put(
            JSONObject()
                .put("questionId", answer.questionId)
                .put("option", answer.optionLabel),
        )
    }
    return array
}
