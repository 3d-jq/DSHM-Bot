package com.dshm.bot.data.remote

import com.dshm.bot.data.runtime.RuntimeEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * mux 的宿主：每个引擎端点一个 mux 实例。
 *
 * mux 是 DSH 客户端的地基 —— 它承载主机事件流（events）：
 * - approval/request：Bot 要跑命令时等一个审批（之前无处可答，卡死）；
 * - user-questions/request：Bot 反问用户；
 * - api-session 系列：会话增删改和状态。
 *
 * 规格照 dsh-Go 的 packages/dsh_client/src/mux.dart。
 */
object DshMuxHub {

    private val lock = Any()
    private val instances = LinkedHashMap<String, DshMux>()

    /** 拿（或建）某个引擎的 mux。 */
    fun of(endpoint: RuntimeEndpoint): DshMux = synchronized(lock) {
        val key = endpoint.baseUrl.trimEnd('/') + "#" + endpoint.token
        instances.getOrPut(key) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            DshMux(
                scope = scope,
                connect = { DshClients.of(endpoint).openMuxLink() },
            )
        }
    }

    /** 启动某个引擎的 mux。 */
    fun start(endpoint: RuntimeEndpoint): DshMux {
        val mux = of(endpoint)
        mux.start()
        return mux
    }

    /** 主机事件流（广播）。 */
    fun hostEvents(endpoint: RuntimeEndpoint): SharedFlow<DshHostEvent> = of(endpoint).hostEvents

    /** 引擎重启后把旧连接清掉 —— 端口/token 变了，旧 socket 没用了。 */
    fun clear() = synchronized(lock) {
        instances.values.forEach { it.close() }
        instances.clear()
    }
}
