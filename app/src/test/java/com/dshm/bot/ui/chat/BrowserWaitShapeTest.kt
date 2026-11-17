package com.dshm.bot.ui.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * `browser-wait` 长轮询结果的形状（[browserWaitRequests]）。
 *
 * 这里是真机上断过的一次链路：0.3.6 把返回值从裸数组改成 `{requests, holds}`，
 * 我们照旧 `as? JSONArray` → 每一轮都拿到 null → 当成「插件没开」退避重试 →
 * App 明明在轮询，Bot 的每个浏览器工具都只等到「The app did not answer in time」。
 */
class BrowserWaitShapeTest {

    @Test
    fun readsTheRequestsOutOfTheCurrentEnvelope() {
        val requests = JSONArray().put(JSONObject().put("id", "open-1").put("kind", "open"))
        val value = JSONObject().put("requests", requests).put("holds", JSONObject())

        val parsed = browserWaitRequests(value)

        assertEquals(1, parsed?.length())
        assertEquals("open", parsed?.optJSONObject(0)?.optString("kind"))
    }

    @Test
    fun stillAcceptsTheBareArrayOlderEnginesSend() {
        val requests = JSONArray().put(JSONObject().put("id", "read-1").put("kind", "read"))
        assertSame(requests, browserWaitRequests(requests))
    }

    @Test
    fun anEnvelopeWithoutRequestsIsNotAnEmptyPoll() {
        // 形状认不出来必须回 null（→ 退避重试），不能回空数组：
        // 空数组等于「这轮没有请求」，会把已经下发的请求悄悄吞掉。
        assertNull(browserWaitRequests(JSONObject().put("holds", JSONObject())))
        assertNull(browserWaitRequests(null))
        assertNull(browserWaitRequests("oops"))
    }
}
