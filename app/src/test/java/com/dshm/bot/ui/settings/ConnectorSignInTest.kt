package com.dshm.bot.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OAuth 登录闭环的关键判定：[isConnectorCallback] 决定 WebView 里哪些跳转要拦下来
 * 交给宿主（`connector-sign-in-code`），哪些要照常加载。
 *
 * 只认 loopback + `/callback`：授权页自己跳到别的 http 地址（帮助文档、协议页）
 * 要继续加载，不能一律拦掉 —— 否则闭环是闭环了，页面却死在错误里。
 */
class ConnectorSignInTest {

    @Test
    fun recognizesCallbackAddressesHostListensFor() {
        assertTrue(isConnectorCallback("http://127.0.0.1:38517/callback?code=abc&state=xyz"))
        assertTrue(isConnectorCallback("http://127.0.0.1:38517/callback"))
        assertTrue(isConnectorCallback("http://localhost:41796/callback?code=c1"))
        // 端口可变，路径才是判据。
        assertTrue(isConnectorCallback("http://127.0.0.1:9999/callback?code=1"))
    }

    @Test
    fun letsAuthorizationPagesThrough() {
        // 正常网页、授权页自己的跳转 —— 都不能拦。
        assertFalse(isConnectorCallback("https://github.com/login/oauth/authorize?client_id=x"))
        assertFalse(isConnectorCallback("http://example.com/terms"))
        // 非 callback 路径的 loopback 也不拦（虽然少见）：路径必须精确。
        assertFalse(isConnectorCallback("http://127.0.0.1:38517/other"))
        assertFalse(isConnectorCallback("http://127.0.0.1:38517/callbackHijack"))
    }

    @Test
    fun fallsBackToNonLoopbackOrEmpty() {
        assertFalse(isConnectorCallback(""))
        assertFalse(isConnectorCallback("https://127.0.0.1:38517/callback"))
        assertFalse(isConnectorCallback("http://192.168.1.10:38517/callback"))
    }
}