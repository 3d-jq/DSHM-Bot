package com.dshm.bot.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R

// 隐私与分享：当前只接引擎真实支持的「向 DeepSeek 上传对话日志」。
@Composable
fun PrivacySettingsScreen(
    engineSettings: EngineSettingsViewModel,
    onBack: () -> Unit,
) {
    val state by engineSettings.ui.collectAsStateWithLifecycle()
    val language = settingsLanguage()
    val logNamespace = state.description?.namespace("session-log-deepseek")

    SettingsPage(
        title = stringResource(R.string.settings_privacy),
        onBack = onBack,
    ) {
        if (logNamespace != null) {
            item {
                EngineNamespaceSection(
                    viewModel = engineSettings,
                    namespace = logNamespace,
                    language = language,
                    title = stringResource(R.string.settings_privacy_log),
                    footer = stringResource(R.string.settings_privacy_log_hint),
                    fieldPaths = listOf(listOf("enabled")),
                )
            }
        } else {
            item { SettingsHint(stringResource(R.string.settings_privacy_log_unavailable)) }
        }
    }
}
