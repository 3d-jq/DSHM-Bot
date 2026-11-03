package com.dshm.bot.domain.repository

import com.dshm.bot.domain.model.AppLanguage
import com.dshm.bot.domain.model.InterfaceMode
import com.dshm.bot.domain.model.VoiceMode
import com.dshm.bot.domain.model.PerformanceMode
import com.dshm.bot.domain.model.ChatFontSize
import com.dshm.bot.domain.model.PetSize
import com.dshm.bot.domain.model.PetStyle
import com.dshm.bot.domain.model.PhoneConfirmMode
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

    fun observeInterfaceMode(): Flow<InterfaceMode>

    fun setInterfaceMode(mode: InterfaceMode)

    fun observeNotificationsEnabled(): Flow<Boolean>

    fun setNotificationsEnabled(enabled: Boolean)

    fun observeVoiceMode(): Flow<VoiceMode>

    fun setVoiceMode(mode: VoiceMode)

    fun observePerformanceMode(): Flow<PerformanceMode>

    fun setPerformanceMode(mode: PerformanceMode)

    fun observePetEnabled(): Flow<Boolean>

    fun setPetEnabled(enabled: Boolean)

    fun observePetStyle(): Flow<PetStyle>

    fun setPetStyle(style: PetStyle)

    fun observePetSize(): Flow<PetSize>

    fun setPetSize(size: PetSize)

    fun observePhoneControlEnabled(): Flow<Boolean>

    fun setPhoneControlEnabled(enabled: Boolean)

    fun observePhoneConfirmMode(): Flow<PhoneConfirmMode>

    fun setPhoneConfirmMode(mode: PhoneConfirmMode)
}

