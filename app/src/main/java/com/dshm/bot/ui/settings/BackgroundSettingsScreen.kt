package com.dshm.bot.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm

// 后台运行：按 dsh-Go 常见问题补充保活指南，保留电池优化白名单状态与跳转。
@Composable
fun BackgroundSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val exempt = rememberBatteryExempt()

    fun openBatterySettings() {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(direct) }.isSuccess ||
            runCatching { context.startActivity(list) }.isSuccess
        if (!opened) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    SettingsPage(
        title = stringResource(R.string.settings_background_running),
        onBack = onBack,
    ) {
        item {
            SettingsSection(
                title = stringResource(R.string.settings_background_status),
                footer = stringResource(R.string.settings_background_hint),
            ) {
                SettingsRow(
                    title = stringResource(R.string.settings_background_battery),
                    value = when (exempt) {
                        true -> stringResource(R.string.settings_background_allowed)
                        false -> stringResource(R.string.settings_background_needs_setup)
                        null -> null
                    },
                    valueColor = if (exempt == false) Dshm.colors.warning else null,
                )
                if (exempt == false) {
                    SettingsRowDivider()
                    SettingsRow(
                        title = stringResource(R.string.settings_background_open),
                        subtitle = stringResource(R.string.settings_background_open_hint),
                        onClick = { openBatterySettings() },
                    )
                }
            }
        }

        item {
            SettingsSection(
                title = stringResource(R.string.settings_notifications),
                footer = stringResource(R.string.settings_notifications_hint),
            ) {
                SettingsRow(
                    title = stringResource(R.string.settings_notifications_open_system),
                    subtitle = stringResource(R.string.settings_notifications_open_system_hint),
                    onClick = { openNotificationSettings() },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_background_vendor)) {
                Text(
                    text = stringResource(R.string.settings_background_vendor_hint),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                    modifier = Modifier.padding(Dshm.spacing.lg),
                )
            }
        }
    }
}
