package com.dshm.bot.data.remote

import org.json.JSONObject

/**
 * mux 的一条物理链路（一条 WebSocket）。
 *
 * 抽象出来是为了能单测状态机 —— 真机上跑的是 [DshWebSocketMuxLink]，
 * 测试里跑的是内存实现。
 *
 * 规格照 dsh-Go `packages/dsh_client/src/carrier.dart` 的 `DshMuxLink`。
 */
interface DshMuxLink {

    /**
     * 开始收帧。回调会在后台线程触发。
     *
     * @param onFrame 收到一帧合法帧
     * @param onLost 链路断开（协议错误、连接关闭、网络错误）
     */
    fun start(onFrame: (JSONObject) -> Unit, onLost: (String) -> Unit)

    /** 打开一条逻辑流。[args] 是端点参数（会被包进 payload.args）。 */
    fun open(streamId: String, endpoint: String, args: JSONObject)

    /** 往一条已打开的流发一个上行 item。 */
    fun sendItem(streamId: String, value: Any?)

    /** 关闭上行方向。 */
    fun endUplink(streamId: String)

    /** 取消一条流。 */
    fun cancel(streamId: String)

    /** 关闭链路。 */
    fun close(code: Int, reason: String)
}
