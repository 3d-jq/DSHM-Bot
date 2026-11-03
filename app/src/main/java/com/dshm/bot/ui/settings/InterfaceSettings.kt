package com.dshm.bot.ui.settings

import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.domain.model.InterfaceMode

/** 界面：原生 / 网页，以及网页端在引擎里的 ui-* 设置。 */
@Composable
fun InterfaceSettingsScreen(
    current: InterfaceMode,
    onSelect: (InterfaceMode) -> Unit,
    engineSettings: EngineSettingsViewModel,
    onBack: () -> Unit,
) {
    val state by engineSettings.ui.collectAsStateWithLifecycle()
    val language = settingsLanguage()
    val webNamespaces = engineSettings.webNamespaces()

    SettingsPage(
        title = stringResource(R.string.settings_interface),
        onBack = onBack,
        actions = {
            SettingsRefreshButton(onClick = engineSettings::refresh)
        },
    ) {
        item {
            SettingsSection(
                title = stringResource(R.string.settings_interface_home),
                footer = stringResource(R.string.settings_interface_hint),
            ) {
                InterfaceMode.entries.forEachIndexed { index, mode ->
                    if (index > 0) SettingsRowDivider()
                    SettingsRow(
                        title = interfaceLabel(mode),
                        subtitle = if (mode == InterfaceMode.WEB) {
                            stringResource(R.string.settings_interface_web_hint)
                        } else {
                            null
                        },
                        trailing = {
                            RadioButton(selected = mode == current, onClick = { onSelect(mode) })
                        },
                        onClick = { onSelect(mode) },
                    )
                }
            }
        }

        if (webNamespaces.isEmpty()) {
            item {
                SettingsHint(
                    if (state.loading) {
                        stringResource(R.string.action_loading)
                    } else {
                        stringResource(R.string.settings_interface_web_unavailable)
                    },
                )
            }
        } else {
            webNamespaces.forEach { namespace ->
                item(key = namespace.ns) {
                    EngineNamespaceSection(
                        viewModel = engineSettings,
                        namespace = namespace,
                        language = language,
                    )
                }
            }
        }
    }
}
