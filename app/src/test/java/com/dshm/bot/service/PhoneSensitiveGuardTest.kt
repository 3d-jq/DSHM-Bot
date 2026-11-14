package com.dshm.bot.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机操控敏感判定的**纯函数层**：[PhoneControlBridge.isSensitive]、
 * [PhoneControlBridge.probeAt]、[PhoneControlBridge.tapXYRejectionReason]。
 *
 * 这层只吃字符串和整数（不持有 AccessibilityNodeInfo / Rect），JVM 直测；
 * 节点侧的转换（labelOf 的 hintText、Element → SensitiveProbe）依赖 Android 类，
 * 由真机验证。
 */
class PhoneSensitiveGuardTest {

    // ------------------------------------------------------------------
    // isSensitive：词表 + pin/otp 词边界
    // ------------------------------------------------------------------

    @Test
    fun matchesChineseSensitiveWords() {
        for (label in listOf("请输入验证码", "支付密码", "登录密码", "安全码", "付款密码", "确认密码", "口令", "动态令牌")) {
            assertTrue(label, PhoneControlBridge.isSensitive(label))
        }
    }

    @Test
    fun matchesEnglishSensitiveWords() {
        for (label in listOf("Password", "Enter passcode", "verification code", "CVV", "cvc")) {
            assertTrue(label, PhoneControlBridge.isSensitive(label))
        }
    }

    @Test
    fun matchesPinAndOtpWithWordBoundary() {
        // PIN / OTP / pin码 命中
        for (label in listOf("PIN", "pin", "pin码", "请输入PIN码", "OTP", "email otp", "otp-code")) {
            assertTrue(label, PhoneControlBridge.isSensitive(label))
        }
    }

    @Test
    fun doesNotMatchPinInsideOtherWords() {
        // spinner / spinning 里有 "pin"，滚动控件不能误伤
        for (label in listOf("spinner", "spinning", "spin", "macaroon")) {
            assertTrue(label, !PhoneControlBridge.isSensitive(label))
        }
    }

    @Test
    fun normalControlsAreNotSensitive() {
        for (label in listOf("发送", "搜索", "确认", "username", "购物车", "下一页")) {
            assertTrue(label, !PhoneControlBridge.isSensitive(label))
        }
    }

    // ------------------------------------------------------------------
    // probeAt：坐标归属
    // ------------------------------------------------------------------

    private fun probe(
        label: String,
        password: Boolean = false,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): PhoneControlBridge.SensitiveProbe =
        PhoneControlBridge.SensitiveProbe(label, password, left, top, right, bottom)

    @Test
    fun picksSmallestContainingProbe() {
        val probes = listOf(
            probe("卡片", left = 0, top = 0, right = 400, bottom = 400),
            probe("按钮", left = 10, top = 10, right = 100, bottom = 50),
        )
        assertEquals("按钮", PhoneControlBridge.probeAt(probes, 50, 30)?.label)
        assertEquals("卡片", PhoneControlBridge.probeAt(probes, 350, 350)?.label)
    }

    @Test
    fun missesOutsideAndOnEdges() {
        val probes = listOf(probe("按钮", left = 10, top = 10, right = 100, bottom = 50))
        assertNull(PhoneControlBridge.probeAt(probes, 5, 5))
        assertNull(PhoneControlBridge.probeAt(probes, 100, 30)) // 右边界算在外面
        assertNull(PhoneControlBridge.probeAt(probes, 50, 50)) // 下边界算在外面
        assertNull(PhoneControlBridge.probeAt(emptyList(), 50, 30))
    }

    // ------------------------------------------------------------------
    // tapXYRejectionReason：tap_xy 的敏感拒绝
    // ------------------------------------------------------------------

    @Test
    fun rejectsWhenPointHitsPasswordField() {
        val probes = listOf(probe("", password = true, left = 10, top = 10, right = 200, bottom = 60))
        val reason = PhoneControlBridge.tapXYRejectionReason(probes, 100, 30)
        assertTrue(reason?.contains("不能代点") == true)
    }

    @Test
    fun rejectsWhenPointHitsSensitiveLabel() {
        val probes = listOf(probe("支付密码", left = 10, top = 10, right = 200, bottom = 60))
        val reason = PhoneControlBridge.tapXYRejectionReason(probes, 100, 30)
        assertTrue(reason?.contains("不能代点") == true)
    }

    @Test
    fun rejectsWhenNestedUnderSensitiveContainer() {
        // 盲点保守策略：命中**任一层**（哪怕最贴身那层无害）都拒绝
        val probes = listOf(
            probe("密码管理", left = 0, top = 0, right = 400, bottom = 400),
            probe("展开", left = 10, top = 10, right = 100, bottom = 50),
        )
        assertTrue(PhoneControlBridge.tapXYRejectionReason(probes, 50, 30) != null)
    }

    @Test
    fun allowsNormalControls() {
        val probes = listOf(
            probe("发送", left = 10, top = 10, right = 200, bottom = 60),
            probe("列表", left = 0, top = 0, right = 400, bottom = 400),
        )
        assertNull(PhoneControlBridge.tapXYRejectionReason(probes, 100, 30))
        assertNull(PhoneControlBridge.tapXYRejectionReason(emptyList(), 100, 30))
    }
}
