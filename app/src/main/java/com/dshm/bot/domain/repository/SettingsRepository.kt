package com.dshm.bot.domain.repository

import com.dshm.bot.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/** App 本地设置（当前只有主题；后续的主机、通知等也放这里）。 */
interface SettingsRepository {
    fun observeThemeMode(): Flow<ThemeMode>

    fun setThemeMode(mode: ThemeMode)
}
