package com.dshm.bot.data.remote

import com.dshm.bot.data.runtime.RuntimeEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * DS Bot 手机接口的版本协商结果，进程内按引擎端点共享。
 *
 * `hello` 是 mobile-api 分支的能力协商（`api` 版本 + `capabilities` 列表 +
 * `boot`/`seq` 通知游标）。以前 `DsBotHello` / `DsBotRepository.hello()` 只有模型和单测，
 * 生产链路从没调用过；现在由 `HomeViewModel.setEndpoint()` 在引擎起来时刷新一次，
 * 所有页面通过 [supports] 按能力决定是否调用某个 endpoint。
 *
 * 协商失败（引擎是旧版 DS Bot、没有 `hello`）时 [hello] 为 null，
 * [supports] 一律返回 false —— 对齐 `DsBotHello.supports` 的「api==null 禁用全部」语义，
 * 不把 `bot/not-found` 显示成主界面错误。
 */
object DsBotCapabilities {

    private val _hello = MutableStateFlow<DsBotHello?>(null)
    val hello: StateFlow<DsBotHello?> = _hello.asStateFlow()

    /** 当前协商对应的引擎 key；换端口/重启后旧结果不能沿用。 */
    @Volatile
    private var endpointKey: String? = null

    private fun keyOf(endpoint: RuntimeEndpoint) =
        endpoint.baseUrl.trimEnd('/') + "#" + endpoint.token

    /** 引擎停止 / 切换端点前清掉，避免旧能力被新引擎误用。 */
    fun clear() {
        endpointKey = null
        _hello.value = null
    }

    /**
     * 发起一次 `hello` 协商。失败时保持 null（能力全关），不抛到 UI。
     *
     * 用 [DsBotRepository] 走 `/api/bot`，和 `state` 共用同一个 cookie 客户端。
     */
    suspend fun refresh(endpoint: RuntimeEndpoint, client: DshClient) {
        val key = keyOf(endpoint)
        if (endpointKey == key && _hello.value != null) return
        val result = runCatching { DsBotRepository(client).hello(offeredApi = listOf(1)) }
            .getOrNull()
        endpointKey = key
        _hello.value = result
    }

    /** 这个能力能不能用；协商没成功时一律不能用。 */
    fun supports(capability: String): Boolean =
        _hello.value?.supports(capability) == true

    /** 通知补读的游标（引擎重启后从上次 seq 继续）。 */
    fun noticeCursor(): Pair<String, Long>? = _hello.value?.let {
        val boot = it.boot.takeIf(String::isNotBlank) ?: return null
        boot to it.seq
    }
}
