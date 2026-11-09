package com.dshm.bot.data.settings

import android.content.Context
import com.dshm.bot.domain.model.AppLanguage
import com.dshm.bot.domain.model.ChatFontSize
import com.dshm.bot.domain.model.DshGoPreferences
import com.dshm.bot.domain.model.InterfaceMode
import com.dshm.bot.domain.model.PhoneConfirmMode
import com.dshm.bot.domain.model.ThemeMode
import com.dshm.bot.domain.model.VoiceMode
import com.dshm.bot.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** 用 SharedPreferences 持久化 App 设置与 dsh-Go 细分偏好。 */
class SharedPrefsSettingsRepository(context: Context) : SettingsRepository {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val themeMode = MutableStateFlow(readEnum(KEY_THEME_MODE, ThemeMode.SYSTEM))
    private val chatFontSize = MutableStateFlow(readEnum(KEY_CHAT_FONT, ChatFontSize.STANDARD))
    private val language = MutableStateFlow(readEnum(KEY_LANGUAGE, AppLanguage.SYSTEM))
    private val interfaceMode = MutableStateFlow(readEnum(KEY_INTERFACE, InterfaceMode.NATIVE))
    private val notifications = MutableStateFlow(prefs.getBoolean(KEY_NOTIFICATIONS, true))
    private val voiceMode = MutableStateFlow(readEnum(KEY_VOICE, VoiceMode.AUTO))
    private val phoneControlEnabled = MutableStateFlow(prefs.getBoolean(KEY_PHONE_ENABLED, false))
    private val phoneConfirmMode = MutableStateFlow(readEnum(KEY_PHONE_CONFIRM, PhoneConfirmMode.ASK))
    private val phoneStorageEnabled = MutableStateFlow(prefs.getBoolean(KEY_PHONE_STORAGE, false))
    private val dshGoPreferences = MutableStateFlow(readDshGoPreferences())

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

    override fun observeInterfaceMode(): Flow<InterfaceMode> = interfaceMode

    override fun setInterfaceMode(mode: InterfaceMode) {
        prefs.edit().putString(KEY_INTERFACE, mode.name).apply()
        interfaceMode.value = mode
    }

    override fun observeNotificationsEnabled(): Flow<Boolean> = notifications

    override fun setNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS, enabled).apply()
        notifications.value = enabled
    }

    override fun observeVoiceMode(): Flow<VoiceMode> = voiceMode

    override fun setVoiceMode(mode: VoiceMode) {
        prefs.edit().putString(KEY_VOICE, mode.name).apply()
        voiceMode.value = mode
    }

    override fun observePhoneControlEnabled(): Flow<Boolean> = phoneControlEnabled

    override fun setPhoneControlEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PHONE_ENABLED, enabled).apply()
        phoneControlEnabled.value = enabled
    }

    override fun observePhoneConfirmMode(): Flow<PhoneConfirmMode> = phoneConfirmMode

    override fun setPhoneConfirmMode(mode: PhoneConfirmMode) {
        prefs.edit().putString(KEY_PHONE_CONFIRM, mode.name).apply()
        phoneConfirmMode.value = mode
    }

    override fun observePhoneStorageEnabled(): Flow<Boolean> = phoneStorageEnabled

    override fun setPhoneStorageEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PHONE_STORAGE, enabled).apply()
        phoneStorageEnabled.value = enabled
    }

    override fun observeDshGoPreferences(): Flow<DshGoPreferences> = dshGoPreferences

    override fun setDshGoPreference(key: String, value: Boolean) {
        // setFlag 会校验 key，未知 key 直接失败，不静默写入。
        val next = dshGoPreferences.value.setFlag(key, value)
        prefs.edit().putBoolean(key, value).apply()
        dshGoPreferences.value = next
    }

    /** 默认值统一取自 [DshGoPreferences]，不在这里重复写死。 */
    private fun readDshGoPreferences(): DshGoPreferences {
        var result = DshGoPreferences()
        DshGoPreferences.BOOLEAN_KEYS.forEach { key ->
            result = result.setFlag(key, prefs.getBoolean(key, result.flag(key)))
        }
        return result
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
        const val KEY_INTERFACE = "interface_mode"
        const val KEY_NOTIFICATIONS = "notifications_enabled"
        const val KEY_VOICE = "voice_mode"
        const val KEY_PHONE_ENABLED = "phone_control_enabled"
        const val KEY_PHONE_CONFIRM = "phone_confirm_mode"
        const val KEY_PHONE_STORAGE = "phone_storage_enabled"
    }
}
