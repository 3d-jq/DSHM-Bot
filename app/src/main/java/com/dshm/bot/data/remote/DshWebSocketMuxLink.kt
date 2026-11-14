package com.dshm.bot.data.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

    /** start / lose 都可能从不同线程到达（OkHttp 回调线程 vs runLoop 协程），共用这把锁。 */
    private val lock = Any()

    private var onFrame: ((JSONObject) -> Unit)? = null
    private var onLost: ((String) -> Unit)? = null

    private var started = false
    private var lost = false
    private var lostReason: String? = null

    /** onLost 只发一次：先 lose 后 start 的场景由 [start] 补发，两边都置位。 */
    private var lostDelivered = false

    override fun start(onFrame: (JSONObject) -> Unit, onLost: (String) -> Unit) {
        val missed: String?
        synchronized(lock) {
            this.onFrame = onFrame
            this.onLost = onLost
            started = true
            // start 之前链路已经断过（比如握手刚成就被切）：这里补发一次 onLost，
            // 否则 DshMux 要白等满 ready 超时才重连。
            missed = if (lost && !lostDelivered) {
                lostDelivered = true
                lostReason
            } else {
                null
            }
        }
        missed?.let(onLost)
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
        lose("client closed") // close 的语义就是链路结束，走同一条 onLost 通路
        socket.close(code, reason)
    }

    private fun send(frame: JSONObject) {
        socket.send(frame.toString())
    }

    private fun lose(reason: String) {
        val notify: ((String) -> Unit)?
        synchronized(lock) {
            if (lost) return
            lost = true
            lostReason = reason
            // start 之前断开时 onLost 还没挂上，交给 [start] 补发。
            notify = if (started && !lostDelivered) {
                lostDelivered = true
                onLost
            } else {
                null
            }
        }
        DshMuxLog.log("link lost: $reason")
        notify?.invoke(reason)
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
            // 先构造后注册：newWebSocket 的回调在 OkHttp 线程上异步触发，可能早于
            // link 赋值对回调线程可见（引擎重启期高频重连时，连接会被立刻拒绝）。
            // 用 AtomicReference 持有尚未挂上回调的 link，未就绪时安全降级，不崩。
            val holder = AtomicReference<DshWebSocketMuxLink?>(null)
            val socket = client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        // 握手完成前不可能有消息；未就绪一律丢弃。
                        val target = holder.get() ?: return
                        if (target.isLost) return
                        val frame = DshMuxFrame.parse(text)
                        if (frame == null) {
                            target.lose("协议错误：不合法的 Remote 流帧")
                            webSocket.close(4002, "invalid Remote stream frame")
                            return
                        }
                        target.deliver(frame)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        val reason = "WebSocket 错误：${t.message ?: t::class.java.simpleName}"
                        val target = holder.get()
                        if (target == null) {
                            // 就绪前就失败：onLost 还没处可去，这里记日志并收掉 socket，
                            // 至少不再无声无息。
                            DshMuxLog.log("link lost before start: $reason")
                            webSocket.cancel()
                            return
                        }
                        target.lose(reason)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        holder.get()?.lose("WebSocket 已关闭（$code ${reason.trim()}）")
                    }
                },
            )
            val created = DshWebSocketMuxLink(socket)
            holder.set(created)
            return created
        }
    }

    /** 链路是否已断开。 */
    val isLost: Boolean get() = lost

    /** 把一帧交给状态机。 */
    private fun deliver(frame: JSONObject) {
        onFrame?.invoke(frame)
    }
}
