package com.dshm.bot.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.domain.model.PhoneConfirmMode
import com.dshm.bot.service.PhoneActionLog
import com.dshm.bot.service.PhoneControlBridge
import kotlinx.coroutines.launch
import org.json.JSONObject

data class PhoneBotOption(val id: String, val name: String)

/**
 * 设置 → 手机操控：对齐 dsh-Go 的能力页骨架。
 * 手机工具只挂给用户选中的一个 Bot；群聊和其他 Bot 会被插件拒绝。
 */
@Composable
fun PhoneControlSettingsScreen(
    enabled: Boolean,
    confirmMode: PhoneConfirmMode,
    bots: List<PhoneBotOption>,
    ownerBotId: String?,
    onEnabledChange: (Boolean) -> Unit,
    onConfirmModeChange: (PhoneConfirmMode) -> Unit,
    onOwnerChange: (String?) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val serviceEnabled = rememberAccessibilityServiceEnabled()
    val notificationsAllowed = rememberNotificationsAllowed()
    var testing by remember { mutableStateOf(false) }
    var testText by remember { mutableStateOf<String?>(null) }
    var rulesOpen by remember { mutableStateOf(false) }
    var ownerPickerOpen by remember { mutableStateOf(false) }
    var recentActions by remember { mutableStateOf(PhoneActionLog.recent(context, 5)) }
    val ownerName = bots.firstOrNull { it.id == ownerBotId }?.name
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                recentActions = PhoneActionLog.recent(context, 5)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (rulesOpen) {
        PhoneAppRulesScreen(onBack = { rulesOpen = false })
        return
    }

    fun openAccessibilitySettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun openNotificationSettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun testScreen() {
        if (testing || !serviceEnabled) return
        testing = true
        scope.launch {
            val result = PhoneControlBridge.handle(context, JSONObject().put("kind", "screen"))
            testing = false
            testText = if (result.optBoolean("ok", false)) {
                result.optJSONObject("result")?.optString("text").orEmpty()
            } else {
                result.optString("error").ifBlank {
                    context.getString(R.string.settings_phone_read_screen_failed)
                }
            }
        }
    }

    SettingsPage(
        title = stringResource(R.string.settings_phone_control),
        onBack = onBack,
    ) {
        item {
            SettingsSection(
                title = stringResource(R.string.settings_phone_owner),
                footer = stringResource(R.string.settings_phone_owner_hint),
            ) {
                SettingsRow(
                    title = stringResource(R.string.settings_phone_owner_row),
                    subtitle = if (bots.isEmpty()) {
                        stringResource(R.string.settings_phone_no_bots)
                    } else {
                        stringResource(R.string.settings_phone_owner_row_hint)
                    },
                    value = ownerName ?: stringResource(R.string.settings_phone_owner_unselected),
                    enabled = bots.isNotEmpty(),
                    onClick = { ownerPickerOpen = true },
                )
            }
        }

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
                SettingsRow(
                    title = stringResource(R.string.settings_phone_notifications),
                    value = when (notificationsAllowed) {
                        false -> stringResource(R.string.settings_phone_notifications_blocked)
                        true -> stringResource(R.string.settings_phone_notifications_ready)
                        null -> stringResource(R.string.settings_value_default)
                    },
                    valueColor = if (notificationsAllowed == false) Dshm.colors.warning else null,
                    onClick = { openNotificationSettings() },
                )
                SettingsRowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.phone_control_enable),
                    subtitle = if (serviceEnabled && ownerBotId == null) {
                        stringResource(R.string.settings_phone_owner_first)
                    } else if (serviceEnabled) {
                        stringResource(R.string.phone_control_enable_main_bot_hint)
                    } else {
                        stringResource(R.string.phone_control_enable_blocked)
                    },
                    checked = enabled && ownerBotId != null,
                    enabled = serviceEnabled && ownerBotId != null,
                    onCheckedChange = onEnabledChange,
                )
            }
        }

        item {
            SettingsSection(
                title = stringResource(R.string.settings_phone_try),
                footer = if (enabled) {
                    stringResource(R.string.settings_phone_try_hint)
                } else {
                    stringResource(R.string.settings_phone_try_off)
                },
            ) {
                SettingsRow(
                    title = stringResource(R.string.settings_phone_read_screen),
                    subtitle = when {
                        testing -> stringResource(R.string.settings_phone_reading_screen)
                        !serviceEnabled -> stringResource(R.string.phone_control_enable_blocked)
                        !enabled -> stringResource(R.string.settings_phone_try_off)
                        else -> stringResource(R.string.settings_phone_read_screen_hint)
                    },
                    enabled = serviceEnabled && enabled && !testing,
                    onClick = { testScreen() },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_phone_supports)) {
                SettingsRow(
                    title = stringResource(R.string.settings_phone_supports_row),
                    subtitle = stringResource(R.string.settings_phone_supports_hint),
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_phone_rules)) {
                SettingsRow(
                    title = stringResource(R.string.settings_phone_app_rules),
                    subtitle = stringResource(R.string.settings_phone_app_rules_hint),
                    onClick = { rulesOpen = true },
                )
            }
        }

        item {
            SettingsSection(
                title = stringResource(R.string.phone_control_confirm),
                footer = stringResource(R.string.phone_control_confirm_hint),
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

        item {
            SettingsSection(title = stringResource(R.string.settings_phone_recent_actions)) {
                if (recentActions.isEmpty()) {
                    SettingsRow(
                        title = stringResource(R.string.settings_phone_no_actions),
                        subtitle = stringResource(R.string.settings_phone_no_actions_hint),
                    )
                } else {
                    recentActions.forEachIndexed { index, action ->
                        if (index > 0) SettingsRowDivider()
                        SettingsRow(title = action)
                    }
                }
            }
        }

        if (!serviceEnabled) {
            item {
                SettingsHint(stringResource(R.string.settings_phone_service_hint))
            }
        }
    }

    testText?.let { text ->
        AlertDialog(
            onDismissRequest = { testText = null },
            title = { Text(stringResource(R.string.settings_phone_current_screen)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    Text(text)
                }
            },
            confirmButton = {
                TextButton(onClick = { testText = null }) {
                    Text(stringResource(R.string.action_done))
                }
            },
        )
    }

    if (ownerPickerOpen) {
        AlertDialog(
            onDismissRequest = { ownerPickerOpen = false },
            title = { Text(stringResource(R.string.settings_phone_choose_owner)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    bots.forEach { bot ->
                        TextButton(
                            onClick = {
                                onOwnerChange(bot.id)
                                ownerPickerOpen = false
                            },
                        ) {
                            Text(
                                if (bot.id == ownerBotId) "✓ ${bot.name}" else bot.name,
                            )
                        }
                    }
                    if (ownerBotId != null) {
                        TextButton(
                            onClick = {
                                onOwnerChange(null)
                                ownerPickerOpen = false
                            },
                        ) {
                            Text(stringResource(R.string.settings_phone_owner_none))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { ownerPickerOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
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
internal fun confirmModeLabel(mode: PhoneConfirmMode): String = when (mode) {
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
