package com.dshm.bot.ui.settings

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.SpeechProvider
import com.dshm.bot.domain.model.VoiceMode
import java.util.Locale

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
                    state.speechLoading && provider == null -> VoiceModelPlaceholder(
                        note = stringResource(R.string.voice_model_loading),
                    )

                    provider == null -> VoiceModelPlaceholder(
                        note = stringResource(R.string.voice_model_plugin_off),
                        status = stringResource(R.string.voice_model_unavailable),
                    )

                    else -> VoiceModelCard(
                        provider = provider,
                        preparing = state.speechPreparing,
                        onDownload = viewModel::prepareSpeech,
                        onCancel = viewModel::cancelSpeechPreparation,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceModelCard(
    provider: SpeechProvider,
    preparing: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val busy = provider.busy || preparing
    val status = voicePhaseText(provider)
    val statusColor = when {
        provider.usable -> Dshm.colors.success
        provider.phase == "failed" -> Dshm.colors.danger
        busy -> Dshm.colors.accent
        else -> Dshm.colors.textTertiary
    }
    val note = when {
        provider.phase == "downloading" -> voiceBytesText(provider)
        else -> listOfNotNull(provider.message, provider.downloadReason).joinToString(" · ").ifBlank { null }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = provider.name,
                style = Dshm.typography.settingsRowTitle,
                color = Dshm.colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = status,
                style = Dshm.typography.rowPreview,
                color = statusColor,
                maxLines = 1,
            )
        }
        if (busy) {
            Spacer(Modifier.size(Dshm.spacing.sm))
            LinearProgressIndicator(
                progress = { provider.fraction ?: 0f },
                modifier = Modifier.fillMaxWidth(),
                color = Dshm.colors.accent,
                trackColor = Dshm.colors.surfaceMuted,
            )
        }
        if (note != null) {
            Spacer(Modifier.size(Dshm.spacing.xs))
            Text(
                text = note,
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textTertiary,
            )
        }
        when {
            busy -> {
                Spacer(Modifier.size(Dshm.spacing.md))
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.voice_action_cancel))
                }
            }
            !provider.usable -> {
                Spacer(Modifier.size(Dshm.spacing.md))
                SettingsPrimaryButton(
                    text = if (provider.phase == "failed") {
                        stringResource(R.string.voice_action_retry)
                    } else {
                        stringResource(R.string.voice_action_download)
                    },
                    onClick = onDownload,
                )
            }
        }
    }
}

@Composable
private fun VoiceModelPlaceholder(note: String, status: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "SenseVoice",
                style = Dshm.typography.settingsRowTitle,
                color = Dshm.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (status != null) {
                Text(
                    text = status,
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textTertiary,
                )
            }
        }
        Spacer(Modifier.size(Dshm.spacing.xs))
        Text(
            text = note,
            style = Dshm.typography.rowPreview,
            color = Dshm.colors.textTertiary,
        )
    }
}

private fun voiceBytesText(provider: SpeechProvider): String? {
    val total = provider.totalBytes?.takeIf { it > 0L } ?: return null
    fun megabytes(bytes: Long): String =
        String.format(Locale.US, "%.0f MB", bytes / 1024.0 / 1024.0)
    val done = provider.completedBytes?.takeIf { it > 0L }
    return if (done != null) {
        "${megabytes(done)} / ${megabytes(total)}"
    } else {
        "约 ${megabytes(total)}"
    }
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
