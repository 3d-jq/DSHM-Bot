package com.dshm.bot.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm

/** 通知：总开关 + 系统通知权限状态 + 跳系统设置。 */
@Composable
fun NotificationSettingsScreen(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val systemAllowed = rememberNotificationsAllowed()
    var error by remember { mutableStateOf<String?>(null) }
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
                footer = stringResource(R.string.settings_notifications_hint),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_notifications_engine_ready),
                    subtitle = stringResource(R.string.settings_notifications_engine_ready_hint),
                    checked = enabled,
                    onCheckedChange = { setEnabled(it) },
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
