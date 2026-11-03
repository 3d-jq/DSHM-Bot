package com.dshm.bot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.domain.model.InterfaceMode
import com.dshm.bot.domain.model.PerformanceMode
import com.dshm.bot.domain.model.VoiceMode

/** 设置子页 ID（路由用）。 */
enum class SettingsPageId {
    INTERFACE,
    NOTIFICATIONS,
    VOICE,
    PERFORMANCE,
    BACKGROUND,
    PRIVACY,
    PETS,
    MODEL,
    PERMISSION,
    PERSONALIZATION,
    PHONE_CONTROL,
    WEB_SEARCH,
    PLUGINS,
    ENGINE_PARAMS,
    LICENSES,
}

/** 单选项设置页（界面/通知/语音/性能等）。 */
@Composable
fun OptionSettingsPage(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onPick: (Int) -> Unit,
    onBack: () -> Unit,
    hint: String? = null,
    subtitles: List<String>? = null,
) {
    SettingsPageScaffold(title = title, onBack = onBack) {
        val shape = RoundedCornerShape(Dshm.radius.lg)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = Dshm.spacing.cardElevation,
                    shape = shape,
                    ambientColor = Dshm.colors.shadowCard,
                    spotColor = Dshm.colors.shadowCard,
                )
                .clip(shape)
                .background(Dshm.colors.surface)
                .border(Dshm.spacing.hairline, Dshm.colors.divider, shape),
        ) {
            options.forEachIndexed { index, label ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(index) }
                        .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = label,
                            style = Dshm.typography.rowTitle,
                            color = Dshm.colors.textPrimary,
                        )
                        val subtitle = subtitles?.getOrNull(index)
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = Dshm.typography.rowPreview,
                                color = Dshm.colors.textSecondary,
                            )
                        }
                    }
                    RadioButton(selected = index == selectedIndex, onClick = { onPick(index) })
                }
                if (index != options.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = Dshm.spacing.lg),
                        thickness = Dshm.spacing.hairline,
                        color = Dshm.colors.divider,
                    )
                }
            }
        }
        if (hint != null) {
            Spacer(Modifier.padding(Dshm.spacing.sm))
            Text(hint, style = Dshm.typography.rowPreview, color = Dshm.colors.textTertiary)
        }
    }
}

/** 说明类设置页（后台运行/隐私/AI/能力/主机/许可等）。 */
@Composable
fun InfoSettingsPage(title: String, lines: List<String>, onBack: () -> Unit) {
    SettingsPageScaffold(title = title, onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dshm.radius.lg))
                .background(Dshm.colors.surface)
                .padding(Dshm.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
        ) {
            lines.forEach { line ->
                Text(line, style = Dshm.typography.rowPreview, color = Dshm.colors.textSecondary)
            }
        }
    }
}

@Composable
internal fun settingsPageTitle(page: SettingsPageId?): String = stringResource(
    when (page) {
        SettingsPageId.MODEL -> R.string.settings_model
        SettingsPageId.PERMISSION -> R.string.settings_permission
        SettingsPageId.PERSONALIZATION -> R.string.settings_personalization
        SettingsPageId.PHONE_CONTROL -> R.string.settings_phone_control
        SettingsPageId.WEB_SEARCH -> R.string.settings_web_search
        SettingsPageId.PLUGINS -> R.string.settings_plugins
        SettingsPageId.BACKGROUND -> R.string.settings_background_running
        SettingsPageId.PRIVACY -> R.string.settings_privacy
        SettingsPageId.PETS -> R.string.settings_pets
        SettingsPageId.ENGINE_PARAMS -> R.string.settings_engine_params
        SettingsPageId.LICENSES -> R.string.settings_licenses
        SettingsPageId.INTERFACE -> R.string.settings_interface
        SettingsPageId.NOTIFICATIONS -> R.string.settings_notifications
        SettingsPageId.VOICE -> R.string.settings_voice
        SettingsPageId.PERFORMANCE -> R.string.settings_performance
        null -> R.string.settings_title
    },
)

@Composable
internal fun settingsPageInfo(page: SettingsPageId?, dshVersion: String?): List<String> = when (page) {
    SettingsPageId.BACKGROUND -> listOf(
        stringResource(R.string.engine_keep_alive_hint),
        stringResource(R.string.info_engine_required),
    )
    SettingsPageId.PRIVACY -> listOf(stringResource(R.string.info_privacy))
    SettingsPageId.PETS -> listOf(stringResource(R.string.info_pets))
    SettingsPageId.LICENSES -> listOf(stringResource(R.string.info_licenses))
    SettingsPageId.ENGINE_PARAMS -> listOf(
        stringResource(R.string.info_engine_required),
        if (dshVersion.isNullOrBlank()) stringResource(R.string.settings_value_not_linked) else dshVersion,
    )
    SettingsPageId.MODEL, SettingsPageId.PERMISSION, SettingsPageId.PERSONALIZATION,
    SettingsPageId.PHONE_CONTROL, SettingsPageId.WEB_SEARCH, SettingsPageId.PLUGINS,
    -> listOf(stringResource(R.string.info_engine_required))
    else -> emptyList()
}

@Composable
internal fun interfaceLabel(mode: InterfaceMode): String = stringResource(
    when (mode) {
        InterfaceMode.NATIVE -> R.string.settings_value_native
        InterfaceMode.WEB -> R.string.interface_web
    },
)

@Composable
internal fun voiceLabel(mode: VoiceMode): String = stringResource(
    when (mode) {
        VoiceMode.AUTO -> R.string.settings_value_auto
        VoiceMode.SYSTEM -> R.string.voice_system
        VoiceMode.ENGINE -> R.string.voice_engine
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



