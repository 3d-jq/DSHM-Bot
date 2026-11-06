package com.dshm.bot.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.domain.model.DshGoPreferences

// dsh-Go 设置 › 通用 › 通知：允许通知总开关，下面四类提醒是真实可保存的本地偏好。
// 总开关 enabled 即 notificationsEnabled，关闭时四类子项都不可操作；
// 系统通知权限状态与跳系统设置沿用原来的 ViewModel 与逻辑，不删真实功能。
@Composable
fun NotificationSettingsScreen(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val systemAllowed = rememberNotificationsAllowed()
    var error by remember { mutableStateOf<String?>(null) }
    val settingsVm: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(LocalContext.current.applicationContext),
    )
    val state by settingsVm.uiState.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            error = null
            onChange(true)
        } else {
            error = context.getString(R.string.settings_notifications_permission_denied)
        }
    }

    fun setEnabled(next: Boolean) {
        error = null
        if (!next) {
            onChange(false)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onChange(true)
        }
    }

    fun openSystemSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    SettingsPage(
        title = stringResource(R.string.settings_notifications),
        onBack = onBack,
    ) {
        item {
            SettingsSection(
                title = stringResource(R.string.settings_notifications_events),
                footer = stringResource(R.string.settings_notify_hint),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_notifications_engine_ready),
                    subtitle = stringResource(R.string.settings_notifications_engine_ready_hint),
                    checked = enabled,
                    onCheckedChange = { setEnabled(it) },
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_notify_turn_done),
                    checked = state.dshGo.flag(DshGoPreferences.KEY_NOTIFY_TURN_DONE),
                    onCheckedChange = {
                        settingsVm.setDshGoPreference(DshGoPreferences.KEY_NOTIFY_TURN_DONE, it)
                    },
                    enabled = enabled,
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_notify_error),
                    checked = state.dshGo.flag(DshGoPreferences.KEY_NOTIFY_ERROR),
                    onCheckedChange = {
                        settingsVm.setDshGoPreference(DshGoPreferences.KEY_NOTIFY_ERROR, it)
                    },
                    enabled = enabled,
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_notify_approval),
                    checked = state.dshGo.flag(DshGoPreferences.KEY_NOTIFY_APPROVAL),
                    onCheckedChange = {
                        settingsVm.setDshGoPreference(DshGoPreferences.KEY_NOTIFY_APPROVAL, it)
                    },
                    enabled = enabled,
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_notify_question),
                    checked = state.dshGo.flag(DshGoPreferences.KEY_NOTIFY_QUESTION),
                    onCheckedChange = {
                        settingsVm.setDshGoPreference(DshGoPreferences.KEY_NOTIFY_QUESTION, it)
                    },
                    enabled = enabled,
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_notifications_system)) {
                SettingsRow(
                    title = stringResource(R.string.settings_notifications_system_status),
                    value = when (systemAllowed) {
                        true -> stringResource(R.string.settings_notifications_allowed)
                        false -> stringResource(R.string.settings_notifications_blocked)
                        null -> null
                    },
                    valueColor = if (systemAllowed == false) Dshm.colors.warning else null,
                )
                if (systemAllowed == false) {
                    SettingsRowDivider()
                    SettingsRow(
                        title = stringResource(R.string.settings_notifications_open_system),
                        subtitle = stringResource(R.string.settings_notifications_open_system_hint),
                        onClick = { openSystemSettings() },
                    )
                }
            }
        }

        if (error != null) {
            item { SettingsError(error.orEmpty()) }
        }
    }
}
