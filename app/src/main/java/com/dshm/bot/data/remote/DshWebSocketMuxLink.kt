package com.dshm.bot.data.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * `/api/remote.mux` 的 WebSocket 链路。
 *
 * 一条 WebSocket 承载很多逻辑流；每个 WebSocket 文本消息是一帧 JSON。
 * 主机每 2 秒 ping 一次，OkHttp 会自动回 pong；这里再加一条客户端 ping
 * 做死链检测（照 dsh-Go 的 `pingInterval`）。
 *
 * 规格照 dsh-Go `packages/dsh_client/src/carrier.dart` 的 `_WebSocketMuxLink`。
 */
internal class DshWebSocketMuxLink private constructor(
    private val socket: WebSocket,
) : DshMuxLink {

    private var lost = false
    private var onFrame: ((JSONObject) -> Unit)? = null
    private var onLost: ((String) -> Unit)? = null

    override fun start(onFrame: (JSONObject) -> Unit, onLost: (String) -> Unit) {
        this.onFrame = onFrame
        this.onLost = onLost
    }

    override fun open(streamId: String, endpoint: String, args: JSONObject) {
        send(DshMuxFrame.open(streamId, endpoint, args))
    }

    override fun sendItem(streamId: String, value: Any?) {
        send(DshMuxFrame.item(streamId, value))
    }

    override fun endUplink(streamId: String) {
        send(DshMuxFrame.end(streamId))
    }

    override fun cancel(streamId: String) {
        send(DshMuxFrame.cancel(streamId))
    }

    override fun close(code: Int, reason: String) {
        lost = true
        socket.close(code, reason)
    }

    private fun send(frame: JSONObject) {
        socket.send(frame.toString())
    }

    private fun lose(reason: String) {
        if (lost) return
        lost = true
        DshMuxLog.log("link lost: $reason")
        onLost?.invoke(reason)
    }

    companion object {
        private const val TAG = "DshMux"

        /** mux 路径。 */
        const val PATH = "/api/remote.mux"

        /**
         * 建立一条链路。
         *
         * @param baseUrl 引擎地址（如 `http://127.0.0.1:3080/`）
         * @param cookie 认证 cookie（`dsh-auth-*`）
         */
        fun connect(baseUrl: String, cookie: String): DshWebSocketMuxLink {
            val url = baseUrl.trimEnd('/') + PATH
            val client = OkHttpClient.Builder()
                .pingInterval(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                .build()
            lateinit var link: DshWebSocketMuxLink
            val socket = client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (link.isLost) return
                        val frame = DshMuxFrame.parse(text)
                        if (frame == null) {
                            link.lose("协议错误：不合法的 Remote 流帧")
                            webSocket.close(4002, "invalid Remote stream frame")
                            return
                        }
                        link.deliver(frame)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        link.lose("WebSocket 错误：${t.message ?: t::class.java.simpleName}")
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        link.lose("WebSocket 已关闭（$code ${reason.trim()}）")
                    }
                },
            )
            link = DshWebSocketMuxLink(socket)
            return link
        }
    }

    /** 链路是否已断开。 */
    val isLost: Boolean get() = lost

    /** 把一帧交给状态机。 */
    private fun deliver(frame: JSONObject) {
        onFrame?.invoke(frame)
    }
}
