package com.dshm.bot.ui.settings

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.SpeechProvider
import com.dshm.bot.domain.model.VoiceMode

// dsh-Go 设置 › 通用 › 语音输入：识别方式，以及引擎语音模型。
// 对齐 dsh-Go：引擎模型由 DSH 的语音输入插件提供，speech/catalog 读目录，
// speech/prepare、speech/configure、speech/cancelPreparation 负责准备与取消，
// 录音只在本机引擎里识别。离线语音模型等未接入能力统一标成不可用，不放假开关。
@Composable
fun VoiceSettingsScreen(
    current: VoiceMode,
    onSelect: (VoiceMode) -> Unit,
    onBack: () -> Unit,
    viewModel: EngineSettingsViewModel,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshSpeech() }

    val catalog = state.speech
    val provider = catalog?.selected ?: catalog?.providers?.firstOrNull()
    val engineAvailable = provider != null
    val modes = listOf(VoiceMode.AUTO, VoiceMode.SYSTEM, VoiceMode.ENGINE)

    SettingsPage(
        title = stringResource(R.string.settings_voice),
        onBack = onBack,
        actions = { SettingsRefreshButton(onClick = viewModel::refreshSpeech) },
    ) {
        if (state.speechError != null) {
            item { SettingsError(state.speechError.orEmpty(), onRetry = viewModel::refreshSpeech) }
        }

        item {
            SettingsSection(title = stringResource(R.string.voice_section_mode)) {
                modes.forEachIndexed { index, mode ->
                    if (index > 0) SettingsRowDivider()
                    val available = mode != VoiceMode.ENGINE || engineAvailable
                    SettingsRow(
                        title = when (mode) {
                            VoiceMode.AUTO -> stringResource(R.string.settings_value_auto)
                            VoiceMode.SYSTEM -> stringResource(R.string.voice_system)
                            VoiceMode.ENGINE -> stringResource(R.string.voice_engine)
                        },
                        subtitle = when (mode) {
                            VoiceMode.AUTO -> stringResource(R.string.voice_hint_auto)
                            VoiceMode.SYSTEM -> stringResource(R.string.voice_hint_system)
                            VoiceMode.ENGINE -> stringResource(R.string.voice_hint_engine)
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
                footer = stringResource(R.string.voice_model_footer),
            ) {
                when {
                    state.speechLoading && provider == null -> SettingsRow(
                        title = "SenseVoice",
                        subtitle = stringResource(R.string.voice_model_loading),
                        enabled = false,
                    )

                    provider == null -> SettingsRow(
                        title = "SenseVoice",
                        subtitle = stringResource(R.string.voice_model_plugin_off),
                        value = stringResource(R.string.voice_model_unavailable),
                        enabled = false,
                    )

                    else -> VoiceProviderRow(
                        provider = provider,
                        preparing = state.speechPreparing,
                        onDownload = viewModel::prepareSpeech,
                        onCancel = viewModel::cancelSpeechPreparation,
                    )
                }
            }
        }

        if (provider != null && provider.busy) {
            item {
                LinearProgressIndicator(
                    progress = { provider.fraction ?: 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
                    color = Dshm.colors.accent,
                    trackColor = Dshm.colors.surfaceMuted,
                )
            }
        }

        // 离线语音模型等未接入能力：不可用，不放假开关。
        item {
            SettingsSection(
                title = stringResource(R.string.settings_voice_other_capabilities),
                footer = stringResource(R.string.settings_engine_required),
            ) {
                listOf(
                    stringResource(R.string.settings_voice_offline_model),
                    stringResource(R.string.settings_voice_custom_words),
                ).forEachIndexed { index, label ->
                    if (index > 0) SettingsRowDivider()
                    SettingsRow(
                        title = label,
                        enabled = false,
                        subtitle = stringResource(R.string.settings_unavailable_hint),
                        value = stringResource(R.string.settings_unavailable),
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceProviderRow(
    provider: SpeechProvider,
    preparing: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val status = voicePhaseText(provider)
    val action = when {
        provider.busy || preparing -> stringResource(R.string.voice_action_cancel)
        provider.usable -> stringResource(R.string.voice_state_ready)
        else -> if (provider.phase == "failed") {
            stringResource(R.string.voice_action_retry)
        } else {
            stringResource(R.string.voice_action_download)
        }
    }
    SettingsRow(
        title = provider.name,
        subtitle = listOfNotNull(status, provider.message, provider.downloadReason).joinToString(" · "),
        value = action,
        enabled = true,
        onClick = {
            when {
                provider.busy || preparing -> onCancel()
                !provider.usable -> onDownload()
            }
        },
    )
}

@Composable
private fun voicePhaseText(provider: SpeechProvider): String {
    val percent = provider.fraction?.let { "${(it * 100).toInt()}%" }
    return when (provider.phase) {
        "ready", "standby", "waking" -> stringResource(R.string.voice_state_ready)
        "downloading" -> stringResource(R.string.voice_state_downloading) + (percent?.let { " $it" } ?: "")
        "checking" -> stringResource(R.string.voice_state_checking)
        "loading" -> stringResource(R.string.voice_state_loading)
        "cancelling" -> stringResource(R.string.voice_state_cancelling)
        "cancelled" -> stringResource(R.string.voice_state_cancelled)
        "failed" -> stringResource(R.string.voice_state_failed)
        "unprepared" -> stringResource(R.string.voice_state_unprepared)
        else -> provider.phase
    }
}
