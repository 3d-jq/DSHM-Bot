package com.dshm.bot.domain.repository

import com.dshm.bot.domain.model.AppLanguage
import com.dshm.bot.domain.model.ChatFontSize
import com.dshm.bot.domain.model.DshGoPreferences
import com.dshm.bot.domain.model.InterfaceMode
import com.dshm.bot.domain.model.PhoneConfirmMode
import com.dshm.bot.domain.model.ThemeMode
import com.dshm.bot.domain.model.VoiceMode
import kotlinx.coroutines.flow.Flow

/** App 本地设置（主题、字号、语言，以及 dsh-Go 设置 IA 的细分偏好）。 */
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

    fun observePhoneControlEnabled(): Flow<Boolean>

    fun setPhoneControlEnabled(enabled: Boolean)

    fun observePhoneConfirmMode(): Flow<PhoneConfirmMode>

    fun setPhoneConfirmMode(mode: PhoneConfirmMode)

    /** 唯一拥有手机操控能力的 Bot；null 表示还没选。 */
    fun observePhoneControlBotId(): Flow<String?>

    fun setPhoneControlBotId(botId: String?)

    /** 「访问手机文件」：把手机共享存储挂进本机引擎（需系统所有文件访问）。 */
    fun observePhoneStorageEnabled(): Flow<Boolean>

    fun setPhoneStorageEnabled(enabled: Boolean)

    /** dsh-Go 设置 IA 的细分偏好（通知四类、完成后收起过程、自动启动引擎）。 */
    fun observeDshGoPreferences(): Flow<DshGoPreferences>

    fun setDshGoPreference(key: String, value: Boolean)
}
