package com.dshm.bot.ui.settings

import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.domain.model.InterfaceMode

// dsh-Go 设置 › 通用 › 界面：起始界面、网页版显示与发送、网页模式专属项。
// 起始界面的原生 / 网页切换，以及引擎 webNamespaces() 提供的 schema 表单都是真实能力，
// 继续调用原来的 ViewModel 方法；其余 dsh-Go 条目当前没有接入，统一标成不可用，不放假开关。
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

        // 网页版显示 / 网页版发送里引擎未提供 schema 的 dsh-Go 条目：不可用。
        item {
            SettingsSection(
                title = stringResource(R.string.settings_web_section_display),
                footer = stringResource(R.string.settings_engine_required),
            ) {
                listOf(
                    stringResource(R.string.settings_web_option_code_view),
                    stringResource(R.string.settings_web_option_steps),
                    stringResource(R.string.settings_web_option_performance),
                    stringResource(R.string.settings_web_option_send_busy),
                    stringResource(R.string.settings_web_option_links),
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

        // 网页模式里的手机布局、只打开一次网页版：当前未接入，不可用。
        item {
            SettingsSection(
                title = stringResource(R.string.settings_web_section_mode),
                footer = stringResource(R.string.settings_unavailable_hint),
            ) {
                listOf(
                    stringResource(R.string.settings_web_option_phone_layout),
                    stringResource(R.string.settings_web_option_open_once),
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
