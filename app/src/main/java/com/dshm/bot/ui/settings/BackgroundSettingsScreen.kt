package com.dshm.bot.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm

/**
 * 后台运行（对齐 dsh-Go `background_guide_page.dart`）：
 * 本机厂商开关 → 系统电池优化 → 保持在线步骤 → 其他品牌折叠指南。
 */
@Composable
fun BackgroundSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val exempt = rememberBatteryExempt()
    val vendor = remember { currentVendor() }
    val vendors = allVendors()
    var openVendor by rememberSaveable { mutableStateOf<String?>(null) }
    var showBatteryExemptDialog by remember { mutableStateOf(false) }

    SettingsPage(
        title = stringResource(R.string.settings_background_running),
        onBack = onBack,
    ) {
        vendors.firstOrNull { it.id == vendor }?.let { mine ->
            item {
                SettingsSection(title = mine.name) {
                    SettingsRow(
                        title = mine.key,
                        subtitle = mine.path,
                        onClick = { BackgroundLauncher.openVendorBackground(context) },
                    )
                }
            }
        }

        item {
            SettingsSection(
                title = stringResource(R.string.settings_background_system),
                footer = stringResource(R.string.settings_background_hint),
            ) {
                SettingsRow(
                    title = stringResource(R.string.settings_background_battery),
                    value = when (exempt) {
                        true -> stringResource(R.string.settings_background_exempt)
                        false -> stringResource(R.string.settings_background_not_exempt)
                        null -> null
                    },
                    valueColor = if (exempt == false) Dshm.colors.warning else null,
                    onClick = {
                        if (exempt == true) {
                            showBatteryExemptDialog = true
                        } else {
                            BackgroundLauncher.openSystemBattery(context)
                        }
                    },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_background_stay_online)) {
                BackgroundStep(index = 1, text = stringResource(R.string.settings_background_step_notification))
                SettingsRowDivider()
                BackgroundStep(index = 2, text = stringResource(R.string.settings_background_step_recents))
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_background_brands)) {
                vendors.forEachIndexed { index, info ->
                    if (index > 0) SettingsRowDivider()
                    val open = openVendor == info.id
                    SettingsRow(
                        title = info.name,
                        onClick = { openVendor = if (open) null else info.id },
                        trailing = {
                            Icon(
                                painter = painterResource(R.drawable.ic_lucide_chevron_right),
                                contentDescription = null,
                                tint = Dshm.colors.textTertiary,
                                modifier = Modifier
                                    .size(Dshm.spacing.topBarIcon)
                                    .rotate(if (open) 90f else 0f),
                            )
                        },
                    )
                    if (open) {
                        Text(
                            text = info.path,
                            style = Dshm.typography.rowPreview,
                            color = Dshm.colors.textSecondary,
                            modifier = Modifier.padding(
                                start = Dshm.spacing.lg,
                                end = Dshm.spacing.lg,
                                bottom = Dshm.spacing.md,
                            ),
                        )
                    }
                }
            }
        }
    }

    if (showBatteryExemptDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryExemptDialog = false },
            containerColor = Dshm.colors.surface,
            title = {
                Text(
                    text = stringResource(R.string.settings_background_exempt_dialog_title),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.settings_background_exempt_dialog_body),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBatteryExemptDialog = false
                    BackgroundLauncher.openAppDetails(context)
                }) {
                    Text(stringResource(R.string.settings_background_open_app_details), color = Dshm.colors.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryExemptDialog = false }) {
                    Text(stringResource(R.string.action_done), color = Dshm.colors.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun BackgroundStep(index: Int, text: String) {
    SettingsRow(
        title = text,
        leading = {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Dshm.colors.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = index.toString(),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.accent,
                )
            }
        },
    )
}

private data class VendorInfo(
    val id: String,
    val name: String,
    val key: String,
    val path: String,
)

@Composable
private fun allVendors(): List<VendorInfo> = listOf(
        VendorInfo(
            "vivo",
            stringResource(R.string.settings_background_vendor_vivo_name),
            stringResource(R.string.settings_background_vendor_vivo_key),
            stringResource(R.string.settings_background_vendor_vivo_path),
        ),
        VendorInfo(
            "xiaomi",
            stringResource(R.string.settings_background_vendor_xiaomi_name),
            stringResource(R.string.settings_background_vendor_xiaomi_key),
            stringResource(R.string.settings_background_vendor_xiaomi_path),
        ),
        VendorInfo(
            "huawei",
            stringResource(R.string.settings_background_vendor_huawei_name),
            stringResource(R.string.settings_background_vendor_huawei_key),
            stringResource(R.string.settings_background_vendor_huawei_path),
        ),
        VendorInfo(
            "honor",
            stringResource(R.string.settings_background_vendor_honor_name),
            stringResource(R.string.settings_background_vendor_honor_key),
            stringResource(R.string.settings_background_vendor_honor_path),
        ),
        VendorInfo(
            "oppo",
            stringResource(R.string.settings_background_vendor_oppo_name),
            stringResource(R.string.settings_background_vendor_oppo_key),
            stringResource(R.string.settings_background_vendor_oppo_path),
        ),
        VendorInfo(
            "samsung",
            stringResource(R.string.settings_background_vendor_samsung_name),
            stringResource(R.string.settings_background_vendor_samsung_key),
            stringResource(R.string.settings_background_vendor_samsung_path),
        ),
    )

private fun currentVendor(): String? {
    val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
    return when {
        "vivo" in brand || "iqoo" in brand -> "vivo"
        "xiaomi" in brand || "redmi" in brand -> "xiaomi"
        "huawei" in brand -> "huawei"
        "honor" in brand -> "honor"
        "oppo" in brand || "oneplus" in brand || "realme" in brand -> "oppo"
        "samsung" in brand -> "samsung"
        else -> null
    }
}
