package com.dshm.bot.ui.chat

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** 一个浏览器标签（标题 / 地址随 WebView 回调更新）。 */
data class BotBrowserTab(
    val id: Long,
    val url: String = "",
    val title: String = "",
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

    private val views = mutableMapOf<Long, WebView>()
    private var nextId = 1L

    fun activeTab(): BotBrowserTab? = tabs.firstOrNull { it.id == activeId }

    fun activeWebView(): WebView? = activeId?.let { views[it] }

    fun select(id: Long) {
        if (tabs.none { it.id == id }) return
        activeId = id
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
        if (url.isNotEmpty()) view.loadUrl(fixedUrl(url))
        return id
    }

    fun closeTab(id: Long) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        tabs.removeAt(index)
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
        view.loadUrl(fixedUrl(text))
    }

    fun reload() {
        activeWebView()?.reload()
    }

    fun goBack() {
        activeWebView()?.let { view -> if (view.canGoBack()) view.goBack() }
    }

    fun goForward() {
        activeWebView()?.let { view -> if (view.canGoForward()) view.goForward() }
    }

    /** 报告给引擎的「当前页」：只有真的载入了 http(s) 页面才算。 */
    fun currentPage(): Pair<String, String>? {
        val tab = activeTab() ?: return null
        val url = tab.url
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return url to tab.title
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
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                update(id) { it.copy(url = view.url.orEmpty(), title = view.title.orEmpty()) }
                if (id == activeId) syncNavigation()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                update(id) { it.copy(url = url.orEmpty(), title = view.title.orEmpty()) }
                if (id == activeId) syncNavigation()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest,
            ): Boolean {
                val scheme = request.url.scheme.orEmpty()
                if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data") {
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
                val name = downloadName(url)
                val request = DownloadManager.Request(Uri.parse(url))
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
                (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
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

    private fun fixedUrl(raw: String): String = when {
        raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("about:") ||
            raw.startsWith("data:") -> raw
        raw.contains(' ') || !raw.contains('.') ->
            "https://www.bing.com/search?q=" + Uri.encode(raw)
        else -> "https://$raw"
    }

    private fun downloadName(url: String): String =
        Uri.parse(url).lastPathSegment?.takeIf { it.isNotEmpty() } ?: "download"

    companion object {
        /** 每个 Bot 最多同时开几张标签（DOM/内存的折中）。 */
        const val MAX_TABS = 6
    }
}

/** 全局的 Bot 浏览器池：所有 Bot 的标签都留在这里，切页面不丢。 */
class BotBrowserStore internal constructor(private val context: Context) {
    private val sessions = mutableMapOf<String, BotBrowserSession>()

    fun session(botId: String): BotBrowserSession =
        sessions.getOrPut(botId) { BotBrowserSession(botId, context) }

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
