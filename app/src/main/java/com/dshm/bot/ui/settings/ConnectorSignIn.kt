package com.dshm.bot.ui.settings

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.ui.components.FloatingIconButton

/**
 * 连接器登录的内嵌页（OAuth 跳转 / 设备码验证页共用）。
 *
 * 桌面版是弹一个窗口；手机上以前直接 `ACTION_VIEW` 丢给系统浏览器，登录完还得把
 * 跳转地址复制回来贴进「粘贴你跳转到的地址」—— 用户说的「没有完整闭环」就是这个。
 *
 * 现在的闭环：
 * 1. 在内嵌 WebView 里打开授权页，用户就在这里登录；
 * 2. 宿主在 rootfs 里监听 `http://127.0.0.1:<port>/callback`，WebView 一旦要跳过去
 *    就自己拦下来，把地址交给宿主（`connector-sign-in-code`），不真的发这个请求；
 * 3. 万一没拦到（服务端 302 直接打到了宿主的监听上），外部每两秒重读一次 `state`，
 *    连接器变「已连接」时这个页面也会自动关掉 —— 两条路都能收尾。
 */
@Composable
internal fun ConnectorSignInScreen(
    title: String,
    url: String,
    onCallback: (String) -> Unit,
    onClose: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    // 回调可能被 shouldOverrideUrlLoading / onPageStarted 各报一次，只交一次。
    var delivered by remember(url) { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = true) {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onClose()
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
        ) {
            FloatingIconButton(
                icon = R.drawable.ic_lucide_x,
                contentDescription = stringResource(R.string.action_cancel),
                onClick = onClose,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = Dshm.spacing.iconButton * 2),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = Dshm.typography.pageTitle,
                    color = Dshm.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.team_connectors_sign_in_hint),
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (loading) {
                LinearProgressIndicator(
                    color = Dshm.colors.accent,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                )
            }
        }
        ConnectorSignInWebView(
            url = url,
            onLoading = { loading = it },
            onReady = { webView = it },
            onCallback = { target ->
                if (!delivered) {
                    delivered = true
                    onCallback(target)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

/** WebView 本体：页面里的登录、跳转都留在这个应用里。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ConnectorSignInWebView(
    url: String,
    onLoading: (Boolean) -> Unit,
    onReady: (WebView) -> Unit,
    onCallback: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).also { view ->
                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadsImagesAutomatically = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                }
                // 授权页常带第三方登录按钮（Google / GitHub），它们的 cookie 要能进。
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(view, true)
                }
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val target = request.url.toString()
                        if (!isConnectorCallback(target)) return false
                        // 不真的去请求 127.0.0.1 的监听：地址本身就是宿主等的那份回执。
                        onCallback(target)
                        return true
                    }

                    override fun onPageStarted(view: WebView, target: String?, favicon: android.graphics.Bitmap?) {
                        onLoading(true)
                        // 服务端 302 不一定走 shouldOverrideUrlLoading，这里兜一层。
                        if (target != null && isConnectorCallback(target)) {
                            view.stopLoading()
                            onCallback(target)
                        }
                    }

                    override fun onPageFinished(view: WebView, target: String?) {
                        onLoading(false)
                    }
                }
                view.tag = url
                onReady(view)
                view.loadUrl(url)
            }
        },
        update = { view ->
            if (view.tag != url) {
                view.tag = url
                view.loadUrl(url)
            }
        },
        onRelease = { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.destroy()
        },
    )
}

/**
 * 这个地址是不是宿主在等的 OAuth 回调（`http://127.0.0.1:<port>/callback…`）。
 *
 * 只认 loopback + `/callback`：授权页自己跳到别的 http 地址（帮助文档之类）要继续加载，
 * 不能一律拦。
 */
internal fun isConnectorCallback(url: String): Boolean {
    val trimmed = url.trim()
    if (!trimmed.startsWith("http://", ignoreCase = true)) return false
    val rest = trimmed.substring("http://".length)
    val slash = rest.indexOf('/')
    if (slash <= 0) return false
    val authority = rest.substring(0, slash)
    val host = authority.substringBefore(':').lowercase()
    if (host != "127.0.0.1" && host != "localhost") return false
    val path = rest.substring(slash).substringBefore('?').substringBefore('#')
    // `/callback`、`/callback?…`、`/callback#…` 都算；不能是 `/callbackHijack`
    // （前缀命中会误拦）。
    return path == "/callback" || path.startsWith("/callback?") || path.startsWith("/callback#")
}

/** 详情/验证页里「在应用内打开」用的地址校验：只放行 http(s)。 */
internal fun isWebLink(url: String): Boolean {
    val trimmed = url.trim()
    return trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)
}
