package com.dshm.bot.data.remote

import org.json.JSONObject

/**
 * 测试用的内存链路：把客户端发出的帧记下来，可以手动喂帧给客户端。
 */
internal class FakeMuxLink : DshMuxLink {

    /** 客户端发出的所有帧。 */
    val sent = mutableListOf<JSONObject>()

    /** 断链原因（null = 还连着）。 */
    var lostReason: String? = null
        private set

    private var onFrame: ((JSONObject) -> Unit)? = null
    private var onLost: ((String) -> Unit)? = null

    /** 已打开的流：streamId → endpoint。 */
    val opened = mutableMapOf<String, String>()

    override fun start(onFrame: (JSONObject) -> Unit, onLost: (String) -> Unit) {
        this.onFrame = onFrame
        this.onLost = onLost
    }

    override fun open(streamId: String, endpoint: String, args: JSONObject) {
        opened[streamId] = endpoint
        sent += DshMuxFrame.open(streamId, endpoint, args)
    }

    override fun sendItem(streamId: String, value: Any?) {
        sent += DshMuxFrame.item(streamId, value)
    }

    override fun endUplink(streamId: String) {
        sent += DshMuxFrame.end(streamId)
    }

    override fun cancel(streamId: String) {
        sent += DshMuxFrame.cancel(streamId)
    }

    override fun close(code: Int, reason: String) {
        lostReason = reason
    }

    /** 喂一帧给客户端。 */
    fun deliver(frame: JSONObject) {
        onFrame?.invoke(frame)
    }

    /** 喂一个 `$events` 的 item（值是主机事件）。 */
    fun deliverHostEvent(event: JSONObject) {
        deliver(
            JSONObject()
                .put("type", DshMuxFrame.TYPE_ITEM)
                .put("streamId", eventsStreamId())
                .put("value", event),
        )
    }

    /** 喂一个 `$events` 的 item（值是主机事件，带 streamId）。 */
    fun deliverHostEvent(streamId: String, event: JSONObject) {
        deliver(
            JSONObject()
                .put("type", DshMuxFrame.TYPE_ITEM)
                .put("streamId", streamId)
                .put("value", event),
        )
    }

    /** 客户端打开 `$events` 时用的 streamId。 */
    fun eventsStreamId(): String =
        sent.firstOrNull { it.optString("endpoint") == DshMuxFrame.HOST_EVENTS_ENDPOINT }
            ?.optString("streamId")
            ?: ""

    /** 模拟链路断开。 */
    fun lose(reason: String) {
        lostReason = reason
        onLost?.invoke(reason)
    }
}
