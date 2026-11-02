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
import com.dshm.bot.domain.model.ThemeMode
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
)

/** 设置页状态层：读/写设置，界面只消费 [uiState]。 */
class SettingsViewModel(
    private val repository: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        repository.observeThemeMode(),
        repository.observeChatFontSize(),
        repository.observeLanguage(),
    ) { theme, font, language ->
        SettingsUiState(themeMode = theme, chatFontSize = font, language = language)
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

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(SharedPrefsSettingsRepository(context.applicationContext))
            }
        }
    }
}
