package com.dshm.bot.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.runtime.PhoneStorageAccess
import com.dshm.bot.data.remote.PermissionOption
import com.dshm.bot.ui.settings.SettingsPrimaryButton
import com.dshm.bot.ui.settings.SettingsSecondaryButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier

// 权限：新对话默认 + 手机文件访问 + 手机操控确认，对齐 dsh-Go PermissionPage。
@Composable
fun PermissionScreen(
    viewModel: EngineSettingsViewModel,
    settings: SettingsViewModel,
    onOpenPhoneControl: () -> Unit,
    onRestartEngine: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val appSettings by settings.uiState.collectAsStateWithLifecycle()
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val language = settingsLanguage()
    val current = viewModel.permissionDefault()
    val stored = state.description?.namespace("permission")?.valueOf(listOf("defaultPreset")) as? String
    val savedError = viewModel.fieldError("permission", listOf("defaultPreset")) ?: state.error
    var confirming by remember { mutableStateOf<PermissionOption?>(null) }
    val catalog = state.permission
    val options = catalog?.defaultOptions.orEmpty()
    val writable = viewModel.permissionRevisionWritable()
    var storageGranted by remember { mutableStateOf(PhoneStorageAccess.granted(context)) }
    var confirmRisk by remember { mutableStateOf(false) }
    var pendingEnable by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    // 从系统「所有文件访问」页返回时重新判权限（dsh-Go 的 `storageStatus()` 同款）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = PhoneStorageAccess.granted(context)
                storageGranted = granted
                // 从系统「所有文件访问」页返回：授权到了就自动完成挂载。
                if (granted && pendingEnable) {
                    pendingEnable = false
                    notice = null
                    settings.setPhoneStorageEnabled(true)
                    onRestartEngine()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsPage(
        title = stringResource(R.string.settings_permission),
        onBack = onBack,
        actions = {
            SettingsRefreshButton(onClick = viewModel::refresh)
        },
    ) {
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
        // 手机能力：文件访问是真实开关（系统授权 + /sdcard 挂载 + 重启引擎），
        // 操控确认跳到「手机操控」页的三档偏好。
        item {
            SettingsSection(
                title = stringResource(R.string.settings_permission_phone_group),
                footer = stringResource(R.string.settings_permission_phone_files_hint),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_permission_phone_files),
                    subtitle = if (appSettings.phoneStorageEnabled && storageGranted) {
                        stringResource(R.string.settings_permission_phone_files_on)
                    } else {
                        stringResource(R.string.settings_permission_phone_files_off)
                    },
                    checked = appSettings.phoneStorageEnabled && storageGranted,
                    onCheckedChange = { next ->
                        if (next) {
                            confirmRisk = true
                        } else {
                            pendingEnable = false
                            settings.setPhoneStorageEnabled(false)
                            notice = null
                            onRestartEngine()
                        }
                    },
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_permission_phone_confirm),
                    subtitle = stringResource(R.string.settings_permission_phone_confirm_hint),
                    value = confirmModeLabel(appSettings.phoneConfirmMode),
                    onClick = onOpenPhoneControl,
                )
                notice?.let { message ->
                    SettingsRowDivider()
                    SettingsRow(title = message, enabled = false)
                }
            }
        }
    }

    // 打开前说明风险；确认后先拿系统权限，再写偏好并重启引擎。
    if (confirmRisk) {
        AlertDialog(
            onDismissRequest = { confirmRisk = false },
            containerColor = Dshm.colors.surface,
            title = {
                Text(
                    text = stringResource(R.string.settings_permission_phone_files),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.settings_permission_phone_files_risk),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                ) {
                    SettingsSecondaryButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = { confirmRisk = false },
                        modifier = Modifier.weight(1f),
                        fillWidth = false,
                        onBackground = Dshm.colors.surface,
                    )
                    SettingsPrimaryButton(
                        text = stringResource(R.string.action_continue),
                        onClick = {
                            confirmRisk = false
                            if (!PhoneStorageAccess.granted(context)) {
                                pendingEnable = true
                                PhoneStorageAccess.openSystemSettings(context)
                                notice = context.getString(R.string.settings_permission_phone_files_grant)
                            } else {
                                pendingEnable = false
                                settings.setPhoneStorageEnabled(true)
                                notice = null
                                onRestartEngine()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        fillWidth = false,
                        danger = true,
                    )
                }
            },
        )
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                ) {
                    SettingsSecondaryButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = { confirming = null },
                        modifier = Modifier.weight(1f),
                        fillWidth = false,
                        onBackground = Dshm.colors.surface,
                    )
                    SettingsPrimaryButton(
                        text = stringResource(R.string.action_continue),
                        onClick = {
                            confirming = null
                            viewModel.setPermissionDefault(pending.value)
                        },
                        modifier = Modifier.weight(1f),
                        fillWidth = false,
                        danger = true,
                    )
                }
            },
        )
    }
}
