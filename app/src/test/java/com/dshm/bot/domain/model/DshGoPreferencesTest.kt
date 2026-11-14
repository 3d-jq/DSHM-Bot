package com.dshm.bot.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** dsh-Go 设置细分偏好的默认值、读写隔离与非法 key 处理。 */
class DshGoPreferencesTest {

    @Test
    fun defaultsFollowDshGoSettingsGuide() {
        val value = DshGoPreferences()
        assertTrue(value.notifyTurnDone)
        assertTrue(value.notifyError)
        assertTrue(value.notifyApproval)
        assertTrue(value.notifyQuestion)
        assertTrue(value.foldProcess)
        // 打开 App 时自动启动引擎（dsh-Go 的 autoStartLocal）默认开着。
        assertTrue(value.autoStartLocal)
        // timeline shadow（Phase 1a debug 双跑）默认关：主管线零影响。
        assertFalse(value.timelineShadow)
        assertFalse(DshGoPreferences().flag(DshGoPreferences.KEY_TIMELINE_SHADOW))
    }

    @Test
    fun everyBooleanKeyRoundTripsWithoutTouchingOtherKeys() {
        val base = DshGoPreferences()
        DshGoPreferences.BOOLEAN_KEYS.forEach { key ->
            val expected = !base.flag(key)
            val updated = base.setFlag(key, expected)
            assertEquals(expected, updated.flag(key))
            DshGoPreferences.BOOLEAN_KEYS.filter { it != key }.forEach { other ->
                assertEquals("改 $key 不应影响 $other", base.flag(other), updated.flag(other))
            }
        }
    }

    @Test
    fun booleanKeysAreUnique() {
        val keys = DshGoPreferences.BOOLEAN_KEYS
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.isNotEmpty())
    }

    @Test
    fun unknownKeyIsRejectedInsteadOfSilentlyIgnored() {
        listOf("flag" to { DshGoPreferences().flag("not_a_key") },
            "setFlag" to { DshGoPreferences().setFlag("not_a_key", true) }).forEach { (name, call) ->
            try {
                call()
                fail("$name 遇到未知 key 应抛异常")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("not_a_key"))
            }
        }
    }
}
