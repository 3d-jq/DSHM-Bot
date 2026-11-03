package com.dshm.bot.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.domain.model.PhoneConfirmMode

/** 设置 → 手机操控：无障碍服务状态、总开关与确认方式。 */
@Composable
fun PhoneControlSettingsScreen(
    enabled: Boolean,
    confirmMode: PhoneConfirmMode,
    onEnabledChange: (Boolean) -> Unit,
    onConfirmModeChange: (PhoneConfirmMode) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val serviceEnabled = rememberAccessibilityServiceEnabled()
    val zh = SettingsCopy.isChinese(settingsLanguage())

    fun openAccessibilitySettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    SettingsPage(
        title = stringResource(R.string.settings_phone_control),
        onBack = onBack,
    ) {
        item {
            SettingsSection(
                title = stringResource(R.string.phone_control_service),
                footer = stringResource(R.string.phone_control_service_hint),
            ) {
                SettingsRow(
                    title = stringResource(R.string.phone_control_accessibility),
                    value = if (serviceEnabled) {
                        stringResource(R.string.phone_control_service_enabled)
                    } else {
                        stringResource(R.string.phone_control_service_disabled)
                    },
                    valueColor = if (!serviceEnabled) Dshm.colors.warning else null,
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.phone_control_open_settings),
                    subtitle = stringResource(R.string.phone_control_open_settings_hint),
                    onClick = { openAccessibilitySettings() },
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.phone_control_enable),
                    subtitle = if (serviceEnabled) {
                        stringResource(R.string.phone_control_enable_hint)
                    } else {
                        stringResource(R.string.phone_control_enable_blocked)
                    },
                    checked = enabled,
                    enabled = serviceEnabled,
                    onCheckedChange = onEnabledChange,
                )
            }
        }

        item {
            SettingsSection(
                title = stringResource(R.string.phone_control_confirm),
                footer = stringResource(R.string.phone_control_bridge_note),
            ) {
                PhoneConfirmMode.entries.forEachIndexed { index, mode ->
                    if (index > 0) SettingsRowDivider()
                    SettingsRow(
                        title = confirmModeLabel(mode, zh),
                        subtitle = confirmModeHint(mode, zh),
                        trailing = {
                            RadioButton(
                                selected = mode == confirmMode,
                                onClick = { onConfirmModeChange(mode) },
                            )
                        },
                        onClick = { onConfirmModeChange(mode) },
                    )
                }
            }
        }

        if (!serviceEnabled) {
            item {
                SettingsHint(
                    if (zh) {
                        "需要在系统设置 → 无障碍里启用 DSHM Bot。手机操控的引擎桥接仍未接入，当前版本只提供服务状态与确认偏好。"
                    } else {
                        "Enable DSHM Bot in Settings → Accessibility. The engine bridge for phone control is not wired yet; this version only stores the service state and confirmation preference."
                    },
                )
            }
        }
    }
}

@Composable
internal fun rememberAccessibilityServiceEnabled(): Boolean {
    val context = LocalContext.current
    var value by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) value = isAccessibilityEnabled(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return value
}

internal fun isAccessibilityEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.split(':').any { entry ->
        entry.contains(context.packageName) && entry.contains("DshmAccessibilityService")
    }
}

private fun confirmModeLabel(mode: PhoneConfirmMode, zh: Boolean): String = when (mode) {
    PhoneConfirmMode.ASK -> if (zh) "每次询问" else "Ask every time"
    PhoneConfirmMode.TASK -> if (zh) "每个任务询问" else "Ask per task"
    PhoneConfirmMode.ALWAYS -> if (zh) "不再询问" else "Never ask"
}

private fun confirmModeHint(mode: PhoneConfirmMode, zh: Boolean): String = when (mode) {
    PhoneConfirmMode.ASK -> if (zh) "每个动作前确认" else "Confirm before every action"
    PhoneConfirmMode.TASK -> if (zh) "每个任务开始时确认一次" else "Confirm once per task"
    PhoneConfirmMode.ALWAYS -> if (zh) "完全信任，动作直接执行" else "Execute actions without asking"
}
