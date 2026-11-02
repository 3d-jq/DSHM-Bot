package com.dshm.bot.data.settings

import android.content.Context
import com.dshm.bot.domain.model.AppLanguage
import com.dshm.bot.domain.model.ChatFontSize
import com.dshm.bot.domain.model.ThemeMode
import com.dshm.bot.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** 用 SharedPreferences 持久化 App 设置（主题 / 对话字号 / 语言）。 */
class SharedPrefsSettingsRepository(context: Context) : SettingsRepository {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val themeMode = MutableStateFlow(readEnum(KEY_THEME_MODE, ThemeMode.SYSTEM))
    private val chatFontSize = MutableStateFlow(readEnum(KEY_CHAT_FONT, ChatFontSize.STANDARD))
    private val language = MutableStateFlow(readEnum(KEY_LANGUAGE, AppLanguage.SYSTEM))

    override fun observeThemeMode(): Flow<ThemeMode> = themeMode

    override fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        themeMode.value = mode
    }

    override fun observeChatFontSize(): Flow<ChatFontSize> = chatFontSize

    override fun setChatFontSize(size: ChatFontSize) {
        prefs.edit().putString(KEY_CHAT_FONT, size.name).apply()
        chatFontSize.value = size
    }

    override fun observeLanguage(): Flow<AppLanguage> = language

    override fun setLanguage(language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.name).apply()
        this.language.value = language
    }

    private inline fun <reified T : Enum<T>> readEnum(key: String, default: T): T {
        val raw = prefs.getString(key, null) ?: return default
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(default)
    }

    private companion object {
        const val PREFS_NAME = "dshm_settings"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_CHAT_FONT = "chat_font_size"
        const val KEY_LANGUAGE = "language"
    }
}
