package com.dshm.bot.data.settings

import com.dshm.bot.domain.model.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置仓库的实例语义与读写行为。
 *
 * P1 修复的回归防线：设置页（写端）与通知 / 对话（读端）必须共用同一个实例，
 * 否则读端的 StateFlow 永远停在构造时的快照（「关掉通知还在响」）。
 * `getInstance(context)` 需要 Android Context，JVM 单测用 [SharedPrefsSettingsRepository.forPrefs]
 * 直接注入内存 prefs 验证同一套行为。
 */
class SharedPrefsSettingsRepositoryTest {

    @Test
    fun `同一实例写完立刻读到`() = runTest {
        val repo = SharedPrefsSettingsRepository.forPrefs(FakeSharedPreferences())

        repo.setNotificationsEnabled(false)
        assertFalse(repo.observeNotificationsEnabled().first())

        repo.setNotificationsEnabled(true)
        assertTrue(repo.observeNotificationsEnabled().first())
    }

    @Test
    fun `偏好写入后读端即时可见且落到底层 prefs`() = runTest {
        val prefs = FakeSharedPreferences()
        val repo = SharedPrefsSettingsRepository.forPrefs(prefs)

        repo.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, repo.observeThemeMode().first())
        assertEquals(ThemeMode.DARK.name, prefs.values["theme_mode"])

        repo.setThemeMode(ThemeMode.SYSTEM)
        assertEquals(ThemeMode.SYSTEM, repo.observeThemeMode().first())
    }

    @Test
    fun `两个实例不互通——这就是必须单例的原因`() = runTest {
        val writer = SharedPrefsSettingsRepository.forPrefs(FakeSharedPreferences())
        val reader = SharedPrefsSettingsRepository.forPrefs(FakeSharedPreferences())

        writer.setNotificationsEnabled(false)

        assertFalse(writer.observeNotificationsEnabled().first())
        // 旧实现的行为：读端（通知 / 对话）各 new 一个实例时，快照永远不跟着写端刷新。
        assertTrue(reader.observeNotificationsEnabled().first())
        assertNotEquals(
            writer.observeNotificationsEnabled().first(),
            reader.observeNotificationsEnabled().first(),
        )
    }

    @Test
    fun `存了不认识的枚举值回落到默认`() = runTest {
        val prefs = FakeSharedPreferences(mapOf("theme_mode" to "neon"))
        val repo = SharedPrefsSettingsRepository.forPrefs(prefs)

        assertEquals(ThemeMode.SYSTEM, repo.observeThemeMode().first())
    }

    @Test
    fun `dshGo 偏好写读互通且底层是裸 key`() = runTest {
        val prefs = FakeSharedPreferences()
        val repo = SharedPrefsSettingsRepository.forPrefs(prefs)

        val before = repo.observeDshGoPreferences().first().notifyTurnDone
        repo.setDshGoPreference("notify_turn_done", !before)

        val after = repo.observeDshGoPreferences().first()
        assertEquals(!before, after.notifyTurnDone)
        assertEquals(!before, prefs.values["notify_turn_done"])
    }
}
