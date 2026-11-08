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

    /**
     * 回一条请求：读页给 `page`，动作 / 导航 / 标签给 `result`。
     *
     * 不管成功还是失败都要回，否则 Bot 那边会卡到超时。
     */
    private suspend fun answer(api: RemoteDshApi, store: BotBrowserStore, item: JSONObject) {
        val id = item.optString("id")
        if (id.isEmpty()) return
        val botId = item.optString("botId")
        val kind = item.optString("kind").ifEmpty { "read" }
        val payload = JSONObject().put("id", id)
        withContext(Dispatchers.Main) {
            // 「Bot 正在操作」要显示在面板里：读页和标签操作一闪而过，不打扰。
            val session = if (kind == "open") store.session(botId) else store.existing(botId)
            val showsBusy = kind in BUSY_KINDS
            if (showsBusy) session?.beginAgent(kind)
            try {
                when (kind) {
                    "open" -> openPage(store, botId, item, payload)
                    "back", "forward", "reload" -> navigate(store, botId, kind, payload)
                    "tabs" -> listTabs(store, botId, payload)
                    "close" -> closeTab(store, botId, item, payload)
                    "handOver" -> beginHandOver(store, botId, item, payload)
                    "handOverWait" -> waitHandOver(store, botId, item, payload)
                    "read" -> readPage(store, botId, payload)
                    else -> runAction(store, botId, kind, item, payload)
                }
            } finally {
                if (showsBusy) session?.endAgent()
            }
        }
        runCatching { api.botValue("browser-result", payload) }
    }

    /**
     * 打开页面：没有标签就新建一个，已有标签就在当前标签里导航。
     *
     * 面板没开着也能用 —— WebView 用应用上下文创建，页面留在浏览器池里，
     * 用户下次打开面板就看到它了（DeepSearch 的 `BrowserTabPool` 也是这么做的）。
     */
    private suspend fun openPage(
        store: BotBrowserStore,
        botId: String,
        item: JSONObject,
        payload: JSONObject,
    ) {
        val url = item.optString("url").trim()
        if (url.isEmpty()) {
            payload.put("ok", false).put("error", "Give an address or a search phrase.")
            return
        }
        val session = store.session(botId)
        if (item.optBoolean("newTab", false) || session.tabs.isEmpty()) {
            session.newTab(url)
        } else {
            session.load(url)
        }
        session.awaitIdle()
        payload.put("ok", true).put("result", pageState(session))
    }

    private suspend fun navigate(
        store: BotBrowserStore,
        botId: String,
        kind: String,
        payload: JSONObject,
    ) {
        val session = store.existing(botId)
        if (session?.activeWebView() == null) {
            payload.put("ok", false).put("error", "no-page")
            return
        }
        when (kind) {
            "back" -> session.goBack()
            "forward" -> session.goForward()
            else -> session.reload()
        }
        session.awaitIdle(8_000)
        payload.put("ok", true).put("result", pageState(session))
    }

    private fun listTabs(store: BotBrowserStore, botId: String, payload: JSONObject) {
        val session = store.existing(botId)
        val tabs = JSONArray()
        session?.tabs?.forEach { tab ->
            tabs.put(
                JSONObject()
                    .put("id", tab.id.toString())
                    .put("title", tab.title)
                    .put("url", tab.url)
                    .put("active", tab.id == session.activeId),
            )
        }
        payload.put(
            "ok",
            true,
        ).put(
            "result",
            JSONObject()
                .put("tabs", tabs)
                .put("active", session?.activeId?.toString().orEmpty()),
        )
    }

    private fun closeTab(
        store: BotBrowserStore,
        botId: String,
        item: JSONObject,
        payload: JSONObject,
    ) {
        val session = store.existing(botId)
        val tabId = item.optString("tabId").toLongOrNull()
        if (session == null || tabId == null || session.tabs.none { it.id == tabId }) {
            payload.put("ok", false).put("error", "No tab has that id.")
            return
        }
        session.closeTab(tabId)
        payload.put("ok", true).put(
            "result",
            JSONObject().put("closed", tabId.toString()).put("tabs", session.tabs.size),
        )
    }

    /** 把页面交给用户：起来横幅，并把 App 推到那个 Bot 的浏览器。 */
    private fun beginHandOver(
        store: BotBrowserStore,
        botId: String,
        item: JSONObject,
        payload: JSONObject,
    ) {
        val session = store.existing(botId)
        if (session?.activeWebView() == null) {
            payload.put("ok", false).put("error", "no-page")
            return
        }
        val id = item.optString("id")
        val reason = item.optString("reason").ifEmpty { "Take over this page." }
        session.beginHandOver(id, reason)
        BrowserHandOverStore.started(botId, reason)
        payload.put("ok", true).put("result", JSONObject().put("id", id))
    }

    /** 轮询交接状态：`pending` / `done` / `gone`。 */
    private fun waitHandOver(
        store: BotBrowserStore,
        botId: String,
        item: JSONObject,
        payload: JSONObject,
    ) {
        val session = store.existing(botId)
        val id = item.optString("handOverId")
        if (session == null || id.isEmpty()) {
            payload.put("ok", false).put("error", "gone")
            return
        }
        val status = session.handOverStatus(id)
        if (status == "done") {
            session.endHandOver()
            BrowserHandOverStore.finished()
        }
        payload.put("ok", true).put("result", JSONObject().put("status", status))
    }

    private suspend fun readPage(store: BotBrowserStore, botId: String, payload: JSONObject) {
        val session = store.existing(botId)
        val page = session?.takeIf { it.activeWebView() != null }?.readActivePage()
        when {
            page == null -> payload.put("ok", false).put("error", "no-page")
            page.has("error") -> payload.put("ok", false).put("error", page.optString("error"))
            else -> payload.put("ok", true).put("page", page)
        }
    }

    private suspend fun runAction(
        store: BotBrowserStore,
        botId: String,
        kind: String,
        item: JSONObject,
        payload: JSONObject,
    ) {
        val webView = store.existing(botId)?.activeWebView()
        if (webView == null) {
            payload.put("ok", false).put("error", "no-page")
            return
        }
        val script = BrowserActionScripts.script(kind, item)
        if (script == null) {
            payload.put("ok", false).put("error", "Unknown action: $kind")
            return
        }
        // 点 / 填之前先看一眼目标：密码框拒绝，危险按钮先问用户。
        if (kind == "click" || kind == "type") {
            val selector = item.optString("selector")
            val target = evaluatePageScript(webView, BrowserGuardScripts.inspect(selector))
            when (val verdict = BrowserGuardRules.verdict(kind, target ?: JSONObject())) {
                is GuardVerdict.Refuse -> {
                    payload.put("ok", false).put("error", verdict.reason)
                    return
                }

                is GuardVerdict.Ask -> {
                    val allowed = BrowserGuardStore.ask(botId, kind, selector, verdict.reason)
                    if (!allowed) {
                        payload.put(
                            "ok",
                            false,
                        ).put(
                            "error",
                            "The user did not allow this action. Do not retry it unless they ask.",
                        )
                        return
                    }
                }

                GuardVerdict.Proceed -> Unit
            }
        }
        val result = evaluatePageScript(webView, script)
        when {
            result == null ->
                payload.put("ok", false).put("error", "The page did not answer")

            result.optString("error").isNotEmpty() ->
                payload.put("ok", false).put("error", result.optString("error"))

            else -> payload.put("ok", true).put("result", result)
        }
    }

    /** 当前标签的状态：id / 地址 / 标题 / 是否还在装载。 */
    private fun pageState(session: BotBrowserSession): JSONObject {
        val tab = session.activeTab()
        return JSONObject()
            .put("tabId", tab?.id?.toString().orEmpty())
            .put("url", tab?.url.orEmpty())
            .put("title", tab?.title.orEmpty())
            .put("loading", session.loading)
            .put("takenOver", session.agentTakenOver)
    }

    /** 会在面板里显示「正在操作」的动作。 */
    private val BUSY_KINDS = setOf("open", "back", "forward", "reload", "click", "type", "scroll")
}
