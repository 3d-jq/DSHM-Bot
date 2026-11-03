package com.dshm.bot.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.domain.model.InterfaceMode
import com.dshm.bot.domain.model.PerformanceMode
import com.dshm.bot.domain.model.ThemeMode
import com.dshm.bot.domain.model.VoiceMode

/** 设置子页 ID（路由用）。 */
enum class SettingsPageId {
    MODEL,
    PERMISSION,
    PERSONALIZATION,
    PHONE_CONTROL,
    WEB_SEARCH,
    PLUGINS,
    PETS,
    VOICE,
    ENGINE_PARAMS,
    INTERFACE,
    NOTIFICATIONS,
    PERFORMANCE,
    BACKGROUND,
    PRIVACY,
    LICENSES,
    ABOUT,
}

@Composable
internal fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    },
)

@Composable
internal fun interfaceLabel(mode: InterfaceMode): String = stringResource(
    when (mode) {
        InterfaceMode.NATIVE -> R.string.settings_value_native
        InterfaceMode.WEB -> R.string.interface_web
    },
)

@Composable
internal fun performanceLabel(mode: PerformanceMode): String = stringResource(
    when (mode) {
        PerformanceMode.AUTO -> R.string.settings_value_auto
        PerformanceMode.FULL -> R.string.performance_full
        PerformanceMode.LIGHT -> R.string.performance_light
    },
)

@Composable
internal fun voiceLabel(mode: VoiceMode): String = stringResource(
    when (mode) {
        VoiceMode.AUTO -> R.string.settings_value_auto
        VoiceMode.SYSTEM -> R.string.voice_system
        VoiceMode.ENGINE -> R.string.voice_engine_unavailable_short
    },
)
