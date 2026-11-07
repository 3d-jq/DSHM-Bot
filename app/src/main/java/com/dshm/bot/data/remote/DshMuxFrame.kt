package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * `/api/remote.mux` 的帧协议（一条 WebSocket 文本消息 = 一帧）。
 *
 * ```text
 * client -> host  {"type":"open","streamId":"<uuid>","endpoint":"session/follow","payload":{"args":{...}}}
 *                 {"type":"item","streamId":"..","value":...}     上行 item
 *                 {"type":"end","streamId":".."}                  上行半关闭
 *                 {"type":"cancel","streamId":".."}
 * host -> client  {"type":"item","streamId":"..","value":...}
 *                 {"type":"end","streamId":".."}
 *                 {"type":"error","streamId":"..","error":{"code","message","details"}}
 * ```
 *
 * 规格照 dsh-Go `packages/dsh_client/src/carrier.dart` 的 `_WebSocketMuxLink`
 * 与 `mux.dart` 的帧处理。
 */
internal object DshMuxFrame {

    const val TYPE_OPEN = "open"
    const val TYPE_ITEM = "item"
    const val TYPE_END = "end"
    const val TYPE_CANCEL = "cancel"
    const val TYPE_ERROR = "error"

    /** 主机事件流的端点名（Gateway 内部的 `$events`）。 */
    const val HOST_EVENTS_ENDPOINT = "\$events"

    /** 解析一帧；返回 null 表示协议不合法（调用方应当断开重连）。 */
    fun parse(text: String): JSONObject? {
        val frame = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val type = frame.optString("type")
        if (type.isEmpty()) return null
        // 服务端→客户端的流帧必须带 streamId；error 帧还要带 error 对象。
        when (type) {
            TYPE_ITEM, TYPE_END, TYPE_ERROR -> {
                val streamId = frame.optString("streamId")
                if (streamId.isEmpty()) return null
                if (type == TYPE_ERROR && frame.optJSONObject("error") == null) return null
            }
        }
        return frame
    }

    /**
     * 客户端→服务端的开流帧。
     *
     * 注意只套**一层** args —— 引擎会拒绝 `payload.args.args` 这种双层结构
     * （报 "forwarded Remote event stream requires an empty args object"）。
     */
    fun open(streamId: String, endpoint: String, args: JSONObject): JSONObject =
        JSONObject()
            .put("type", TYPE_OPEN)
            .put("streamId", streamId)
            .put("endpoint", endpoint)
            .put("payload", JSONObject().put("args", args))

    /** 客户端→服务端的上行 item 帧。 */
    fun item(streamId: String, value: Any?): JSONObject =
        JSONObject()
            .put("type", TYPE_ITEM)
            .put("streamId", streamId)
            .put("value", value ?: JSONObject.NULL)

    /** 客户端→服务端的上行半关闭帧。 */
    fun end(streamId: String): JSONObject =
        JSONObject().put("type", TYPE_END).put("streamId", streamId)

    /** 客户端→服务端的取消帧。 */
    fun cancel(streamId: String): JSONObject =
        JSONObject().put("type", TYPE_CANCEL).put("streamId", streamId)

    /** 帧里的 `value` 是不是 `$events` 的主机事件。 */
    fun hostEventOf(frame: JSONObject): DshHostEvent? {
        if (frame.optString("type") != TYPE_ITEM) return null
        val value = frame.opt("value")
        return DshHostEvent.fromFrame(value)
    }

    /** 帧里的错误信息。 */
    fun errorOf(frame: JSONObject): String {
        val error = frame.optJSONObject("error") ?: return "未知错误"
        val message = error.optString("message")
        val code = error.optString("code")
        return when {
            message.isEmpty() && code.isEmpty() -> "未知错误"
            code.isEmpty() -> message
            message.isEmpty() -> code
            else -> "$code：$message"
        }
    }
}

/** 一条逻辑流的帧（客户端打开的流，服务端往里推东西）。 */
internal data class DshMuxStreamFrame(
    val type: String,
    val value: JSONObject? = null,
    val error: String? = null,
)

/** `$events` 首帧 ready 带来的连接代（generation）。 */
data class DshMuxGeneration(
    /** 每次成功（重）连递增，从 1 开始。 */
    val number: Int,
    /** 回 `$events/result` 时必须带上它。 */
    val clientId: String,
    val home: String,
)

/** 审批决策 —— 与 DSH 主机端的枚举取值一致。 */
internal enum class DshApprovalDecision(val wire: String) {
    /** 允许一次。 */
    ALLOW_ONCE("allowed-once"),

    /** 一直允许。 */
    ALLOW_ALWAYS("allowed-always"),

    /** 拒绝。 */
    REJECT("rejected"),

    /** 拒绝并记住。 */
    REJECT_ALWAYS("rejected-always"),
}
