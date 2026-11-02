package com.dshm.bot.domain.repository

import com.dshm.bot.domain.model.AppLanguage
import com.dshm.bot.domain.model.ChatFontSize
import com.dshm.bot.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/** App 本地设置（主题、字号、语言；后续的主机、通知等也放这里）。 */
interface SettingsRepository {
    fun observeThemeMode(): Flow<ThemeMode>

    fun setThemeMode(mode: ThemeMode)

    fun observeChatFontSize(): Flow<ChatFontSize>

    fun setChatFontSize(size: ChatFontSize)

    fun observeLanguage(): Flow<AppLanguage>

    fun setLanguage(language: AppLanguage)
}
