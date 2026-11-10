package com.dshm.bot.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.domain.model.ThemeMode
import com.dshm.bot.domain.model.VoiceMode

/** 设置子页 ID（路由用）。 */
enum class SettingsPageId {
    MODEL,
    PERMISSION,
    PERSONALIZATION,
    AGENT_PRESETS,
    PHONE_CONTROL,
    WEB_SEARCH,
    PLUGINS,
    VOICE,
    ENGINE_PARAMS,
    SCHEDULES,
    SECRETS,
    CONNECTORS,
    CAPABILITIES,
    USAGE,
    NOTIFICATIONS,
    BACKGROUND,
    PRIVACY,
    APP_VERSION,
    DSH_VERSION,
    LICENSES,
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
internal fun voiceLabel(mode: VoiceMode): String = stringResource(
    when (mode) {
        VoiceMode.AUTO -> R.string.settings_value_auto
        VoiceMode.SYSTEM -> R.string.voice_system
        VoiceMode.ENGINE -> R.string.voice_engine_unavailable_short
    },
)
