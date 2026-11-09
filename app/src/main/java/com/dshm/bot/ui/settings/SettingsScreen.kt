package com.dshm.bot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.dshm.bot.BuildConfig
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.domain.model.AppLanguage
import com.dshm.bot.data.remote.PluginBundleGroup
import com.dshm.bot.domain.model.ThemeMode

/**
 * 设置首页（对齐 dsh-Go 设置 IA）：
 * 搜索、账号概览，然后 AI / 能力 / 外观 / 通用 / 主机 / 关于六组。
 * 每一行的右侧值都来自真实状态，不再有写死的占位文案。
 */
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    engineSettings: EngineSettingsViewModel,
    botPrefs: BotPrefsViewModel,
    onBack: () -> Unit,
    onOpenEngine: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onOpenChatFont: () -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onOpenPage: (SettingsPageId) -> Unit,
    onOpenAccount: () -> Unit,
    accountName: String? = null,
    accountBalance: String? = null,
    accountAvatarUrl: String? = null,
    chatFontLabel: String,
    languageLabel: String,
    engineStatus: String,
    dshVersion: String? = null,
    reduceMotion: Boolean = false,
) {
    val context = LocalContext.current
    val engineState by engineSettings.ui.collectAsStateWithLifecycle()
    val prefsState by botPrefs.ui.collectAsStateWithLifecycle()
    val language = settingsLanguage()
    val providers = engineSettings.providers()
    val permission = engineSettings.permissionDefault()
    val permissionName = permission?.let { SettingsCopy.permissionLabel(it, language) }
    val webApi = engineState.description?.namespace("web")?.valueOf(listOf("searchProvider")) == "deepseek-search-api"
    val webServed = engineState.description?.served("web") == true
    // 数「页面上会显示的」那些：官方可选包（optional）和已装的都算 ——
    // 只数 installed 的话，官方可选包一个都不算，计数会是 0。
    val pluginCount = engineState.bundles
        .takeIf { it.isNotEmpty() }
        ?.count { it.group != PluginBundleGroup.HIDDEN }
    val batteryExempt = rememberBatteryExempt()
    val notificationsAllowed = rememberNotificationsAllowed()
    val accessibilityEnabled = rememberAccessibilityServiceEnabled()
    var showThemeMenu by remember { mutableStateOf(false) }
    var showLanguageMenu by remember { mutableStateOf(false) }
    var themeAnchorHeight by remember { mutableStateOf(0) }
    var languageAnchorHeight by remember { mutableStateOf(0) }


    val providerCount = providerDisplayCount(providers.profiles, engineState.listedProviders)
    val modelValue = if (providerCount == 0) null else {
        stringResource(R.string.settings_model_channels, providerCount)
    }
    val personalizationValue = stringResource(
        when (prefsState.prefs.theme) {
            "mono" -> R.string.bot_prefs_theme_mono
            "paper" -> R.string.bot_prefs_theme_paper
            "moonlight" -> R.string.bot_prefs_theme_moonlight
            else -> R.string.bot_prefs_theme_deepseek
        },
    )
    val webValue = when {
        !webServed -> null
        webApi -> stringResource(R.string.settings_web_search_api_short)
        else -> stringResource(R.string.settings_value_default)
    }
    val pluginValue = pluginCount?.let { stringResource(R.string.settings_plugin_count, it) }
    val phoneValue = when {
        !accessibilityEnabled -> stringResource(R.string.phone_control_service_disabled)
        uiState.phoneControlEnabled -> stringResource(R.string.settings_value_on)
        else -> stringResource(R.string.settings_value_off)
    }
    val notificationValue = when {
        !uiState.notificationsEnabled -> stringResource(R.string.settings_value_off)
        notificationsAllowed == false -> stringResource(R.string.settings_notifications_denied)
        notificationsAllowed == true -> stringResource(R.string.settings_value_on)
        else -> null
    }
    val backgroundValue = when (batteryExempt) {
        true -> stringResource(R.string.settings_background_allowed)
        false -> stringResource(R.string.settings_background_needs_setup)
        null -> null
    }

    SettingsPage(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
    ) {
        item {
            AccountOverviewCard(
                onClick = onOpenAccount,
                name = accountName,
                balance = accountBalance,
                avatarUrl = accountAvatarUrl,
                engineStatus = engineStatus,
            )
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_group_ai)) {
                SettingsRow(
                    title = stringResource(R.string.settings_model),
                    icon = R.drawable.ic_lucide_sparkles,
                    value = modelValue,
                    onClick = { onOpenPage(SettingsPageId.MODEL) },
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_permission),
                    icon = R.drawable.ic_lucide_shield,
                    value = permissionName,
                    onClick = { onOpenPage(SettingsPageId.PERMISSION) },
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_personalization),
                    icon = R.drawable.ic_lucide_palette,
                    value = personalizationValue,
                    onClick = { onOpenPage(SettingsPageId.PERSONALIZATION) },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_group_capability)) {
                SettingsRow(
                    title = stringResource(R.string.settings_web_search),
                    icon = R.drawable.ic_lucide_search,
                    value = webValue,
                    onClick = { onOpenPage(SettingsPageId.WEB_SEARCH) },
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_phone_control),
                    icon = R.drawable.ic_lucide_smartphone,
                    value = phoneValue,
                    valueColor = if (!accessibilityEnabled) Dshm.colors.textTertiary else null,
                    onClick = { onOpenPage(SettingsPageId.PHONE_CONTROL) },
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_plugins),
                    icon = R.drawable.ic_lucide_puzzle,
                    value = pluginValue,
                    onClick = { onOpenPage(SettingsPageId.PLUGINS) },
                )
            }
        }

        // DS Bot 团队设置（对齐网页端的 Secrets / Connectors 两页）。
        item {
            SettingsSection(title = stringResource(R.string.settings_group_team)) {
                SettingsRow(
                    title = stringResource(R.string.settings_team_secrets),
                    icon = R.drawable.ic_lucide_key_round,
                    onClick = { onOpenPage(SettingsPageId.SECRETS) },
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_team_connectors),
                    icon = R.drawable.ic_lucide_server,
                    onClick = { onOpenPage(SettingsPageId.CONNECTORS) },
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_team_capabilities),
                    icon = R.drawable.ic_lucide_package_search,
                    onClick = { onOpenPage(SettingsPageId.CAPABILITIES) },
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.schedules_title),
                    icon = R.drawable.ic_lucide_clock_3,
                    onClick = { onOpenPage(SettingsPageId.SCHEDULES) },
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_team_usage),
                    icon = R.drawable.ic_lucide_gauge,
                    onClick = { onOpenPage(SettingsPageId.USAGE) },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_group_appearance)) {
                Box(Modifier.onGloballyPositioned { themeAnchorHeight = it.size.height }) {
                    SettingsRow(
                        title = stringResource(R.string.settings_theme),
                        icon = R.drawable.ic_lucide_palette,
                        value = themeLabel(uiState.themeMode),
                        trailing = {
                            Icon(
                                painter = painterResource(R.drawable.ic_lucide_chevrons_up_down),
                                contentDescription = null,
                                tint = Dshm.colors.textTertiary,
                                modifier = Modifier.size(Dshm.spacing.topBarIcon),
                            )
                        },
                        onClick = { showThemeMenu = true },
                    )
                    SettingsAnchoredMenu(
                        expanded = showThemeMenu,
                        onDismiss = { showThemeMenu = false },
                        anchorHeightPx = themeAnchorHeight,
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            SettingsMenuItem(
                                text = themeLabel(mode),
                                selected = mode == uiState.themeMode,
                                onClick = {
                                    onThemeChange(mode)
                                    showThemeMenu = false
                                },
                            )
                        }
                    }
                }
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_chat_font),
                    icon = R.drawable.ic_lucide_type,
                    value = chatFontLabel,
                    onClick = onOpenChatFont,
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_group_general)) {
                SettingsRow(
                    title = stringResource(R.string.settings_interface),
                    icon = R.drawable.ic_lucide_layout_dashboard,
                    value = interfaceLabel(uiState.interfaceMode),
                    onClick = { onOpenPage(SettingsPageId.INTERFACE) },
                )
                SettingsRowDivider()
                Box(Modifier.onGloballyPositioned { languageAnchorHeight = it.size.height }) {
                    SettingsRow(
                        title = stringResource(R.string.settings_language),
                        icon = R.drawable.ic_lucide_languages,
                        value = languageLabel,
                        trailing = {
                            Icon(
                                painter = painterResource(R.drawable.ic_lucide_chevrons_up_down),
                                contentDescription = null,
                                tint = Dshm.colors.textTertiary,
                                modifier = Modifier.size(Dshm.spacing.topBarIcon),
                            )
                        },
                        onClick = { showLanguageMenu = true },
                    )
                    SettingsAnchoredMenu(
                        expanded = showLanguageMenu,
                        onDismiss = { showLanguageMenu = false },
                        anchorHeightPx = languageAnchorHeight,
                    ) {
                        AppLanguage.entries.forEach { language ->
                            SettingsMenuItem(
                                text = languageLabel(language),
                                selected = language == uiState.language,
                                onClick = {
                                    onLanguageChange(language)
                                    showLanguageMenu = false
                                },
                            )
                        }
                    }
                }
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_notifications),
                    icon = R.drawable.ic_lucide_bell,
                    value = notificationValue,
                    valueColor = if (notificationsAllowed == false) Dshm.colors.warning else null,
                    onClick = { onOpenPage(SettingsPageId.NOTIFICATIONS) },
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_voice),
                    icon = R.drawable.ic_lucide_mic,
                    value = voiceLabel(uiState.voiceMode),
                    onClick = { onOpenPage(SettingsPageId.VOICE) },
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_background_running),
                    icon = R.drawable.ic_lucide_battery_charging,
                    value = backgroundValue,
                    valueColor = if (batteryExempt == false) Dshm.colors.warning else null,
                    onClick = { onOpenPage(SettingsPageId.BACKGROUND) },
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_privacy),
                    icon = R.drawable.ic_lucide_shield,
                    onClick = { onOpenPage(SettingsPageId.PRIVACY) },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_group_host)) {
                SettingsRow(
                    title = stringResource(R.string.settings_local_engine),
                    icon = R.drawable.ic_lucide_server,
                    value = engineStatus,
                    onClick = onOpenEngine,
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_engine_params),
                    icon = R.drawable.ic_lucide_sliders_horizontal,
                    value = stringResource(R.string.settings_this_phone),
                    onClick = { onOpenPage(SettingsPageId.ENGINE_PARAMS) },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_group_about)) {
                SettingsRow(
                    title = stringResource(R.string.settings_version),
                    icon = R.drawable.ic_lucide_info,
                    value = BuildConfig.VERSION_NAME,
                    onClick = { onOpenPage(SettingsPageId.ABOUT) },
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_dsh_version),
                    icon = R.drawable.ic_lucide_bot,
                    value = dshVersion ?: stringResource(R.string.settings_value_not_linked),
                    onClick = { onOpenPage(SettingsPageId.ABOUT) },
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_licenses),
                    icon = R.drawable.ic_lucide_file_text,
                    onClick = { onOpenPage(SettingsPageId.LICENSES) },
                )
            }
        }
    }
}

@Composable
private fun AccountOverviewCard(
    onClick: () -> Unit,
    name: String?,
    balance: String?,
    avatarUrl: String?,
    engineStatus: String,
) {
    SettingsSection {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (avatarUrl != null) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(Dshm.spacing.settingsAvatar)
                        .clip(CircleShape),
                )
            } else if (name != null) {
                Box(
                    modifier = Modifier
                        .size(Dshm.spacing.settingsAvatar)
                        .clip(CircleShape)
                        .background(Dshm.colors.surfaceMuted),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = name.trim().take(1).uppercase(),
                        style = Dshm.typography.sheetTitle,
                        color = Dshm.colors.textSecondary,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(Dshm.spacing.settingsAvatar)
                        .clip(CircleShape)
                        .background(Dshm.colors.surfaceMuted),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_bot),
                        contentDescription = null,
                        tint = Dshm.colors.iconTint,
                        modifier = Modifier.size(Dshm.spacing.avatarGlyph),
                    )
                }
            }
            Spacer(Modifier.width(Dshm.spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = name ?: stringResource(R.string.app_name),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
                Text(
                    text = balance ?: stringResource(R.string.settings_overview_engine, engineStatus),
                    style = Dshm.typography.settingsRowValue,
                    color = Dshm.colors.textSecondary,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_lucide_chevron_right),
                contentDescription = null,
                tint = Dshm.colors.textTertiary,
                modifier = Modifier.size(Dshm.spacing.topBarIcon),
            )
        }
    }
}

