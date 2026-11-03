package com.dshm.bot.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshm.bot.data.settings.SharedPrefsSettingsRepository
import com.dshm.bot.domain.model.AppLanguage
import com.dshm.bot.domain.model.ChatFontSize
import com.dshm.bot.domain.model.InterfaceMode
import com.dshm.bot.domain.model.PerformanceMode
import com.dshm.bot.domain.model.PetSize
import com.dshm.bot.domain.model.PetStyle
import com.dshm.bot.domain.model.PhoneConfirmMode
import com.dshm.bot.domain.model.ThemeMode
import com.dshm.bot.domain.model.VoiceMode
import com.dshm.bot.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** 设置页状态。 */
data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val chatFontSize: ChatFontSize = ChatFontSize.STANDARD,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val interfaceMode: InterfaceMode = InterfaceMode.NATIVE,
    val notificationsEnabled: Boolean = true,
    val voiceMode: VoiceMode = VoiceMode.AUTO,
    val performanceMode: PerformanceMode = PerformanceMode.AUTO,
    val petEnabled: Boolean = false,
    val petStyle: PetStyle = PetStyle.DEEPY_WHALE,
    val petSize: PetSize = PetSize.MEDIUM,
    val phoneControlEnabled: Boolean = false,
    val phoneConfirmMode: PhoneConfirmMode = PhoneConfirmMode.ASK,
)

/** 设置页状态层：读/写设置，界面只消费 [uiState]。 */
class SettingsViewModel(
    private val repository: SettingsRepository,
) : ViewModel() {

    private val core = combine(
        repository.observeThemeMode(),
        repository.observeChatFontSize(),
        repository.observeLanguage(),
        repository.observeInterfaceMode(),
        repository.observeNotificationsEnabled(),
    ) { theme, font, language, interfaceMode, notifications ->
        listOf(theme, font, language, interfaceMode, notifications)
    }

    private val extended = combine(
        repository.observeVoiceMode(),
        repository.observePerformanceMode(),
        repository.observePetEnabled(),
        repository.observePetStyle(),
        repository.observePetSize(),
    ) { voice, performance, petEnabled, petStyle, petSize ->
        listOf(voice, performance, petEnabled, petStyle, petSize)
    }

    private val phone = combine(
        repository.observePhoneControlEnabled(),
        repository.observePhoneConfirmMode(),
    ) { enabled, mode -> listOf(enabled, mode) }

    val uiState: StateFlow<SettingsUiState> = combine(core, extended, phone) { base, extra, phoneValues ->
        @Suppress("UNCHECKED_CAST")
        SettingsUiState(
            themeMode = base[0] as ThemeMode,
            chatFontSize = base[1] as ChatFontSize,
            language = base[2] as AppLanguage,
            interfaceMode = base[3] as InterfaceMode,
            notificationsEnabled = base[4] as Boolean,
            voiceMode = extra[0] as VoiceMode,
            performanceMode = extra[1] as PerformanceMode,
            petEnabled = extra[2] as Boolean,
            petStyle = extra[3] as PetStyle,
            petSize = extra[4] as PetSize,
            phoneControlEnabled = phoneValues[0] as Boolean,
            phoneConfirmMode = phoneValues[1] as PhoneConfirmMode,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsUiState(),
    )

    fun setThemeMode(mode: ThemeMode) {
        repository.setThemeMode(mode)
    }

    fun setChatFontSize(size: ChatFontSize) {
        repository.setChatFontSize(size)
    }

    fun setLanguage(language: AppLanguage) {
        repository.setLanguage(language)
    }

    fun setInterfaceMode(mode: InterfaceMode) = repository.setInterfaceMode(mode)

    fun setNotificationsEnabled(enabled: Boolean) = repository.setNotificationsEnabled(enabled)

    fun setVoiceMode(mode: VoiceMode) = repository.setVoiceMode(mode)

    fun setPerformanceMode(mode: PerformanceMode) = repository.setPerformanceMode(mode)

    fun setPetEnabled(enabled: Boolean) = repository.setPetEnabled(enabled)

    fun setPetStyle(style: PetStyle) = repository.setPetStyle(style)

    fun setPetSize(size: PetSize) = repository.setPetSize(size)

    fun setPhoneControlEnabled(enabled: Boolean) = repository.setPhoneControlEnabled(enabled)

    fun setPhoneConfirmMode(mode: PhoneConfirmMode) = repository.setPhoneConfirmMode(mode)

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(SharedPrefsSettingsRepository(context.applicationContext))
            }
        }
    }
}
