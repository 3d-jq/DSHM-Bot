package com.dshm.bot.ui.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.runtime.RuntimeState
import com.dshm.bot.data.runtime.shortRuntimeVersion
import com.dshm.bot.ui.settings.SettingsPage
import com.dshm.bot.ui.settings.SettingsRow
import com.dshm.bot.ui.settings.SettingsRowDivider
import com.dshm.bot.ui.settings.SettingsSection

/** 诊断页上的两个宿主动作；页面只做展示，动作都交给 VM。 */
class DiagnosticsActions(
    val openLog: () -> Unit = {},
    val closeLog: () -> Unit = {},
    val retryLog: () -> Unit = {},
    val clear: () -> Unit = {},
)

/**
 * 诊断页（对齐 dsh-Go `app/lib/ui/settings/diagnostics_page.dart`）：
 * 运行时状态 → 连接信息 → 宿主日志 / 清空 → 复制一份打码过的诊断报告。
 *
 * 报告里永远不含 token 与 API Key 明文。
 *
 * 宿主日志走 `diagnostics-log`（长文，弹窗里按行懒加载 + 可复制），
 * 清空走 `diagnostics-clear`（破坏性操作，二次确认；失败给可见错误态）。
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
    diagnostics: DiagnosticsUiState,
    actions: DiagnosticsActions,
    onBack: () -> Unit,
) {
    val clipboard = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    var copied by remember { mutableStateOf(false) }
    // 清空成功后 reportRevision +1：报告就地重算，不会停在清空前的快照上。
    // 运行状态 / API Key 变了也要重算，复制的永远是当下这份。
    val reportText = remember(diagnostics.reportRevision, state, hasApiKey) { report() }
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
            var confirmClear by remember { mutableStateOf(false) }
            SettingsSection(title = stringResource(R.string.diagnostics_host)) {
                SettingsRow(
                    title = stringResource(R.string.diagnostics_view_log),
                    icon = R.drawable.ic_lucide_file_text,
                    subtitle = stringResource(R.string.diagnostics_view_log_hint),
                    onClick = actions.openLog,
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.diagnostics_clear),
                    icon = R.drawable.ic_lucide_trash_2,
                    subtitle = stringResource(R.string.diagnostics_clear_hint),
                    enabled = diagnostics.clearStatus != DiagnosticsClearStatus.WORKING,
                    onClick = { confirmClear = true },
                    trailing = {
                        if (diagnostics.clearStatus == DiagnosticsClearStatus.WORKING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(Dshm.spacing.md),
                                strokeWidth = 2.dp,
                                color = Dshm.colors.accent,
                            )
                        }
                    },
                )
                when (diagnostics.clearStatus) {
                    DiagnosticsClearStatus.CLEARED -> {
                        SettingsRowDivider()
                        SettingsRow(
                            title = stringResource(R.string.diagnostics_cleared),
                            icon = R.drawable.ic_lucide_check,
                            valueColor = Dshm.colors.success,
                            trailing = {},
                        )
                    }

                    DiagnosticsClearStatus.FAILED -> {
                        SettingsRowDivider()
                        SettingsRow(
                            title = stringResource(R.string.diagnostics_clear_failed),
                            icon = R.drawable.ic_lucide_info,
                            subtitle = stringResource(R.string.diagnostics_clear_failed_hint),
                            subtitleColor = Dshm.colors.danger,
                            trailing = {
                                TextButton(onClick = actions.clear) {
                                    Text(stringResource(R.string.action_retry), color = Dshm.colors.danger)
                                }
                            },
                        )
                    }

                    else -> Unit
                }
            }

            if (confirmClear) {
                AlertDialog(
                    onDismissRequest = { confirmClear = false },
                    containerColor = Dshm.colors.surface,
                    title = {
                        Text(
                            text = stringResource(R.string.diagnostics_clear_confirm_title),
                            style = Dshm.typography.sheetTitle,
                            color = Dshm.colors.textPrimary,
                        )
                    },
                    text = {
                        Text(
                            text = stringResource(R.string.diagnostics_clear_confirm_body),
                            style = Dshm.typography.rowPreview,
                            color = Dshm.colors.textSecondary,
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmClear = false
                            actions.clear()
                        }) {
                            Text(
                                text = stringResource(R.string.diagnostics_clear_confirm_action),
                                color = Dshm.colors.danger,
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmClear = false }) {
                            Text(stringResource(R.string.action_cancel), color = Dshm.colors.textSecondary)
                        }
                    },
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
                        clipboard.setPrimaryClip(ClipData.newPlainText("dshm-diagnostics", reportText))
                        copied = true
                    },
                )
            }
        }
    }

    if (diagnostics.logOpen) {
        DiagnosticsLogDialog(
            diagnostics = diagnostics,
            onCopy = { text ->
                clipboard.setPrimaryClip(ClipData.newPlainText("dshm-diagnostics-log", text))
            },
            actions = actions,
        )
    }
}

/**
 * 宿主诊断日志的长文视图。
 *
 * 按行进 [LazyColumn]（只测量可见行），长日志不卡；空文本给空态，取不到给
 * 错误态 + 重试——诊断页本身就是排障入口，失败不能静默。
 */
@Composable
private fun DiagnosticsLogDialog(
    diagnostics: DiagnosticsUiState,
    onCopy: (String) -> Unit,
    actions: DiagnosticsActions,
) {
    var copied by remember(diagnostics.logText) { mutableStateOf(false) }
    val lines = remember(diagnostics.logText) {
        if (diagnostics.logText.isEmpty()) emptyList() else diagnostics.logText.split('\n')
    }
    AlertDialog(
        onDismissRequest = actions.closeLog,
        containerColor = Dshm.colors.surface,
        title = {
            Text(
                text = stringResource(R.string.diagnostics_log_title),
                style = Dshm.typography.sheetTitle,
                color = Dshm.colors.textPrimary,
            )
        },
        text = {
            when (diagnostics.logStatus) {
                DiagnosticsLogStatus.LOADING -> Row(
                    modifier = Modifier.fillMaxWidth().padding(Dshm.spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Dshm.spacing.md),
                        strokeWidth = 2.dp,
                        color = Dshm.colors.accent,
                    )
                    Text(
                        text = stringResource(R.string.diagnostics_log_loading),
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.textSecondary,
                    )
                }

                DiagnosticsLogStatus.FAILED -> Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.diagnostics_log_failed),
                        style = Dshm.typography.rowTitle,
                        color = Dshm.colors.textPrimary,
                    )
                    Spacer(Modifier.heightIn(min = Dshm.spacing.sm))
                    Text(
                        text = stringResource(R.string.diagnostics_log_failed_hint),
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.danger,
                    )
                }

                DiagnosticsLogStatus.LOADED -> if (lines.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(Dshm.spacing.lg)) {
                        Text(
                            text = stringResource(R.string.diagnostics_log_empty),
                            style = Dshm.typography.rowPreview,
                            color = Dshm.colors.textSecondary,
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(count = lines.size) { index ->
                            Text(
                                text = lines[index],
                                style = Dshm.typography.logMono,
                                color = Dshm.colors.textPrimary,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                DiagnosticsLogStatus.IDLE -> Unit
            }
        },
        confirmButton = {
            when (diagnostics.logStatus) {
                DiagnosticsLogStatus.FAILED -> TextButton(onClick = actions.retryLog) {
                    Text(stringResource(R.string.action_retry), color = Dshm.colors.accent)
                }

                DiagnosticsLogStatus.LOADED -> if (lines.isNotEmpty()) {
                    TextButton(onClick = {
                        onCopy(diagnostics.logText)
                        copied = true
                    }) {
                        Text(
                            text = if (copied) {
                                stringResource(R.string.diagnostics_log_copied)
                            } else {
                                stringResource(R.string.engine_logs_copy)
                            },
                            color = Dshm.colors.accent,
                        )
                    }
                } else {
                    TextButton(onClick = actions.closeLog) {
                        Text(stringResource(R.string.action_done), color = Dshm.colors.accent)
                    }
                }

                else -> TextButton(onClick = actions.closeLog) {
                    Text(stringResource(R.string.action_done), color = Dshm.colors.accent)
                }
            }
        },
        dismissButton = if (diagnostics.logStatus == DiagnosticsLogStatus.LOADED && lines.isNotEmpty()) {
            {
                TextButton(onClick = actions.closeLog) {
                    Text(stringResource(R.string.action_done), color = Dshm.colors.textSecondary)
                }
            }
        } else {
            null
        },
    )
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
