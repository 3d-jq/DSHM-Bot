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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.domain.model.PhoneConfirmMode

// 设置 → 手机操控：无障碍服务状态、总开关、确认方式与未接入能力说明。
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
        // 说明：如实写明引擎桥接未接入，不假称进阶能力可用
        item {
            SettingsSection(
                title = stringResource(R.string.settings_phone_before_start),
                footer = stringResource(R.string.phone_control_bridge_note),
            ) {
                SettingsRow(
                    title = stringResource(R.string.phone_control_service_description),
                )
            }
        }

        // 开启与准备：权限状态、去系统设置、真实开关，以及未接入的读屏 / 自动恢复
        item {
            SettingsSection(
                title = stringResource(R.string.settings_phone_setup),
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
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_phone_read_screen),
                    enabled = false,
                    subtitle = stringResource(R.string.settings_unavailable_hint),
                    value = stringResource(R.string.settings_unavailable),
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_phone_restore_a11y),
                    enabled = false,
                    subtitle = stringResource(R.string.settings_unavailable_hint),
                    value = stringResource(R.string.settings_unavailable),
                )
            }
        }

        // 确认方式：三档真实保存的偏好
        item {
            SettingsSection(
                title = stringResource(R.string.phone_control_confirm),
                footer = stringResource(R.string.phone_control_bridge_note),
            ) {
                PhoneConfirmMode.entries.forEachIndexed { index, mode ->
                    if (index > 0) SettingsRowDivider()
                    SettingsRow(
                        title = confirmModeLabel(mode),
                        subtitle = confirmModeHint(mode),
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

        // 悬浮条 / 应用名单 / 解除限制 / 不打扰模式 / 操作记录：尚未接入，明确标注
        item {
            val advanced = listOf(
                stringResource(R.string.settings_phone_floating_bar),
                stringResource(R.string.settings_phone_app_rules),
                stringResource(R.string.settings_phone_unlock),
                stringResource(R.string.settings_phone_dnd),
                stringResource(R.string.settings_phone_operation_log),
            )
            SettingsSection(
                title = stringResource(R.string.settings_phone_advanced),
                footer = stringResource(R.string.settings_engine_required),
            ) {
                advanced.forEachIndexed { index, label ->
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

        if (!serviceEnabled) {
            item {
                SettingsHint(stringResource(R.string.settings_phone_service_hint))
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

@Composable
private fun confirmModeLabel(mode: PhoneConfirmMode): String = when (mode) {
    PhoneConfirmMode.ASK -> stringResource(R.string.phone_confirm_ask)
    PhoneConfirmMode.TASK -> stringResource(R.string.phone_confirm_task)
    PhoneConfirmMode.ALWAYS -> stringResource(R.string.phone_confirm_always)
}

@Composable
private fun confirmModeHint(mode: PhoneConfirmMode): String = when (mode) {
    PhoneConfirmMode.ASK -> stringResource(R.string.phone_confirm_ask_hint)
    PhoneConfirmMode.TASK -> stringResource(R.string.phone_confirm_task_hint)
    PhoneConfirmMode.ALWAYS -> stringResource(R.string.phone_confirm_always_hint)
}
