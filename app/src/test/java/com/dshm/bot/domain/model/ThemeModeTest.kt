package com.dshm.bot.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {

    @Test
    fun systemFollowsSystemSetting() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemInDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemInDark = false))
    }

    @Test
    fun lightAndDarkOverrideSystem() {
        assertFalse(ThemeMode.LIGHT.isDark(systemInDark = true))
        assertTrue(ThemeMode.DARK.isDark(systemInDark = false))
    }
}
