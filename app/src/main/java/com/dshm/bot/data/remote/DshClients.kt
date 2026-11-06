package com.dshm.bot.data.remote

import com.dshm.bot.data.runtime.RuntimeEndpoint

/**
 * 按引擎端点共享一个 [DshClient]。
 *
 * 之前设置、账号、主界面、对话各建一个 client，各自登录、各自维护 cookie，
 * 既慢又容易出现一边登录成功、另一边 401。这里以 `baseUrl + token` 为键复用连接，
 * 让认证/HTTP 细节只存在于 data 层。
 */
object DshClients {
    private val lock = Any()
    private val cache = LinkedHashMap<String, DshClient>()

    fun of(endpoint: RuntimeEndpoint): DshClient = synchronized(lock) {
        val key = endpoint.baseUrl.trimEnd('/') + "#" + endpoint.token
        cache.getOrPut(key) { DshClient(endpoint) }
    }

    /** 引擎换端口/重启后清掉旧 cookie。 */
    fun clear() = synchronized(lock) { cache.clear() }
}
