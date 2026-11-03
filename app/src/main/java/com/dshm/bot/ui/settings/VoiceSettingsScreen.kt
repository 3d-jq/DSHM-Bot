package com.dshm.bot.ui.settings

import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.domain.model.VoiceMode

/**
 * 语音输入：识别方式 + 语音模型。
 * 当前版本用系统识别；引擎内的 SenseVoice 模型尚未随运行时提供。
 */
@Composable
fun VoiceSettingsScreen(
    current: VoiceMode,
    onSelect: (VoiceMode) -> Unit,
    onBack: () -> Unit,
) {
    val modes = listOf(VoiceMode.AUTO, VoiceMode.SYSTEM, VoiceMode.ENGINE)
    SettingsPage(
        title = stringResource(R.string.settings_voice),
        onBack = onBack,
    ) {
        item {
            SettingsSection(title = stringResource(R.string.voice_section_mode)) {
                modes.forEachIndexed { index, mode ->
                    if (index > 0) SettingsRowDivider()
                    val available = mode != VoiceMode.ENGINE
                    SettingsRow(
                        title = when (mode) {
                            VoiceMode.AUTO -> stringResource(R.string.settings_value_auto)
                            VoiceMode.SYSTEM -> stringResource(R.string.voice_system)
                            VoiceMode.ENGINE -> stringResource(R.string.voice_engine)
                        },
                        subtitle = when (mode) {
                            VoiceMode.AUTO -> stringResource(R.string.voice_hint_auto)
                            VoiceMode.SYSTEM -> stringResource(R.string.voice_hint_system)
                            VoiceMode.ENGINE -> stringResource(R.string.voice_engine_unavailable)
                        },
                        enabled = available,
                        trailing = {
                            RadioButton(
                                selected = mode == current,
                                onClick = if (available) {
                                    { onSelect(mode) }
                                } else {
                                    null
                                },
                            )
                        },
                        onClick = if (available) {
                            { onSelect(mode) }
                        } else {
                            null
                        },
                    )
                }
            }
        }

        item {
            SettingsSection(
                title = stringResource(R.string.voice_section_model),
                footer = stringResource(R.string.voice_model_note),
            ) {
                SettingsRow(
                    title = "SenseVoice",
                    subtitle = stringResource(R.string.voice_model_hint),
                    value = stringResource(R.string.voice_model_not_installed),
                    enabled = false,
                )
            }
        }
    }
}
