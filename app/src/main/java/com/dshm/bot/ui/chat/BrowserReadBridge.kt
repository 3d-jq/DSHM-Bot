package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.DshClients
import com.dshm.bot.data.remote.RemoteDshApi
import com.dshm.bot.data.runtime.RuntimeEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Bot 浏览器读页通道（DS Bot `src/host/browser.js` 的窗口侧）。
 *
 * 桌面版的浏览器活在自己的窗口里，所以引擎用一条长轮询问窗口：
 * - 这轮 `browser-wait {clientId, open}` 告诉引擎「哪些 Bot 现在开着页面」，
 *   同时把等待中的 `read_browser` 请求带回来；
 * - 我们在 WebView 里把当前页读成 Markdown，用 `browser-result {id, ok, page}` 回执。
 *
 * 引擎最多挂 20 秒，所以这条 HTTP 请求必须比它长（客户端 readTimeout 是 30 秒）。
 */
object BrowserReadBridge {

    /** 上一次失败的原因（界面上提示「浏览器没接通」用）。 */
    val lastError = MutableStateFlow<String?>(null)

    suspend fun loop(endpoint: RuntimeEndpoint, store: BotBrowserStore) {
        val api = RemoteDshApi(DshClients.of(endpoint))
        val clientId = "android-" + UUID.randomUUID()
        while (currentCoroutineContext().isActive) {
            val started = System.currentTimeMillis()
            val open = withContext(Dispatchers.Main) { store.openPages() }
            val value = runCatching {
                api.botValue(
                    "browser-wait",
                    JSONObject().put("clientId", clientId).put("open", open),
                )
            }
            val items = value.getOrNull() as? JSONArray
            if (items == null) {
                val message = value.exceptionOrNull()?.message
                if (message != null) lastError.value = message
                // 插件没开（`bot/browser-off`）或引擎在重启：退一步再试，别打热循环。
                val elapsed = System.currentTimeMillis() - started
                delay(if (elapsed < 1_000) 5_000 else 500)
                continue
            }
            lastError.value = null
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                answer(api, store, item)
            }
        }
    }

    /** 回一条读页请求：能读到就给 `page`，读不到也要回 `ok: false`，否则 Bot 会卡到超时。 */
    private suspend fun answer(api: RemoteDshApi, store: BotBrowserStore, item: JSONObject) {
        val id = item.optString("id")
        if (id.isEmpty()) return
        val botId = item.optString("botId")
        val page = withContext(Dispatchers.Main) {
            store.existing(botId)?.readActivePage()
        }
        val payload = JSONObject().put("id", id)
        if (page == null) {
            payload.put("ok", false).put("error", NO_PAGE)
        } else if (page.has("error")) {
            payload.put("ok", false).put("error", page.optString("error"))
        } else {
            payload.put("ok", true).put("page", page)
        }
        runCatching { api.botValue("browser-result", payload) }
    }

    private const val NO_PAGE = "No page is open in this Bot's browser."
}
