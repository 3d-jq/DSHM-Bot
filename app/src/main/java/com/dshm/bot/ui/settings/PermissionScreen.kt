package com.dshm.bot.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.PermissionOption

// 权限：当前对话、新对话默认、访问手机文件、手机操控确认，对齐 dsh-Go PermissionPage。
@Composable
fun PermissionScreen(viewModel: EngineSettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val language = settingsLanguage()
    val current = viewModel.permissionDefault()
    val stored = state.description?.namespace("permission")?.valueOf(listOf("defaultPreset")) as? String
    val savedError = viewModel.fieldError("permission", listOf("defaultPreset")) ?: state.error
    var confirming by remember { mutableStateOf<PermissionOption?>(null) }
    val catalog = state.permission
    val options = catalog?.defaultOptions.orEmpty()
    val writable = viewModel.permissionRevisionWritable()

    SettingsPage(
        title = stringResource(R.string.settings_permission),
        onBack = onBack,
        actions = {
            SettingsRefreshButton(onClick = viewModel::refresh)
        },
    ) {
        // 当前对话：dsh-Go 随打开的对话出现，本项目未接入对话级权限，明确标注不可用。
        item {
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.settings_permission_current),
                    subtitle = stringResource(R.string.settings_unavailable_hint),
                    value = stringResource(R.string.settings_unavailable),
                    enabled = false,
                )
            }
        }
        when {
            state.loading && state.description == null -> item { SettingsLoading() }
            savedError != null && state.description == null -> item {
                SettingsError(savedError, onRetry = viewModel::refresh)
            }
            catalog == null -> item { SettingsEmpty(stringResource(R.string.info_engine_required)) }
            else -> {
                item {
                    SettingsSection(
                        title = stringResource(R.string.settings_permission_new_default),
                        footer = stringResource(R.string.settings_permission_hint),
                    ) {
                        options.forEachIndexed { index, option ->
                            if (index > 0) SettingsRowDivider()
                            val risky = option.value == "danger-full-access" || option.value == "auto"
                            SettingsRow(
                                title = SettingsCopy.permissionLabel(option.value, language),
                                subtitle = SettingsCopy.permissionHint(option.value, language)
                                    ?: option.description,
                                subtitleColor = if (risky) Dshm.colors.warning else null,
                                enabled = writable,
                                trailing = {
                                    RadioButton(
                                        selected = option.value == current,
                                        onClick = if (!writable) null else {
                                            { confirming = option }
                                        },
                                    )
                                },
                                onClick = if (!writable) null else {
                                    { confirming = option }
                                },
                            )
                        }
                    }
                }

                if (stored != null && stored != catalog.defaultPreset) {
                    item {
                        SettingsSection {
                            SettingsRow(
                                title = stringResource(R.string.settings_reset_default),
                                subtitle = stringResource(R.string.settings_permission_reset_hint, catalog.defaultPreset),
                                enabled = writable,
                                onClick = { viewModel.setPermissionDefault(null) },
                            )
                        }
                    }
                }

                if (savedError != null) {
                    item { SettingsError(savedError) }
                }
                if (!writable) {
                    item { SettingsHint(stringResource(R.string.settings_engine_read_only)) }
                }
            }
        }
        // 访问手机文件 / 手机操控确认：依赖本机引擎与手机操控能力，本项目未接入，标注不可用。
        item {
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.settings_permission_phone_files),
                    subtitle = stringResource(R.string.settings_unavailable_hint),
                    value = stringResource(R.string.settings_unavailable),
                    enabled = false,
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_permission_phone_confirm),
                    subtitle = stringResource(R.string.settings_unavailable_hint),
                    value = stringResource(R.string.settings_unavailable),
                    enabled = false,
                )
            }
        }
    }

    val pending = confirming
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { confirming = null },
            containerColor = Dshm.colors.surface,
            title = {
                Text(
                    text = stringResource(R.string.settings_permission_confirm_title),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
            },
            text = {
                Text(
                    text = when (pending.value) {
                        "danger-full-access" -> stringResource(R.string.settings_permission_confirm_full)
                        else -> stringResource(R.string.settings_permission_confirm_auto)
                    },
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    viewModel.setPermissionDefault(pending.value)
                }) {
                    Text(stringResource(R.string.action_continue), color = Dshm.colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) {
                    Text(stringResource(R.string.action_cancel), color = Dshm.colors.textSecondary)
                }
            },
        )
    }
}
