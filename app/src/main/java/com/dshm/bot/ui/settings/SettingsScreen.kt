package com.dshm.bot.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import com.dshm.bot.BuildConfig
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.domain.model.ThemeMode
import com.dshm.bot.ui.components.FloatingIconButton
import kotlinx.coroutines.launch

/**
 * 设置页（参照 dsh-Go 的信息架构：按意图分组、每行带图标且右侧显示当前值）。
 * 目前可用的真实设置是「主题」；其余行为 DSH 接入后的占位入口。
 */
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onOpenEngine: () -> Unit,
    engineStatus: String,
    modifier: Modifier = Modifier,
) {
    var showThemeDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val comingSoon = stringResource(R.string.settings_coming_soon)

    val comingSoonRow: () -> Unit = {
        scope.launch { snackbarHostState.showSnackbar(comingSoon) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Dshm.colors.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatingIconButton(
                    icon = R.drawable.ic_lucide_arrow_left,
                    contentDescription = stringResource(R.string.action_back),
                    onClick = onBack,
                )
                Spacer(Modifier.width(Dshm.spacing.sm))
                Text(
                    text = stringResource(R.string.settings_title),
                    style = Dshm.typography.pageTitle,
                    color = Dshm.colors.textPrimary,
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .testTag(SETTINGS_LIST_TAG),
                contentPadding = PaddingValues(
                    start = Dshm.spacing.lg,
                    end = Dshm.spacing.lg,
                    top = Dshm.spacing.sm,
                    bottom = Dshm.spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Dshm.spacing.lg),
            ) {
                item { OverviewCard() }

                item {
                    SettingsGroup(stringResource(R.string.settings_group_ai)) {
                        SettingsRow(R.drawable.ic_lucide_sparkles, stringResource(R.string.settings_model), stringResource(R.string.settings_value_not_set), comingSoonRow)
                        SettingsRow(R.drawable.ic_lucide_shield, stringResource(R.string.settings_permission), stringResource(R.string.settings_value_default), comingSoonRow)
                        SettingsRow(R.drawable.ic_lucide_palette, stringResource(R.string.settings_personalization), stringResource(R.string.settings_value_not_set), comingSoonRow)
                    }
                }

                item {
                    SettingsGroup(stringResource(R.string.settings_group_capability)) {
                        SettingsRow(R.drawable.ic_lucide_smartphone, stringResource(R.string.settings_phone_control), stringResource(R.string.settings_value_off), comingSoonRow)
                        SettingsRow(R.drawable.ic_lucide_search, stringResource(R.string.settings_web_search), stringResource(R.string.settings_value_not_configured), comingSoonRow)
                        SettingsRow(R.drawable.ic_lucide_puzzle, stringResource(R.string.settings_plugins), stringResource(R.string.settings_value_plugins_count, 0), comingSoonRow)
                    }
                }

                item {
                    SettingsGroup(stringResource(R.string.settings_group_appearance)) {
                        SettingsRow(
                            icon = R.drawable.ic_lucide_palette,
                            title = stringResource(R.string.settings_theme),
                            value = themeLabel(uiState.themeMode),
                            onClick = { showThemeDialog = true },
                        )
                        SettingsRow(R.drawable.ic_lucide_type, stringResource(R.string.settings_chat_font), stringResource(R.string.settings_value_standard), comingSoonRow)
                        SettingsRow(R.drawable.ic_lucide_paw_print, stringResource(R.string.settings_pets), stringResource(R.string.settings_value_not_set), comingSoonRow)
                    }
                }

                item {
                    SettingsGroup(stringResource(R.string.settings_group_general)) {
                        SettingsRow(R.drawable.ic_lucide_layout_dashboard, stringResource(R.string.settings_interface), stringResource(R.string.settings_value_native), comingSoonRow)
                        SettingsRow(R.drawable.ic_lucide_languages, stringResource(R.string.settings_language), stringResource(R.string.settings_value_follow_system), comingSoonRow)
                        SettingsRow(R.drawable.ic_lucide_bell, stringResource(R.string.settings_notifications), stringResource(R.string.settings_value_on), comingSoonRow)
                        SettingsRow(R.drawable.ic_lucide_mic, stringResource(R.string.settings_voice), stringResource(R.string.settings_value_auto), comingSoonRow)
                        SettingsRow(R.drawable.ic_lucide_gauge, stringResource(R.string.settings_performance), stringResource(R.string.settings_value_auto), comingSoonRow)
                        SettingsRow(R.drawable.ic_lucide_battery_charging, stringResource(R.string.settings_background_running), stringResource(R.string.settings_value_needs_setup), comingSoonRow)
                    }
                }

                item {
                    SettingsGroup(stringResource(R.string.settings_group_host)) {
                        SettingsRow(R.drawable.ic_lucide_server, stringResource(R.string.settings_local_engine), engineStatus, onOpenEngine)
                        SettingsRow(R.drawable.ic_lucide_sliders_horizontal, stringResource(R.string.settings_engine_params), stringResource(R.string.settings_value_default), comingSoonRow)
                    }
                }

                item {
                    SettingsGroup(stringResource(R.string.settings_group_about)) {
                        SettingsRow(R.drawable.ic_lucide_info, stringResource(R.string.settings_version), BuildConfig.VERSION_NAME, null)
                        SettingsRow(R.drawable.ic_lucide_bot, stringResource(R.string.settings_dsh_version), stringResource(R.string.settings_value_not_linked), null)
                        SettingsRow(R.drawable.ic_lucide_file_text, stringResource(R.string.settings_licenses), null, comingSoonRow)
                    }
                }
            }
        }
    }

    if (showThemeDialog) {
        ThemeDialog(
            current = uiState.themeMode,
            onPick = { mode ->
                onThemeChange(mode)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false },
        )
    }
}

/** 设置卡片：柔和阴影悬浮（和顶栏/输入框的悬浮按钮同一质感）。 */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Dshm.radius.lg)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = Dshm.spacing.cardElevation,
                shape = shape,
                ambientColor = Dshm.colors.shadowCard,
                spotColor = Dshm.colors.shadowCard,
            )
            .clip(shape)
            .background(Dshm.colors.surface)
            .border(Dshm.spacing.hairline, Dshm.colors.divider, shape),
        content = content,
    )
}

@Composable
private fun OverviewCard() {
    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dshm.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(Dshm.spacing.avatar)
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
            Spacer(Modifier.width(Dshm.spacing.md))
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = Dshm.typography.rowTitle,
                    color = Dshm.colors.textPrimary,
                )
                Spacer(Modifier.width(Dshm.spacing.xxs))
                Text(
                    text = stringResource(R.string.settings_overview_host),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            style = Dshm.typography.rowPreview,
            color = Dshm.colors.textSecondary,
            modifier = Modifier.padding(start = Dshm.spacing.sm, bottom = Dshm.spacing.sm),
        )
        SettingsCard {
            content()
        }
    }
}

@Composable
private fun SettingsRow(
    @DrawableRes icon: Int,
    title: String,
    value: String?,
    onClick: (() -> Unit)?,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dshm.spacing.composerKey)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = Dshm.colors.iconTint,
                modifier = Modifier.size(Dshm.spacing.settingsRowIcon),
            )
            Spacer(Modifier.width(Dshm.spacing.md))
            Text(
                text = title,
                style = Dshm.typography.rowTitle,
                color = Dshm.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (value != null) {
                Text(
                    text = value,
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textTertiary,
                )
            }
            if (onClick != null) {
                Spacer(Modifier.width(Dshm.spacing.xs))
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_chevron_right),
                    contentDescription = null,
                    tint = Dshm.colors.textTertiary,
                    modifier = Modifier.size(Dshm.spacing.topBarIcon),
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = Dshm.spacing.lg, end = Dshm.spacing.lg),
            thickness = Dshm.spacing.hairline,
            color = Dshm.colors.divider,
        )
    }
}

@Composable
private fun ThemeDialog(
    current: ThemeMode,
    onPick: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Dshm.colors.surface,
        title = {
            Text(
                text = stringResource(R.string.settings_theme_dialog_title),
                style = Dshm.typography.sheetTitle,
                color = Dshm.colors.textPrimary,
            )
        },
        text = {
            Column {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(mode) }
                            .padding(vertical = Dshm.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == current, onClick = { onPick(mode) })
                        Spacer(Modifier.width(Dshm.spacing.sm))
                        Text(
                            text = themeLabel(mode),
                            style = Dshm.typography.rowTitle,
                            color = Dshm.colors.textPrimary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel), color = Dshm.colors.accent)
            }
        },
    )
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    },
)

/** 测试用 tag：设置列表（用于滚动到指定行）。 */
const val SETTINGS_LIST_TAG = "settings_list"


