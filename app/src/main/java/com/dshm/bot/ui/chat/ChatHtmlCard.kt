package com.dshm.bot.ui.chat

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.core.design.DshmColors
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * 视图蓝图里的 `html` 组件。
 *
 * DS Bot 把它放进一个沙箱 iframe（`deep-view` 的 `frameDocument`）：脚本可跑、网络全禁、
 * 主题色以 `--view-*` 变量注入。手机上等价的实现是 WebView —— 同样禁网、禁文件、
 * 禁跳转，只从 `loadDataWithBaseURL(null, …)` 加载这一份文档。
 *
 * 模型爱用纯 CSS 画 chart（数组参数被 provider 的编码改写过时这是唯一的退路），
 * 所以这里必须真的把 HTML 画出来，只显示 `summary` 是不够的。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ChatHtmlFrame(
    html: String,
    heightDp: Float?,
    modifier: Modifier = Modifier,
) {
    val colors = Dshm.colors
    val document = remember(html, colors) { chatHtmlDocument(html, colors) }
    var height by remember(html) {
        mutableStateOf(heightDp?.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_HTML_HEIGHT_DP)
    }
    val main = remember { Handler(Looper.getMainLooper()) }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isFocusable = false
                isFocusableInTouchMode = false
                isClickable = false
                // 关掉输入：卡片自己不需要点，手势要留给外面的 dock 滚动。
                isEnabled = false
                settings.apply {
                    javaScriptEnabled = true
                    blockNetworkLoads = true
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    databaseEnabled = false
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    mediaPlaybackRequiresUserGesture = true
                    textZoom = 100
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = true

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        // 只放行内联的东西；外面的一律回空，别让卡片偷偷发请求。
                        val scheme = request.url.scheme.orEmpty()
                        if (scheme == "data" || scheme == "about") return null
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            ByteArrayInputStream(ByteArray(0)),
                        )
                    }
                }
                addJavascriptInterface(
                    HtmlHeightBridge { px ->
                        main.post {
                            val clamped = px.toFloat().coerceIn(MIN_HTML_HEIGHT_DP, MAX_HTML_HEIGHT_DP)
                            if (clamped != height) height = clamped
                        }
                    },
                    HTML_HEIGHT_BRIDGE,
                )
                tag = document
                loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
            }
        },
        update = { view ->
            if (view.tag != document) {
                view.tag = document
                view.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
            }
        },
        onRelease = { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        },
    )
}

/** 卡片自己的高度（CSS px ≈ dp）；没写就照 DS Bot 的默认 120。 */
internal fun chatHtmlHeight(node: org.json.JSONObject): Float? {
    val height = node.optDouble("height", Double.NaN)
    return height.takeIf { it.isFinite() && it > 0.0 }?.toFloat()
}

/** 文档里的名字，和 DS Bot 的 `frameDocument` 一致。 */
private const val HTML_HEIGHT_BRIDGE = "DshmView"
private const val DEFAULT_HTML_HEIGHT_DP = 120f
private const val MIN_HTML_HEIGHT_DP = 24f
private const val MAX_HTML_HEIGHT_DP = 640f

/** DS Bot `FRAME_POLICY`：脚本和样式只许内联，网络/表单/基底全禁。 */
private const val FRAME_POLICY =
    "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
        "img-src data: blob:; font-src data:; media-src data: blob:; " +
        "form-action 'none'; base-uri 'none'"

/**
 * 把模型写的片段包成完整文档：CSP 在标记之前、主题变量和高度上报脚本在 `<head>` 里。
 *
 * 上游 `frameDocument` 的变量名是 `--view-*`（旧蓝图用 `--bt-*`），两个都注入。
 */
internal fun chatHtmlDocument(html: String, colors: DshmColors): String {
    val vars = buildString {
        fun put(name: String, color: Color) {
            val value = cssColor(color)
            append(name).append(':').append(value).append(';')
            append(name.replace("--view-", "--bt-")).append(':').append(value).append(';')
        }
        put("--view-ink", colors.textPrimary)
        put("--view-ink-2", colors.textSecondary)
        put("--view-ink-3", colors.textTertiary)
        put("--view-line", colors.divider)
        put("--view-line-2", colors.divider)
        put("--view-card", colors.surface)
        put("--view-main", colors.surfaceMuted)
        put("--view-hover", colors.surfaceMuted)
        put("--view-accent", colors.accent)
        put("--view-accent-ink", colors.onAccent)
        put("--view-green", colors.success)
        put("--view-star", colors.warning)
        put("--view-danger", colors.danger)
        append("color-scheme:").append(if (colors.isDark) "dark" else "light").append(';')
    }
    val report = "(()=>{const h=()=>Math.max(document.documentElement?document.documentElement.scrollHeight:0," +
        "document.body?document.body.scrollHeight:0);" +
        "const send=()=>{try{$HTML_HEIGHT_BRIDGE.report(h())}catch(_){}};" +
        "if(document.documentElement&&typeof ResizeObserver==='function'){" +
        "new ResizeObserver(send).observe(document.documentElement)}" +
        "addEventListener('load',send);send()})()"
    return buildString {
        append("<!doctype html><html><head><meta charset=\"utf-8\">")
        append("<meta http-equiv=\"Content-Security-Policy\" content=\"").append(FRAME_POLICY).append("\">")
        append("<style>:root{").append(vars).append('}')
        append("html,body{margin:0;padding:0;background:transparent;")
        append("color:var(--view-ink,var(--bt-ink));")
        append("font:13px/18px -apple-system,BlinkMacSystemFont,\"Segoe UI\",system-ui,sans-serif}")
        append("*{box-sizing:border-box}svg,canvas,img{max-width:100%}</style>")
        append("<script>").append(report).append("</script></head><body>")
        append(html)
        append("</body></html>")
    }
}

/** `#RRGGBB`（透明度丢掉：卡片底下就是我们的白卡）。 */
private fun cssColor(color: Color): String {
    val argb = color.toArgb()
    return String.format(Locale.ROOT, "#%06X", 0xFFFFFF and argb)
}

/** JS 侧的高度上报（只暴露这一个方法）。 */
internal class HtmlHeightBridge(private val onHeight: (Int) -> Unit) {
    @JavascriptInterface
    fun report(px: Int) {
        if (px >= 0) onHeight(px)
    }
}
