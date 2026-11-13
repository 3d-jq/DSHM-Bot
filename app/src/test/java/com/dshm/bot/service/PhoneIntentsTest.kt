package com.dshm.bot.service

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统 intent 白名单的**纯校验层**（[PhoneIntents.validate]）——不构造 Android
 * [android.content.Intent]，所以能在 JVM 上跑。构造出来的 Intent 由真机验证。
 */
class PhoneIntentsTest {

    @Test
    fun dialRequiresNumber() {
        val (kind, error) = PhoneIntents.validate(JSONObject().put("type", "dial").put("number", "+8613800000000"))
        assertEquals(PhoneIntents.Kind.DIAL, kind)
        assertNull(error)
    }

    @Test
    fun shareRejectsEmptyText() {
        val (kind, error) = PhoneIntents.validate(JSONObject().put("type", "share"))
        assertNull(kind)
        assertTrue(error!!.contains("text"))
    }

    @Test
    fun unknownTypeIsRejected() {
        val (kind, error) = PhoneIntents.validate(JSONObject().put("type", "ACTION_CALL").put("number", "123"))
        assertNull(kind)
        assertTrue(error!!.contains("不支持"))
    }

    @Test
    fun settingsWhitelistExcludesAccessibility() {
        val (bad, _) = PhoneIntents.validate(JSONObject().put("type", "settings").put("page", "accessibility"))
        assertNull("敏感设置页不该在白名单里", bad)
        val (good, error) = PhoneIntents.validate(JSONObject().put("type", "settings").put("page", "wifi"))
        assertEquals(PhoneIntents.Kind.SETTINGS, good)
        assertNull(error)
    }

    @Test
    fun appNotificationsRequiresValidPackage() {
        val (bad, _) = PhoneIntents.validate(
            JSONObject().put("type", "settings").put("page", "app_notifications").put("app", "../../evil"),
        )
        assertNull(bad)
        val (good, error) = PhoneIntents.validate(
            JSONObject().put("type", "settings").put("page", "app_notifications").put("app", "com.example.app"),
        )
        assertEquals(PhoneIntents.Kind.SETTINGS, good)
        assertNull(error)
    }

    @Test
    fun alarmValidatesRange() {
        val (bad, _) = PhoneIntents.validate(JSONObject().put("type", "alarm").put("hour", 25).put("minute", 0))
        assertNull(bad)
        val (good, error) = PhoneIntents.validate(
            JSONObject().put("type", "alarm").put("hour", 8).put("minute", 30)
                .put("message", "起床").put("days", JSONArray().put(1).put(3)),
        )
        assertEquals(PhoneIntents.Kind.ALARM, good)
        assertNull(error)
    }

    @Test
    fun navigateAcceptsTextOrCoordinates() {
        val (text, e1) = PhoneIntents.validate(JSONObject().put("type", "navigate").put("destination", "成都火锅"))
        assertEquals(PhoneIntents.Kind.NAVIGATE, text)
        assertNull(e1)
        val (geo, e2) = PhoneIntents.validate(JSONObject().put("type", "navigate").put("lat", 30.6).put("lng", 104.0))
        assertEquals(PhoneIntents.Kind.NAVIGATE, geo)
        assertNull(e2)
        val (bad, _) = PhoneIntents.validate(JSONObject().put("type", "navigate"))
        assertNull(bad)
    }

    @Test
    fun allEightKindsAreDeclared() {
        assertEquals(8, PhoneIntents.Kind.entries.size)
        assertTrue(PhoneIntents.Kind.entries.any { it.wire == "dial" })
        assertTrue(PhoneIntents.Kind.entries.any { it.wire == "settings" })
        assertTrue(PhoneIntents.Kind.entries.any { it.wire == "share" })
    }
}
