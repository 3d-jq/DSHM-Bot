package com.dshm.bot.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.webkit.WebView
import com.dshm.bot.data.remote.DshClients
import com.dshm.bot.data.remote.RemoteDshApi
import com.dshm.bot.data.runtime.RuntimeEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
                // 兜底：里面的每一步（脚本 8 秒、waitStable 30 秒、网络 30 秒）都已各自有界，
                // 这是最后的保险丝 —— 一条请求不管卡在哪都不许拖住整条长轮询。
                // 超时补一条失败回执，让 Bot 立刻拿到错误，而不是等满自己的超时。
                val finished = withTimeoutOrNull(ANSWER_FALLBACK_TIMEOUT_MS) { answer(api, store, item) }
                if (finished == null) {
                    val id = item.optString("id")
                    if (id.isNotEmpty()) {
                        runCatching { api.botValue("browser-result", answerTimeoutReceipt(id)) }
                    }
                }
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
                    "hover" -> runScript(
                        store,
                        botId,
                        BrowserExtraScripts.hover(item.optString("selector")),
                        payload,
                    )

                    "executeJs" -> runScript(
                        store,
                        botId,
                        BrowserExtraScripts.execute(item.optString("script")),
                        payload,
                    )

                    "backbone" -> runScript(store, botId, BrowserExtraScripts.backbone(), payload)
                    "fetch" -> fetchInPage(store, botId, item, payload)
                    "waitStable" -> waitStable(store, botId, item, payload)
                    "scrollCollect" -> scrollCollect(store, botId, payload)
                    "screenshot" -> screenshot(store, botId, payload)
                    "getCookies" -> cookies(store, botId, payload)
                    "setCookie" -> setCookie(store, botId, item, payload)
                    "userAgent" -> userAgent(store, botId, item, payload)
                    "viewport" -> viewport(store, botId, item, payload)
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

    /** 跑一段「同步返回 JSON」的脚本，按 action 的约定回执。 */
    private suspend fun runScript(
        store: BotBrowserStore,
        botId: String,
        script: String,
        payload: JSONObject,
    ) {
        val webView = store.existing(botId)?.activeWebView()
        if (webView == null) {
            payload.put("ok", false).put("error", "no-page")
            return
        }
        val result = evaluatePageScript(webView, script)
        when {
            result == null -> payload.put("ok", false).put("error", "The page did not answer")
            result.optString("error").isNotEmpty() ->
                payload.put("ok", false).put("error", result.optString("error"))

            else -> payload.put("ok", true).put("result", result)
        }
    }

    /**
     * 页面内的 fetch：先发起、再轮询取结果。
     *
     * `evaluateJavascript` 不会等 Promise，所以结果暂存在 `window.__dshmFetch`。
     */
    private suspend fun fetchInPage(
        store: BotBrowserStore,
        botId: String,
        item: JSONObject,
        payload: JSONObject,
    ) {
        val webView = store.existing(botId)?.activeWebView()
        if (webView == null) {
            payload.put("ok", false).put("error", "no-page")
            return
        }
        val url = item.optString("url").trim()
        if (url.isEmpty()) {
            payload.put("ok", false).put("error", "Give a URL to fetch.")
            return
        }
        val method = item.optString("method").ifEmpty { "GET" }
        val body = item.optString("body").takeIf { it.isNotEmpty() }
        val start = evaluatePageScript(webView, BrowserExtraScripts.fetchStart(url, method, body))
        if (start == null || start.has("error")) {
            payload.put("ok", false).put("error", start?.optString("error") ?: "The page did not answer")
            return
        }
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            delay(250)
            val poll = evaluatePageScript(webView, BrowserExtraScripts.fetchPoll()) ?: continue
            val value = poll.optJSONObject("value") ?: continue
            if (value.has("error")) {
                payload.put("ok", false).put("error", value.optString("error"))
            } else {
                payload.put("ok", true).put("result", value)
            }
            return
        }
        payload.put("ok", false).put("error", "The fetch did not finish in 15 seconds.")
    }

    /** 等页面「安静」下来（文本、节点数、高度连续一段时间不变）。 */
    private suspend fun waitStable(
        store: BotBrowserStore,
        botId: String,
        item: JSONObject,
        payload: JSONObject,
    ) {
        val webView = store.existing(botId)?.activeWebView()
        if (webView == null) {
            payload.put("ok", false).put("error", "no-page")
            return
        }
        val quietMs = item.optLong("quietMs", 800).coerceIn(200, 5_000)
        val timeoutMs = item.optLong("timeoutMs", 15_000).coerceIn(1_000, 30_000)
        val started = System.currentTimeMillis()
        val deadline = started + timeoutMs
        var last = ""
        var lastChangedAt = started
        while (System.currentTimeMillis() < deadline) {
            val probe = evaluatePageScript(webView, BrowserExtraScripts.probe())
            val signature = probe?.toString().orEmpty()
            val now = System.currentTimeMillis()
            if (signature != last) {
                last = signature
                lastChangedAt = now
            } else if (now - lastChangedAt >= quietMs) {
                payload.put(
                    "ok",
                    true,
                ).put(
                    "result",
                    JSONObject().put("stable", true).put("waitedMs", now - started),
                )
                return
            }
            delay(200)
        }
        payload.put("ok", true).put(
            "result",
            JSONObject().put("stable", false).put("waitedMs", timeoutMs),
        )
    }

    /** 一路往下滚（无限滚动列表用），滚到底后把整页读成 Markdown。 */
    private suspend fun scrollCollect(store: BotBrowserStore, botId: String, payload: JSONObject) {
        val session = store.existing(botId)
        val webView = session?.activeWebView()
        if (session == null || webView == null) {
            payload.put("ok", false).put("error", "no-page")
            return
        }
        var lastHeight = -1
        var steps = 0
        while (steps < MAX_SCROLL_STEPS) {
            val step = evaluatePageScript(webView, BrowserExtraScripts.scrollStep(900))
            val height = step?.optInt("height", -1) ?: -1
            delay(350)
            steps += 1
            if (height in 1..lastHeight) break
            lastHeight = height
        }
        val page = session.readActivePage()
        if (page == null) {
            payload.put("ok", false).put("error", "no-page")
            return
        }
        payload.put("ok", true).put(
            "result",
            JSONObject().put("steps", steps).put("page", page),
        )
    }

    /** 截当前标签的图，落到应用自己的目录里，回路径。 */
    private suspend fun screenshot(store: BotBrowserStore, botId: String, payload: JSONObject) {
        val webView = store.existing(botId)?.activeWebView()
        if (webView == null) {
            payload.put("ok", false).put("error", "no-page")
            return
        }
        val bitmap = capture(webView)
        val path = store.saveScreenshot(botId, bitmap)
        if (path == null) {
            payload.put("ok", false).put("error", "The screenshot could not be saved.")
            return
        }
        payload.put(
            "ok",
            true,
        ).put(
            "result",
            JSONObject()
                .put("path", path)
                .put("width", bitmap.width)
                .put("height", bitmap.height),
        )
    }

    private fun capture(webView: WebView): Bitmap {
        var width = webView.width
        var height = webView.height
        if (width <= 0 || height <= 0) {
            // 面板没开着时 WebView 没有尺寸：按 1080x1920 量一次再画。
            width = 1080
            height = 1920
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            webView.layout(0, 0, width, height)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        webView.draw(Canvas(bitmap))
        return bitmap
    }

    private fun cookies(store: BotBrowserStore, botId: String, payload: JSONObject) {
        val session = store.existing(botId)
        val url = session?.currentPage()?.first ?: session?.activeWebView()?.url
        if (session == null || url.isNullOrEmpty()) {
            payload.put("ok", false).put("error", "no-page")
            return
        }
        payload.put(
            "ok",
            true,
        ).put(
            "result",
            JSONObject().put("url", url).put("cookies", session.cookies().orEmpty()),
        )
    }

    private fun setCookie(
        store: BotBrowserStore,
        botId: String,
        item: JSONObject,
        payload: JSONObject,
    ) {
        val session = store.existing(botId)
        if (session == null) {
            payload.put("ok", false).put("error", "no-page")
            return
        }
        val name = item.optString("name").trim()
        if (name.isEmpty()) {
            payload.put("ok", false).put("error", "Give the cookie name.")
            return
        }
        session.setCookie(item.optString("url"), name, item.optString("value"))
        payload.put("ok", true).put("result", JSONObject().put("set", name))
    }

    private fun userAgent(
        store: BotBrowserStore,
        botId: String,
        item: JSONObject,
        payload: JSONObject,
    ) {
        val session = store.existing(botId)
        if (session == null) {
            payload.put("ok", false).put("error", "no-page")
            return
        }
        if (!item.has("value")) {
            payload.put(
                "ok",
                true,
            ).put("result", JSONObject().put("userAgent", session.userAgent().orEmpty()))
            return
        }
        val value = item.optString("value").trim()
        session.setUserAgent(value.ifEmpty { null })
        payload.put(
            "ok",
            true,
        ).put(
            "result",
            JSONObject().put("userAgent", value.ifEmpty { "default" }),
        )
    }

    private fun viewport(
        store: BotBrowserStore,
        botId: String,
        item: JSONObject,
        payload: JSONObject,
    ) {
        val session = store.existing(botId)
        if (session == null || session.activeWebView() == null) {
            payload.put("ok", false).put("error", "no-page")
            return
        }
        val width = item.optInt("width", 0)
        if (width <= 0) {
            payload.put("ok", false).put("error", "Give the viewport width in CSS pixels.")
            return
        }
        session.setViewportWidth(width)
        payload.put("ok", true).put("result", JSONObject().put("width", width.coerceIn(200, 2400)))
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

    /** `scroll_collect` 最多往下滚几次（防止无限列表）。 */
    private const val MAX_SCROLL_STEPS = 25

    /**
     * 单条请求的兜底限时：要盖过最长的合法动作（`waitStable` 上限 30 秒），
     * 所以取 60 秒；正常路径永远不会碰到它。
     */
    private const val ANSWER_FALLBACK_TIMEOUT_MS = 60_000L
}

/** 兜底超时的回执：和 `answer` 的失败形状一致，Bot 拿到就能继续。 */
internal fun answerTimeoutReceipt(id: String): JSONObject = JSONObject()
    .put("id", id)
    .put("ok", false)
    .put("error", "The page did not answer")
