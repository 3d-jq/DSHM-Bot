package com.dshm.bot.ui.settings

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.runtime.RuntimeEndpoint

/** 网页模式：用 WebView 承载本机 DSH 的 Web 界面（token URL 会换取 cookie 并跳转）。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebHostScreen(endpoint: RuntimeEndpoint?, modifier: Modifier = Modifier) {
    if (endpoint == null) {
        Text(
            text = "本机引擎未运行",
            style = Dshm.typography.rowPreview,
            color = Dshm.colors.textSecondary,
            modifier = modifier.padding(Dshm.spacing.lg),
        )
        return
    }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                loadUrl(endpoint.baseUrl + "?token=" + endpoint.token)
            }
        },
    )
}
