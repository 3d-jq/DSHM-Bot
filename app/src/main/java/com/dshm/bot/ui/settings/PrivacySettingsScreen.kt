package com.dshm.bot.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm

/** 隐私与分享：本机数据说明 + 引擎的对话日志上传开关。 */
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
        item {
            SettingsSection(title = stringResource(R.string.settings_privacy_local)) {
                Text(
                    text = stringResource(R.string.info_privacy),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                    modifier = Modifier.padding(Dshm.spacing.lg),
                )
            }
        }

        if (logNamespace != null) {
            item {
                EngineNamespaceSection(
                    viewModel = engineSettings,
                    namespace = logNamespace,
                    language = language,
                    title = stringResource(R.string.settings_privacy_log),
                    footer = stringResource(R.string.settings_privacy_log_hint),
                )
            }
        } else {
            item { SettingsHint(stringResource(R.string.settings_privacy_log_unavailable)) }
        }
    }
}
