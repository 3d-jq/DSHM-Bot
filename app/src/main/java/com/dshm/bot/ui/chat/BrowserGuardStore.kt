package com.dshm.bot.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 「允许这次操作吗」：一次只问一个。
 *
 * 对话框画在 `DshmBotApp` 顶层，所以用户不管在聊天页还是浏览器面板里都能看到；
 * 没人回答（或两分钟没答）就按拒绝处理。
 */
internal object BrowserGuardStore {
    private val _pending = MutableStateFlow<GuardRequest?>(null)
    val pending: StateFlow<GuardRequest?> = _pending.asStateFlow()
    private var waiter: CompletableDeferred<Boolean>? = null

    suspend fun ask(
        botId: String,
        action: String,
        detail: String,
        reason: String,
        timeoutMs: Long = 120_000,
    ): Boolean {
        val mine = CompletableDeferred<Boolean>()
        waiter?.complete(false)
        waiter = mine
        _pending.value = GuardRequest(botId, action, detail, reason)
        return try {
            withTimeoutOrNull(timeoutMs) { mine.await() } ?: false
        } finally {
            if (waiter === mine) {
                waiter = null
                _pending.value = null
            }
        }
    }

    fun answer(allowed: Boolean) {
        waiter?.complete(allowed)
    }
}

/**
 * 交接给用户：App 收到后把那个 Bot 的浏览器推到前台。
 *
 * 只负责「该打开谁」，等待和完成在 `BotBrowserSession` 里（面板上的横幅）。
 */
internal object BrowserHandOverStore {
    private val _pending = MutableStateFlow<Pair<String, String>?>(null)
    val pending: StateFlow<Pair<String, String>?> = _pending.asStateFlow()

    fun started(botId: String, reason: String) {
        _pending.value = botId to reason
    }

    fun finished() {
        _pending.value = null
    }
}
