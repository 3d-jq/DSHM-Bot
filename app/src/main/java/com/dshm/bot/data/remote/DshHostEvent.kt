package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * DSH 内部事件流的帧模型，对齐 dsh-Go `models.dart` 的 `HostEvent.fromFrame`。
 *
 * 帧类型：ready / emit / waterfall / cancel。未知类型保留 raw，
 * 供 UI 决定「折叠成一行」还是「渲染成新卡片」，不静默丢弃。
 */
sealed interface DshHostEvent {
    data class Ready(val clientId: String, val home: String, val raw: JSONObject) : DshHostEvent
    data class Emit(val event: String, val args: JSONArray, val raw: JSONObject) : DshHostEvent
    data class Waterfall(
        val event: String,
        val eventId: String,
        val agentId: String,
        val request: JSONObject,
        val raw: JSONObject,
    ) : DshHostEvent
    data class Cancelled(val eventId: String, val raw: JSONObject) : DshHostEvent
    data class Unknown(val type: String, val raw: JSONObject) : DshHostEvent

    companion object {
        const val APPROVAL_REQUEST = "approval/request"
        const val USER_QUESTIONS_REQUEST = "user-questions/request"

        fun fromFrame(json: Any?): DshHostEvent {
            val frame = json as? JSONObject ?: return Unknown("", JSONObject())
            return when (frame.optString("type")) {
                "ready" -> Ready(
                    clientId = frame.optString("clientId"),
                    home = frame.optJSONObject("host")?.optString("home") ?: "",
                    raw = frame,
                )
                "emit" -> Emit(
                    event = frame.optString("event"),
                    args = frame.optJSONArray("args") ?: JSONArray(),
                    raw = frame,
                )
                "waterfall" -> Waterfall(
                    event = frame.optString("event"),
                    eventId = frame.optString("eventId"),
                    agentId = frame.optString("agentId"),
                    request = frame.optJSONObject("request") ?: JSONObject(),
                    raw = frame,
                )
                "cancel" -> Cancelled(frame.optString("eventId"), frame)
                else -> Unknown(frame.optString("type"), frame)
            }
        }
    }
}

/** waterfall 里需要用户回应的请求种类。 */
enum class DshWaterfallKind { APPROVAL, USER_QUESTIONS, OTHER }

val DshHostEvent.Waterfall.kind: DshWaterfallKind
    get() = when (event) {
        DshHostEvent.APPROVAL_REQUEST -> DshWaterfallKind.APPROVAL
        DshHostEvent.USER_QUESTIONS_REQUEST -> DshWaterfallKind.USER_QUESTIONS
        else -> DshWaterfallKind.OTHER
    }
