package com.dshm.bot.data.settings

import android.content.Context
import com.dshm.bot.domain.model.ThemeMode
import com.dshm.bot.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** 用 SharedPreferences 持久化 App 设置（主题等）。 */
class SharedPrefsSettingsRepository(context: Context) : SettingsRepository {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val themeMode = MutableStateFlow(readThemeMode())

    override fun observeThemeMode(): Flow<ThemeMode> = themeMode

    override fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        themeMode.value = mode
    }

    private fun readThemeMode(): ThemeMode {
        val raw = prefs.getString(KEY_THEME_MODE, null) ?: return ThemeMode.SYSTEM
        return runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.SYSTEM)
    }

    private companion object {
        const val PREFS_NAME = "dshm_settings"
        const val KEY_THEME_MODE = "theme_mode"
    }
}
