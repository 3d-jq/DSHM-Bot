package com.dshm.bot.ui.settings

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.runtime.RuntimeEndpoint

/**
 * 网页模式：用 WebView 承载本机 DSH 的 Web 界面。
 * 先打开带 token 的地址换取 `dsh-auth` cookie，再跳到 Web UI。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebHostScreen(endpoint: RuntimeEndpoint?, modifier: Modifier = Modifier) {
    if (endpoint == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Dshm.colors.pageBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.chat_engine_off_title),
                style = Dshm.typography.emptyTitle,
                color = Dshm.colors.textPrimary,
            )
        }
        return
    }

    val target = remember(endpoint) { endpoint.baseUrl + "?token=" + endpoint.token }
    var loading by remember(endpoint) { mutableStateOf(true) }
    var errorMessage by remember(endpoint) { mutableStateOf<String?>(null) }
    var reloadKey by remember(endpoint) { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Dshm.colors.pageBackground),
    ) {
        key(reloadKey) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    if (com.dshm.bot.BuildConfig.DEBUG) {
                        WebView.setWebContentsDebuggingEnabled(true)
                    }
                    WebView(context).apply {
                        setBackgroundColor(android.graphics.Color.WHITE)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            builtInZoomControls = false
                            displayZoomControls = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            cacheMode = WebSettings.LOAD_DEFAULT
                            // 本机 127.0.0.1 是 HTTP；部分系统 WebView 会拦明文，显式允许。
                            allowContentAccess = true
                        }
                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                        }
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                Log.i("DshWeb", "page started: $url")
                                loading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                Log.i("DshWeb", "page finished: $url")
                                loading = false
                                CookieManager.getInstance().flush()
                                view?.let(::fitWebViewport)
                            }

                            override fun onPageCommitVisible(view: WebView?, url: String?) {
                                Log.i("DshWeb", "commit visible: $url")
                                view?.let(::fitWebViewport)
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                Log.w(
                                    "DshWeb",
                                    "resource error ${request.url} ${error.errorCode} ${error.description}",
                                )
                                if (!request.isForMainFrame) return
                                loading = false
                                errorMessage = error.description?.toString() ?: "net::ERR_FAILED"
                            }

                            override fun onReceivedHttpError(
                                view: WebView,
                                request: WebResourceRequest,
                                response: WebResourceResponse,
                            ) {
                                Log.w("DshWeb", "http error ${response.statusCode} ${request.url}")
                                if (!request.isForMainFrame) return
                                if (response.statusCode >= 400) {
                                    loading = false
                                    errorMessage = "HTTP ${response.statusCode}"
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean = false
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                Log.i("DshWeb", "progress $newProgress")
                            }

                            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                Log.i(
                                    "DshWeb",
                                    "${message.message()} (${message.sourceId()}:${message.lineNumber()})",
                                )
                                return true
                            }
                        }
                        webView = this
                        loadUrl(target)
                    }
                },
            )
        }

        if (loading && errorMessage == null) {
            CircularProgressIndicator(
                color = Dshm.colors.accent,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(Dshm.spacing.lg),
            )
        }

        errorMessage?.let { message ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = Dshm.spacing.xxl)
                    .clip(RoundedCornerShape(Dshm.radius.lg))
                    .background(Dshm.colors.surface)
                    .padding(Dshm.spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
            ) {
                Text(
                    text = stringResource(R.string.web_load_failed),
                    style = Dshm.typography.emptyTitle,
                    color = Dshm.colors.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = message,
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.chat_retry),
                    style = Dshm.typography.rowTitle,
                    color = Dshm.colors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Dshm.radius.pill))
                        .clickable {
                            errorMessage = null
                            loading = true
                            reloadKey += 1
                        }
                        .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
                )
            }
        }
    }
}

/**
 * DSH Web 的 `html, body, #root { height: 100% }` 在部分 WebView 上会按 0 高度解析
 * （首次布局时 WebView 还没拿到尺寸），页面就只剩一层空白。页面加载后把视口高度
 * 显式写回去，并在尺寸变化时重算。
 */
private fun fitWebViewport(view: WebView) {
    view.evaluateJavascript(
        "(function(){function f(){var h=window.innerHeight;" +
            "var d=document.documentElement,b=document.body,r=document.getElementById('root');" +
            "[d,b,r].forEach(function(e){if(e)e.style.setProperty('height',h+'px','important');});}" +
            "f();window.addEventListener('resize',f);setTimeout(f,150);setTimeout(f,700);})();",
        null,
    )
}
