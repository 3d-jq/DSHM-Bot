package com.dshm.bot.ui.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.runtime.RuntimeState
import com.dshm.bot.data.runtime.shortRuntimeVersion
import com.dshm.bot.ui.settings.SettingsPage
import com.dshm.bot.ui.settings.SettingsRow
import com.dshm.bot.ui.settings.SettingsRowDivider
import com.dshm.bot.ui.settings.SettingsSection

/**
 * 诊断页（对齐 dsh-Go `app/lib/ui/settings/diagnostics_page.dart`）：
 * 运行时状态 → 连接信息 → 复制一份打码过的诊断报告。
 *
 * 报告里永远不含 token 与 API Key 明文。
 */
@Composable
fun DiagnosticsScreen(
    state: RuntimeState,
    version: String,
    nodeVersion: String,
    bundledVersion: String,
    installedVersion: String?,
    abi: String,
    hasApiKey: Boolean,
    report: () -> String,
    onBack: () -> Unit,
) {
    val clipboard = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    var copied by remember { mutableStateOf(false) }
    val connected = state is RuntimeState.Running

    SettingsPage(
        title = stringResource(R.string.engine_diagnostics),
        onBack = onBack,
    ) {
        item {
            SettingsSection(title = stringResource(R.string.diagnostics_runtime)) {
                SettingsRow(
                    title = stringResource(R.string.diagnostics_status),
                    icon = R.drawable.ic_lucide_gauge,
                    value = enginePhaseLabel(state),
                    trailing = {},
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.diagnostics_architecture),
                    icon = R.drawable.ic_lucide_cpu,
                    value = abi,
                    trailing = {},
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.engine_bundled_version),
                    icon = R.drawable.ic_lucide_package,
                    value = shortRuntimeVersion(bundledVersion),
                    trailing = {},
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.diagnostics_installed_version),
                    icon = R.drawable.ic_lucide_box,
                    value = shortRuntimeVersion(installedVersion)
                        ?: stringResource(R.string.settings_value_not_set),
                    trailing = {},
                )
                SettingsRowDivider()
                SettingsRow(title = "DSH", icon = R.drawable.ic_lucide_server, value = version, trailing = {})
                SettingsRowDivider()
                SettingsRow(
                    title = "Node.js",
                    icon = R.drawable.ic_lucide_code,
                    value = nodeVersion,
                    trailing = {},
                )
                val failure = (state as? RuntimeState.Failed)?.message
                if (failure != null) {
                    SettingsRowDivider()
                    SettingsRow(
                        title = stringResource(R.string.diagnostics_error),
                        icon = R.drawable.ic_lucide_info,
                        value = failure,
                        valueColor = Dshm.colors.danger,
                        trailing = {},
                    )
                }
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.diagnostics_connection)) {
                SettingsRow(
                    title = stringResource(R.string.diagnostics_address),
                    icon = R.drawable.ic_lucide_globe,
                    value = (state as? RuntimeState.Running)?.endpoint?.baseUrl
                        ?: stringResource(R.string.settings_value_not_set),
                    trailing = {},
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.diagnostics_client),
                    icon = R.drawable.ic_lucide_monitor,
                    value = if (connected) {
                        stringResource(R.string.diagnostics_connected)
                    } else {
                        stringResource(R.string.diagnostics_not_connected)
                    },
                    trailing = {},
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.diagnostics_api_key),
                    icon = R.drawable.ic_lucide_shield,
                    value = if (hasApiKey) {
                        stringResource(R.string.diagnostics_configured)
                    } else {
                        stringResource(R.string.settings_value_not_set)
                    },
                    trailing = {},
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.diagnostics_export)) {
                SettingsRow(
                    title = if (copied) {
                        stringResource(R.string.diagnostics_copied)
                    } else {
                        stringResource(R.string.diagnostics_copy_report)
                    },
                    icon = R.drawable.ic_lucide_copy,
                    subtitle = stringResource(R.string.diagnostics_copy_report_hint),
                    onClick = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("dshm-diagnostics", report()))
                        copied = true
                    },
                )
            }
        }
    }
}

/** 状态的中文/英文名（诊断页用）。 */
@Composable
private fun enginePhaseLabel(state: RuntimeState): String = when (state) {
    is RuntimeState.Running -> stringResource(R.string.engine_value_running)
    is RuntimeState.Installed -> stringResource(R.string.engine_value_installed)
    is RuntimeState.Starting -> stringResource(R.string.engine_value_starting)
    is RuntimeState.Installing -> stringResource(R.string.engine_value_installing)
    is RuntimeState.Failed -> stringResource(R.string.engine_value_failed)
    RuntimeState.Stopped -> stringResource(R.string.engine_value_stopped)
    RuntimeState.NotInstalled -> stringResource(R.string.engine_value_not_installed)
}
