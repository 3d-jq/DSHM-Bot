package com.dshm.bot.ui.chat

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebStorage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dshm.bot.data.runtime.RuntimeInstaller
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import androidx.core.content.edit
import androidx.core.net.toUri

/** 一个浏览器标签（标题 / 地址随 WebView 回调更新）。 */
data class BotBrowserTab(
    val id: Long,
    val url: String = "",
    val title: String = "",
)

/** 面板顶上的下载进度（`DownloadManager` 的一行）。 */
data class BotDownload(
    val id: Long,
    val name: String,
    /** 0..1；总量未知时是 -1。 */
    val progress: Float,
    val done: Boolean,
    val failed: Boolean = false,
)

/** 浏览历史的一条（同一个 URL 只留最新一次，按 Bot 持久化）。 */
data class BotBrowserHistory(
    val url: String,
    val title: String,
    val visitedAt: Long,
)

/**
 * 一个 Bot 的浏览器面板（DS Bot `BrowserCards` 的 Android 版）。
 *
 * 每个 Bot 自己的标签页集合；页面由 WebView 载入，用户自己操作。
 * Bot 通过 `read_browser` 读当前标签：见 [readActivePage] + [BrowserReadBridge]。
 *
 * 注意：Android 的 WebView cookie 是进程级共享的（桌面版用独立 storage partition
 * 做到「登录只属于这个 Bot」），这里只能做到「标签页各自独立」。
 */
class BotBrowserSession internal constructor(
    val botId: String,
    private val context: Context,
) {
    val tabs = mutableStateListOf<BotBrowserTab>()
    var activeId by mutableStateOf<Long?>(null)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set
    /** 当前标签在装载页面（`onPageStarted` → `onPageFinished`）。 */
    var loading by mutableStateOf(false)
        private set
    private var loadingTabId: Long? = null

    /** Bot 正在操作这个浏览器：面板里要显示「谁在操作 + 接管」。 */
    var agentBusy by mutableStateOf(false)
        private set
    var agentAction by mutableStateOf<String?>(null)
        private set
    private var agentCancelled = false

    /** 用户在面板里点了「接管」：这一条回执要告诉模型别再等。 */
    val agentTakenOver: Boolean get() = agentCancelled

    /** 交接给用户：面板里显示横幅，等用户点「完成」。 */
    var handOverReason by mutableStateOf<String?>(null)
        private set
    private var handOverId: String? = null
    private var handOverDone = false

    /** 最近一次下载；完成后多留两秒再收。 */
    var download by mutableStateOf<BotDownload?>(null)
        private set
    private val localDownloads = ConcurrentHashMap<Long, BotDownload>()

    /** 浏览历史：最新的在前，最多 [MAX_HISTORY] 条。 */
    val history = mutableStateListOf<BotBrowserHistory>()

    /** 自定义 UA；null 表示用系统默认。 */
    var userAgentOverride by mutableStateOf<String?>(null)
        private set

    /** 自定义视口宽度（CSS px）；null 表示跟随设备。 */
    var viewportWidth by mutableStateOf<Int?>(null)
        private set

    /** 最近一次 Bot 操作浏览器的时间；聊天页据此决定这一轮要不要贴一张缩略图卡。 */
    var lastAgentAt by mutableStateOf(0L)
        private set

    private val views = mutableMapOf<Long, WebView>()
    private var nextId = 1L
    private val prefs = context.getSharedPreferences("bot-browser:$botId", Context.MODE_PRIVATE)

    init {
        userAgentOverride = prefs.getString(KEY_UA, null)?.takeIf { it.isNotBlank() }
        viewportWidth = prefs.getInt(KEY_VIEWPORT, 0).takeIf { it > 0 }
        loadHistory()
    }

    fun activeTab(): BotBrowserTab? = tabs.firstOrNull { it.id == activeId }

    fun activeWebView(): WebView? = activeId?.let { views[it] }

    fun select(id: Long) {
        if (tabs.none { it.id == id }) return
        activeId = id
        loadingTabId = null
        loading = false
        syncNavigation()
    }

    /** 新标签；[url] 为空就是一张空白页。 */
    fun newTab(url: String = ""): Long {
        if (tabs.size >= MAX_TABS) {
            closeTab(tabs.first().id)
        }
        val id = nextId++
        val view = createWebView(id)
        views[id] = view
        tabs.add(BotBrowserTab(id))
        activeId = id
        canGoBack = false
        canGoForward = false
        if (url.isNotEmpty()) startLoad(view, url)
        return id
    }

    fun closeTab(id: Long) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        tabs.removeAt(index)
        if (loadingTabId == id) {
            loadingTabId = null
            loading = false
        }
        views.remove(id)?.let { view ->
            runCatching {
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.stopLoading()
                view.destroy()
            }
        }
        if (activeId == id) {
            activeId = tabs.getOrNull(index)?.id ?: tabs.lastOrNull()?.id
            syncNavigation()
        }
    }

    fun load(url: String) {
        val text = url.trim()
        if (text.isEmpty()) return
        val view = activeWebView() ?: return
        startLoad(view, text)
    }

    /** 从对话里的网页卡片进入：优先切到已经打开这个地址的标签，否则在当前标签加载。 */
    fun showUrl(url: String) {
        val text = url.trim()
        if (text.isEmpty()) return
        val target = fixedUrl(text)
        val existing = tabs.firstOrNull { it.url == text || it.url == target }
        when {
            existing != null -> select(existing.id)
            activeId == null -> newTab(text)
            else -> load(text)
        }
    }

    fun reload() {
        activeWebView()?.let { view ->
            loadingTabId = activeId
            loading = true
            view.reload()
        }
    }

    fun goBack() {
        activeWebView()?.let { view ->
            if (view.canGoBack()) {
                loadingTabId = activeId
                loading = true
                view.goBack()
            }
        }
    }

    fun goForward() {
        activeWebView()?.let { view ->
            if (view.canGoForward()) {
                loadingTabId = activeId
                loading = true
                view.goForward()
            }
        }
    }

    /**
     * 等当前标签装载完。
     *
     * `loadUrl` 和 `onPageStarted` 之间有一小段空档，所以起点先自己置上 [loading]，
     * 不然刚发出请求就会以为已经好了。
     */
    suspend fun awaitIdle(timeoutMs: Long = 12_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (loading && !agentCancelled && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(100)
        }
    }

    /** 一次动作开始：置上「Bot 在操作」，并清掉上一次的接管标记。 */
    fun beginAgent(action: String) {
        agentCancelled = false
        agentAction = action
        agentBusy = true
        lastAgentAt = System.currentTimeMillis()
    }

    fun endAgent() {
        agentBusy = false
        agentAction = null
    }

    /** 取走「这一轮操作过浏览器」的标记；一轮只贴一张缩略图。 */
    fun consumeAgentMark(): Boolean {
        val had = lastAgentAt > 0L
        lastAgentAt = 0L
        return had
    }

    /** 用户点「接管」：打断当前的等待，动作尽快回执。 */
    fun cancelAgent() {
        agentCancelled = true
        endAgent()
    }

    /** 开始一次交接（`id` 是通道请求的 id）。 */
    fun beginHandOver(id: String, reason: String) {
        handOverId = id
        handOverReason = reason
        handOverDone = false
        endAgent()
    }

    /** 用户点了「完成」。 */
    fun finishHandOver() {
        handOverDone = true
    }

    /** `pending` / `done` / `gone`（这次交接已经不在这个标签页上）。 */
    fun handOverStatus(id: String): String = when {
        handOverId != id -> "gone"
        handOverDone -> "done"
        else -> "pending"
    }

    fun endHandOver() {
        handOverId = null
        handOverReason = null
        handOverDone = false
    }

    fun updateDownload(value: BotDownload?) {
        download = value
    }

    /** 查一次下载进度。 */
    fun downloadStatus(id: Long): BotDownload? {
        localDownloads[id]?.let { return it }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return null
        val cursor = runCatching { manager.query(DownloadManager.Query().setFilterById(id)) }.getOrNull()
            ?: return null
        cursor.use { row ->
            if (!row.moveToFirst()) return null
            val bytes = row.getLong(row.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = row.getLong(row.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val status = row.getInt(row.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val name = row.getString(row.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)).orEmpty()
            val progress = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else -1f
            val finished = status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED
            return BotDownload(
                id = id,
                name = name,
                progress = progress,
                done = finished,
                failed = status == DownloadManager.STATUS_FAILED,
            )
        }
    }

    /** 报告给引擎的「当前页」：普通网页和 rootfs 里的本地页面都算。 */
    fun currentPage(): Pair<String, String>? {
        val tab = activeTab() ?: return null
        val url = tab.url
        if (!isPageUrl(url)) return null
        return url to tab.title
    }

    /** 当前页的 cookie（`get_cookies`）。 */
    fun cookies(): String? {
        val url = activeWebView()?.url?.takeIf { it.isNotEmpty() } ?: currentPage()?.first ?: return null
        return CookieManager.getInstance().getCookie(url)
    }

    /** 写一条 cookie（`set_cookie`）。 */
    fun setCookie(url: String, name: String, value: String) {
        val target = url.takeIf { it.isNotEmpty() }
            ?: activeWebView()?.url?.takeIf { it.isNotEmpty() }
            ?: return
        CookieManager.getInstance().setCookie(target, "$name=$value; path=/")
        CookieManager.getInstance().flush()
    }

    fun userAgent(): String? = activeWebView()?.settings?.userAgentString

    /** [value] 为空表示恢复默认 UA。 */
    fun setUserAgent(value: String?) {
        val clean = value?.trim()?.takeIf { it.isNotEmpty() }
        userAgentOverride = clean
        prefs.edit {
            if (clean == null) remove(KEY_UA) else putString(KEY_UA, clean)
        }
        views.values.forEach { view ->
            view.settings.userAgentString = clean
        }
        activeWebView()?.reload()
    }

    /**
     * 按 CSS 宽度摆一个视口：设备宽度对应过去算初始缩放。
     *
     * WebView 没有真正的 viewport 覆写，这是最接近的做法（DeepSearch 的 `set_viewport` 同理）。
     */
    fun setViewportWidth(width: Int) {
        val clean = width.takeIf { it in 200..2400 }
        viewportWidth = clean
        prefs.edit {
            if (clean == null) remove(KEY_VIEWPORT) else putInt(KEY_VIEWPORT, clean)
        }
        val view = activeWebView() ?: return
        applyViewport(view)
        view.reload()
    }

    /** 恢复「跟随设备」视口。 */
    fun resetViewport() {
        viewportWidth = null
        prefs.edit { remove(KEY_VIEWPORT) }
        val view = activeWebView() ?: return
        view.setInitialScale(0)
        view.settings.useWideViewPort = true
        view.settings.loadWithOverviewMode = true
        view.reload()
    }

    private fun applyViewport(view: WebView) {
        val width = viewportWidth ?: return
        val metrics = context.resources.displayMetrics
        val deviceWidth = (view.width.takeIf { it > 0 } ?: metrics.widthPixels).toFloat()
        val target = width.coerceIn(200, 2400) * metrics.density
        val scale = (deviceWidth / target * 100).toInt().coerceIn(10, 400)
        view.settings.useWideViewPort = true
        view.settings.loadWithOverviewMode = true
        view.setInitialScale(scale)
    }

    // ---- 浏览历史 -----------------------------------------------------------

    /** 记一条访问（网页和 rootfs 本地页都算），同一 URL 去重并挪到最前。 */
    private fun rememberVisit(url: String?, title: String?) {
        val clean = url?.trim().orEmpty()
        if (!isPageUrl(clean)) return
        val existing = history.indexOfFirst { it.url == clean }
        val entry = BotBrowserHistory(
            url = clean,
            title = title?.trim().orEmpty().ifEmpty {
                if (existing >= 0) history[existing].title else clean
            },
            visitedAt = System.currentTimeMillis(),
        )
        if (existing >= 0) history.removeAt(existing)
        history.add(0, entry)
        while (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)
        saveHistory()
    }

    fun clearHistory() {
        history.clear()
        prefs.edit { remove(KEY_HISTORY) }
    }

    private fun saveHistory() {
        val array = JSONArray()
        history.forEach { entry ->
            array.put(
                JSONObject()
                    .put("url", entry.url)
                    .put("title", entry.title)
                    .put("time", entry.visitedAt),
            )
        }
        prefs.edit { putString(KEY_HISTORY, array.toString()) }
    }

    private fun loadHistory() {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = item.optString("url")
            if (url.isEmpty()) continue
            history.add(
                BotBrowserHistory(
                    url = url,
                    title = item.optString("title").ifEmpty { url },
                    visitedAt = item.optLong("time", 0L),
                ),
            )
        }
    }

    // ---- Cookie / 站点数据 --------------------------------------------------

    /** 历史里出现过的域名（设置页按域名清 cookie 用）。 */
    fun cookieDomains(): List<String> = buildList {
        listOfNotNull(currentPage()?.first, activeWebView()?.url)
            .plus(history.map { it.url })
            .forEach { link ->
                if (!link.startsWith("http://") && !link.startsWith("https://")) return@forEach
                val host = runCatching { link.toUri().host }.getOrNull()
                if (!host.isNullOrBlank() && host !in this) add(host)
            }
    }.take(MAX_COOKIE_DOMAINS)

    fun cookiesFor(domain: String): String? =
        CookieManager.getInstance().getCookie("https://$domain")

    fun clearCookiesFor(domain: String) {
        val raw = cookiesFor(domain).orEmpty()
        if (raw.isEmpty()) return
        raw.split(';').forEach { pair ->
            val name = pair.substringBefore('=', "").trim()
            if (name.isNotEmpty()) {
                CookieManager.getInstance().setCookie("https://$domain", "$name=; Max-Age=0; Path=/")
            }
        }
        CookieManager.getInstance().flush()
    }

    fun clearAllCookies() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    fun clearCache() {
        views.values.forEach { view -> runCatching { view.clearCache(true) } }
    }

    /** 「清除数据」：cookie + WebStorage + 缓存。 */
    fun clearSiteData() {
        clearAllCookies()
        runCatching { WebStorage.getInstance().deleteAllData() }
        clearCache()
    }

    // ---- 缩略图 -------------------------------------------------------------

    /**
     * 给聊天页的页面缩略图：当前标签按 [maxWidth] 等比缩放。
     *
     * 面板没开着时 WebView 没有尺寸，先按 1080×1920 量一次再画（与
     * `browser_screenshot` 的做法一致）。整张图几乎只有一种颜色时（常见于合成失败）
     * 返回 null，宁可不贴卡也不贴一张白图。
     */
    fun captureThumbnail(maxWidth: Int = 520): Bitmap? {
        val view = activeWebView() ?: return null
        var width = view.width
        var height = view.height
        if (width <= 0 || height <= 0) {
            width = 1080
            height = 1920
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, width, height)
        }
        val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(full))
        if (isFlat(full)) {
            full.recycle()
            return null
        }
        val scale = (maxWidth.toFloat() / width).coerceAtMost(1f)
        val scaled = Bitmap.createScaledBitmap(
            full,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== full) full.recycle()
        return scaled
    }

    /** 抽样看颜色种类：页面缩略图不可能整张只有一两种颜色。 */
    private fun isFlat(bitmap: Bitmap): Boolean {
        val colors = HashSet<Int>()
        var x = 0
        while (x < bitmap.width) {
            var y = 0
            while (y < bitmap.height) {
                colors += bitmap.getPixel(x, y)
                if (colors.size > 12) return false
                y += 24
            }
            x += 24
        }
        return true
    }

    /**
     * 读当前页：`evaluateJavascript` 在 JS 侧拼一份 Markdown 和控件清单。
     *
     * 返回值直接是 `browser-result` 要的 `page`（DS Bot `pageText` 的输入）。
     */
    suspend fun readActivePage(): JSONObject? {
        val view = activeWebView() ?: return null
        val raw = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            runCatching {
                view.evaluateJavascript(READ_PAGE_SCRIPT) { value ->
                    if (continuation.isActive) continuation.resumeWith(Result.success(value))
                }
            }.onFailure { error ->
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }
        }
        return decodeJsJson(raw)
    }

    private fun createWebView(id: Long): WebView {
        val view = WebView(context)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        userAgentOverride?.let { view.settings.userAgentString = it }
        applyViewport(view)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (loadingTabId == id) {
                    loadingTabId = null
                    loading = false
                }
                update(id) { it.copy(url = view.url.orEmpty(), title = view.title.orEmpty()) }
                rememberVisit(url, view.title)
                if (id == activeId) syncNavigation()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                update(id) { it.copy(url = url.orEmpty(), title = view.title.orEmpty()) }
                rememberVisit(url, view.title)
                if (id == activeId) syncNavigation()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest,
            ): Boolean {
                val rewritten = RootfsWeb.urlFor(context, request.url.toString())
                if (rewritten != null && rewritten != request.url.toString()) {
                    view.loadUrl(rewritten)
                    return true
                }
                val scheme = request.url.scheme.orEmpty()
                if (scheme == "http" || scheme == "https" || scheme == "about" ||
                    scheme == "data" || scheme == RootfsWeb.SCHEME
                ) {
                    return false
                }
                // 非网页 scheme 交给系统（mailto: / tel: / 应用深链）。
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? =
                RootfsWeb.intercept(context, request.url)

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                loadingTabId = id
                loading = true
                update(id) { it.copy(url = url.orEmpty()) }
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                update(id) { it.copy(title = title.orEmpty()) }
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                val newId = newTab()
                val created = views[newId] ?: return false
                (resultMsg.obj as? WebView.WebViewTransport)?.webView = created
                resultMsg.sendToTarget()
                return true
            }
        }
        view.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            runCatching {
                if (url.startsWith("${RootfsWeb.SCHEME}://")) {
                    downloadRootfsFile(url, contentDisposition, mimeType)
                } else {
                    val name = downloadName(url)
                    val request = DownloadManager.Request(url.toUri())
                        .setMimeType(mimeType)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setTitle(name)
                        // 落到应用自己的下载目录：不需要存储权限，也不会把文件写丢。
                        .setDestinationInExternalFilesDir(
                            context,
                            Environment.DIRECTORY_DOWNLOADS,
                            name,
                        )
                    CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
                    userAgent?.let { request.addRequestHeader("User-Agent", it) }
                    val id = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                    updateDownload(BotDownload(id, name, 0f, done = false))
                }
            }
        }
        return view
    }

    private fun update(id: Long, change: (BotBrowserTab) -> BotBrowserTab) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        tabs[index] = change(tabs[index])
    }

    private fun syncNavigation() {
        val view = activeWebView()
        canGoBack = view?.canGoBack() == true
        canGoForward = view?.canGoForward() == true
    }

    private fun startLoad(view: WebView, raw: String) {
        loadingTabId = activeId
        loading = true
        view.loadUrl(fixedUrl(raw))
    }

    private fun fixedUrl(raw: String): String {
        RootfsWeb.urlFor(context, raw)?.let { return it }
        return when {
            raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("about:") ||
                raw.startsWith("data:") -> raw
            raw.contains(' ') || !raw.contains('.') ->
                "https://www.bing.com/search?q=" + Uri.encode(raw)
            else -> "https://$raw"
        }
    }

    private fun downloadRootfsFile(url: String, contentDisposition: String?, mimeType: String?) {
        val source = RootfsWeb.fileFor(context, url.toUri()) ?: return
        val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            .ifBlank { downloadName(url) }
        val id = -System.nanoTime()
        val initial = BotDownload(id, name, 0f, done = false)
        localDownloads[id] = initial
        updateDownload(initial)
        thread(name = "bot-local-download") {
            val done = runCatching {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                dir.mkdirs()
                val target = uniqueDownload(dir, name)
                source.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }.fold(
                onSuccess = { BotDownload(id, name, 1f, done = true) },
                onFailure = { BotDownload(id, name, 0f, done = true, failed = true) },
            )
            localDownloads[id] = done
            Handler(Looper.getMainLooper()).post {
                if (download?.id == id) download = done
                Handler(Looper.getMainLooper()).postDelayed(
                    { localDownloads.remove(id) },
                    15_000,
                )
            }
        }
    }

    private fun uniqueDownload(dir: java.io.File, name: String): java.io.File {
        val first = java.io.File(dir, name)
        if (!first.exists()) return first
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var index = 2
        while (true) {
            val candidate = java.io.File(dir, "$stem ($index)$ext")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun isPageUrl(url: String): Boolean =
        url.startsWith("http://") ||
            url.startsWith("https://") ||
            url.startsWith("${RootfsWeb.SCHEME}://")

    private fun downloadName(url: String): String =
        url.toUri().lastPathSegment?.takeIf { it.isNotEmpty() } ?: "download"

    companion object {
        /** 每个 Bot 最多同时开几张标签（DOM/内存的折中）。 */
        const val MAX_TABS = 6

        /** 每个 Bot 留多少条浏览历史。 */
        const val MAX_HISTORY = 200

        private const val MAX_COOKIE_DOMAINS = 30
        private const val KEY_UA = "ua"
        private const val KEY_VIEWPORT = "viewport"
        private const val KEY_HISTORY = "history"
    }
}

/** 全局的 Bot 浏览器池：所有 Bot 的标签都留在这里，切页面不丢。 */
class BotBrowserStore internal constructor(private val context: Context) {
    private val sessions = mutableMapOf<String, BotBrowserSession>()

    fun session(botId: String): BotBrowserSession =
        sessions.getOrPut(botId) { BotBrowserSession(botId, context) }

    /**
     * 预热 WebView 引擎（只做一次）。
     *
     * 冷启动后**第一个** `WebView(context)` 要初始化 Chromium（类加载 + 原生库），
     * 在 UI 线程上就是几百毫秒的卡顿；预热把它挪到启动后的一帧里，用户点开浏览器
     * 分类时就不用再承担这次开销。只建视图、不加载页面，建完立刻销毁。
     */
    fun warmUp() {
        if (warmed) return
        warmed = true
        runCatching {
            WebView(context).destroy()
        }
    }

    /** 预热只做一次；失败也不重试（引擎起不来时反复建更糟）。 */
    private var warmed = false

    fun existing(botId: String): BotBrowserSession? = sessions[botId]

    /** 给 `browser-wait` 报的 `open`：每个 Bot 的当前页。 */
    fun openPages(): JSONObject {
        val open = JSONObject()
        sessions.forEach { (botId, session) ->
            val page = session.currentPage() ?: return@forEach
            open.put(
                botId,
                JSONObject().put("url", page.first).put("title", page.second),
            )
        }
        return open
    }

    /**
     * 把一张截图存进 Bot 的工作区，返回**引擎里**的路径。
     *
     * 不能存应用私有目录：那是 rootfs 外面，引擎和 Bot 都读不到，拿到的路径等于废的。
     * 存到团队工作区之后，Bot 能自己读，也能用 `present` 当产物交付。
     */
    fun saveScreenshot(botId: String, bitmap: Bitmap): String? = runCatching {
        val dir = java.io.File(RuntimeInstaller(context).rootfsDir, "root/.dsh/bot/workspace/shots")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val name = "shot-$botId-${System.currentTimeMillis()}.png"
        val file = java.io.File(dir, name)
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 90, out) }
        "/root/.dsh/bot/workspace/shots/$name"
    }.getOrNull()

    companion object {
        @Volatile
        private var store: BotBrowserStore? = null

        fun of(context: Context): BotBrowserStore =
            store ?: synchronized(this) {
                store ?: BotBrowserStore(context.applicationContext).also { store = it }
            }
    }
}

/** `evaluateJavascript` 把 JS 的返回串又编码了一层；拆两层拿到对象。 */
internal fun decodeJsJson(raw: String?): JSONObject? {
    if (raw == null || raw == "null" || raw.isEmpty()) return null
    val inner = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: raw
    return runCatching { JSONObject(inner) }.getOrNull()
}

/**
 * 读页面：输出 DS Bot `pageText` 认识的 `{url,title,format:'markdown',text,items,…}`。
 *
 * `[#n]` 标在正文里，`items[n-1]` 是它的目标；只读可见内容，不碰脚本/样式/canvas。
 */
private const val READ_PAGE_SCRIPT = """
(function () {
  try {
    var MAX_TEXT = 60000;
    var MAX_ITEMS = 400;
    var out = [];
    var length = 0;
    var truncated = false;
    var items = [];
    var SKIP = {'SCRIPT':1,'STYLE':1,'NOSCRIPT':1,'TEMPLATE':1,'SVG':1,'CANVAS':1,'VIDEO':1,'AUDIO':1,'IFRAME':1,'LINK':1,'META':1};
    function flat(t) { return String(t == null ? '' : t).replace(/\s+/g, ' ').trim(); }
    function push(t) {
      if (length > MAX_TEXT) { truncated = true; return; }
      out.push(t);
      length += t.length;
    }
    function visible(el) {
      try {
        var st = window.getComputedStyle(el);
        if (st.display === 'none' || st.visibility === 'hidden' || Number(st.opacity) === 0) return false;
        if (el.getAttribute && el.getAttribute('aria-hidden') === 'true') return false;
      } catch (e) { return true; }
      return true;
    }
    function absolute(href) { try { return new URL(href, location.href).href; } catch (e) { return href; } }
    function mark(item) {
      if (items.length >= MAX_ITEMS) return '';
      items.push(item);
      return '[#' + items.length + ']';
    }
    function isHeading(tag) { return tag.length === 2 && tag.charAt(0) === 'H' && '123456'.indexOf(tag.charAt(1)) >= 0; }
    function inline(node) {
      if (!node) return '';
      if (node.nodeType === 3) return flat(node.nodeValue) + ' ';
      if (node.nodeType !== 1) return '';
      var tag = node.tagName;
      if (SKIP[tag] || !visible(node)) return '';
      if (tag === 'BR') return ' ';
      if (tag === 'IMG') {
        var alt = node.getAttribute('alt') || node.getAttribute('title') || '';
        return alt ? '[image: ' + flat(alt) + '] ' : '';
      }
      if (tag === 'A') {
        var href = node.getAttribute('href');
        var label = flat(node.innerText) || node.getAttribute('aria-label') || node.getAttribute('title') || href || '';
        if (!href) return label + ' ';
        return mark({role: 'link', name: label, href: absolute(href)}) + ' ' + label + ' ';
      }
      if (tag === 'BUTTON') {
        var name = flat(node.innerText) || node.getAttribute('aria-label') || node.getAttribute('title') || '';
        return mark({role: 'button', name: name, disabled: !!node.disabled}) + ' ' + name + ' ';
      }
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') {
        var kind = tag === 'INPUT' ? (node.getAttribute('type') || 'text') : tag.toLowerCase();
        var name2 = node.getAttribute('aria-label') || node.getAttribute('placeholder') || node.getAttribute('name') || '';
        var value = tag === 'TEXTAREA' ? flat(node.value) : (tag === 'SELECT' ? flat(node.options && node.selectedIndex >= 0 ? node.options[node.selectedIndex].text : '') : flat(node.value));
        var entry = {role: kind, name: flat(name2)};
        if (kind === 'checkbox' || kind === 'radio') entry.checked = !!node.checked;
        if (node.disabled) entry.disabled = true;
        if (value) entry.value = value;
        return mark(entry) + ' ' + (flat(name2) || kind) + ' ';
      }
      var text = '';
      for (var child = node.firstChild; child; child = child.nextSibling) text += inline(child);
      return text;
    }
    function hasBlockChild(el) {
      for (var child = el.firstElementChild; child; child = child.nextElementSibling) {
        if (SKIP[child.tagName] || !visible(child)) continue;
        var display = '';
        try { display = window.getComputedStyle(child).display; } catch (e) {}
        if (display !== 'inline' && display !== 'inline-block' && display !== 'contents' && display !== 'none') return true;
      }
      return false;
    }
    function table(el) {
      var rows = el.querySelectorAll('tr');
      var lines = [];
      var width = 0;
      for (var r = 0; r < rows.length && r < 40; r++) {
        var cells = rows[r].querySelectorAll('th,td');
        if (cells.length === 0) continue;
        var line = [];
        for (var c = 0; c < cells.length; c++) line.push(flat(inline(cells[c])));
        width = Math.max(width, line.length);
        lines.push(line);
      }
      if (lines.length === 0) return;
      for (var i = 0; i < lines.length; i++) {
        while (lines[i].length < width) lines[i].push('');
      }
      push('\n| ' + lines[0].join(' | ') + ' |\n');
      var sep = [];
      for (var s = 0; s < width; s++) sep.push('---');
      push('| ' + sep.join(' | ') + ' |\n');
      for (var j = 1; j < lines.length; j++) push('| ' + lines[j].join(' | ') + ' |\n');
      push('\n');
    }
    function walk(node) {
      for (var child = node.firstChild; child; child = child.nextSibling) {
        if (child.nodeType === 3) {
          var raw = flat(child.nodeValue);
          if (raw) push(raw + ' ');
          continue;
        }
        if (child.nodeType !== 1) continue;
        var tag = child.tagName;
        if (SKIP[tag] || !visible(child)) continue;
        if (isHeading(tag)) {
          var level = Number(tag.charAt(1));
          push('\n' + new Array(level + 1).join('#') + ' ' + flat(inline(child)) + '\n\n');
          continue;
        }
        if (tag === 'LI') { push('\n- ' + flat(inline(child)) + '\n'); continue; }
        if (tag === 'BLOCKQUOTE') { push('\n> ' + flat(inline(child)) + '\n\n'); continue; }
        if (tag === 'PRE') {
          var code = String(child.innerText || child.textContent || '').replace(/\n{3,}/g, '\n\n').trim();
          if (code) push('\n```\n' + code + '\n```\n\n');
          continue;
        }
        if (tag === 'TABLE') { table(child); continue; }
        if (tag === 'HR') { push('\n\n'); continue; }
        var display = '';
        try { display = window.getComputedStyle(child).display; } catch (e) {}
        var block = display !== 'inline' && display !== 'inline-block' && display !== 'contents';
        if (block && hasBlockChild(child)) { walk(child); continue; }
        if (block) {
          var text = flat(inline(child));
          if (text) push('\n' + text + '\n');
          continue;
        }
        walk(child);
      }
    }
    if (document.body) walk(document.body);
    var description = '';
    var meta = document.querySelector('meta[name="description"]');
    if (meta) description = flat(meta.getAttribute('content'));
    var selection = '';
    try { selection = flat(window.getSelection ? String(window.getSelection()) : ''); } catch (e) {}
    var text = out.join('').replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
    if (text.length > MAX_TEXT) { text = text.slice(0, MAX_TEXT); truncated = true; }
    return JSON.stringify({
      url: location.href,
      title: document.title || '',
      description: description,
      selection: selection,
      format: 'markdown',
      text: text,
      items: items,
      truncated: truncated
    });
  } catch (error) {
    return JSON.stringify({url: location.href, title: document.title || '', text: '', items: [], error: String(error)});
  }
})()
"""
