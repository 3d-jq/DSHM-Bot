package com.dshm.bot.ui.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 点 / 填之前的守卫：密码框拒绝，危险按钮先问。 */
class BrowserGuardTest {

    private fun target(vararg pairs: Pair<String, Any>) = JSONObject().apply {
        put("found", true)
        pairs.forEach { (key, value) -> put(key, value) }
    }

    @Test
    fun refusesPasswordAndCodeFields() {
        assertTrue(
            BrowserGuardRules.verdict("type", target("type" to "password")) is GuardVerdict.Refuse,
        )
        assertTrue(
            BrowserGuardRules.verdict(
                "type",
                target("name" to "otp", "placeholder" to "验证码"),
            ) is GuardVerdict.Refuse,
        )
        assertTrue(
            BrowserGuardRules.verdict(
                "type",
                target("autocomplete" to "current-password"),
            ) is GuardVerdict.Refuse,
        )
    }

    @Test
    fun allowsOrdinaryFields() {
        assertEquals(
            GuardVerdict.Proceed,
            BrowserGuardRules.verdict(
                "type",
                target("name" to "q", "placeholder" to "搜索"),
            ),
        )
        // 邮编、优惠码不是密码。
        assertEquals(
            GuardVerdict.Proceed,
            BrowserGuardRules.verdict(
                "type",
                target("name" to "zip", "placeholder" to "Postal code"),
            ),
        )
    }

    @Test
    fun asksBeforeDangerousClicks() {
        assertTrue(
            BrowserGuardRules.verdict("click", target("label" to "立即支付")) is GuardVerdict.Ask,
        )
        assertTrue(
            BrowserGuardRules.verdict("click", target("text" to "Delete account")) is GuardVerdict.Ask,
        )
        assertTrue(
            BrowserGuardRules.verdict("click", target("value" to "Send")) is GuardVerdict.Ask,
        )
    }

    @Test
    fun allowsOrdinaryClicksAndMissingElements() {
        assertEquals(
            GuardVerdict.Proceed,
            BrowserGuardRules.verdict("click", target("label" to "下一页")),
        )
        assertEquals(
            GuardVerdict.Proceed,
            BrowserGuardRules.verdict("click", JSONObject().put("found", false)),
        )
    }
}
