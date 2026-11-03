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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.ui.components.FloatingIconButton

/** 测试用 tag：设置列表。 */
const val SETTINGS_LIST_TAG = "settings_list"

/** 设置子页统一骨架：固定顶栏 + LazyColumn 内容。 */
@Composable
fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    androidx.compose.material3.Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Dshm.colors.pageBackground,
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
            ) {
                FloatingIconButton(
                    icon = R.drawable.ic_lucide_arrow_left,
                    contentDescription = stringResource(R.string.action_back),
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = Dshm.spacing.iconButton + Dshm.spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = title,
                        style = Dshm.typography.pageTitle,
                        color = Dshm.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = Dshm.typography.rowPreview,
                            color = Dshm.colors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag(SETTINGS_LIST_TAG),
            contentPadding = PaddingValues(
                start = Dshm.spacing.lg,
                end = Dshm.spacing.lg,
                top = Dshm.spacing.sm,
                bottom = Dshm.spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xxl),
            content = content,
        )
    }
}

/** 一张设置卡：柔和悬浮阴影 + 细描边。 */
@Composable
fun SettingsSection(
    title: String? = null,
    footer: String? = null,
    state: SettingsSectionState = SettingsSectionState.NORMAL,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        if (title != null) {
            Text(
                text = title,
                style = Dshm.typography.settingsSectionTitle,
                color = if (state == SettingsSectionState.DANGER) Dshm.colors.danger else Dshm.colors.textSecondary,
                modifier = Modifier.padding(start = Dshm.spacing.lg, bottom = Dshm.spacing.xs),
            )
        }
        val shape = RoundedCornerShape(Dshm.radius.settingsCard)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Dshm.colors.surface),
            content = content,
        )
        if (footer != null) {
            Text(
                text = footer,
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textTertiary,
                modifier = Modifier.padding(start = Dshm.spacing.lg, end = Dshm.spacing.lg, top = Dshm.spacing.sm),
            )
        }
    }
}

enum class SettingsSectionState { NORMAL, DANGER }

@Composable
fun SettingsRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = Dshm.spacing.lg, end = Dshm.spacing.lg),
        thickness = Dshm.spacing.hairline,
        color = Dshm.colors.divider,
    )
}

/** 普通设置行：左图标 + 标题/副标题 + 右侧值/控件。 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    leading: @Composable (() -> Unit)? = null,
    badge: @Composable (() -> Unit)? = null,
    subtitle: String? = null,
    subtitleColor: androidx.compose.ui.graphics.Color? = null,
    value: String? = null,
    valueColor: androidx.compose.ui.graphics.Color? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dshm.spacing.rowMinHeight - Dshm.spacing.sm)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(Dshm.spacing.md))
        } else if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = if (enabled) Dshm.colors.iconTint else Dshm.colors.textTertiary,
                modifier = Modifier.size(Dshm.spacing.icon - Dshm.spacing.xxs),
            )
            Spacer(Modifier.width(Dshm.spacing.md))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = Dshm.typography.settingsRowTitle,
                    color = if (enabled) Dshm.colors.textPrimary else Dshm.colors.textTertiary,
                )
                if (badge != null) {
                    Spacer(Modifier.width(Dshm.spacing.xs))
                    badge()
                }
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = Dshm.typography.settingsRowSubtitle,
                    color = subtitleColor ?: Dshm.colors.textSecondary,
                )
            }
        }
        if (value != null) {
            Text(
                text = value,
                style = Dshm.typography.settingsRowValue,
                color = valueColor ?: Dshm.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = Dshm.spacing.sm),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(Dshm.spacing.sm))
            trailing()
        } else if (onClick != null) {
            Spacer(Modifier.width(Dshm.spacing.xs))
            Icon(
                painter = painterResource(R.drawable.ic_lucide_chevron_right),
                contentDescription = null,
                tint = Dshm.colors.textTertiary,
                modifier = Modifier.width(Dshm.spacing.topBarIcon),
            )
        }
    }
}

@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    subtitle: String? = null,
    subtitleColor: androidx.compose.ui.graphics.Color? = null,
    enabled: Boolean = true,
) {
    SettingsRow(
        title = title,
        modifier = modifier,
        icon = icon,
        subtitle = subtitle,
        subtitleColor = subtitleColor,
        enabled = enabled,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
    )
}

@Composable
fun SettingsLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(Dshm.spacing.xxl), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Dshm.colors.accent)
    }
}

@Composable
fun SettingsHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = Dshm.typography.rowPreview,
        color = Dshm.colors.textTertiary,
        modifier = modifier.padding(horizontal = Dshm.spacing.sm),
    )
}

@Composable
fun SettingsError(text: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = Dshm.spacing.sm)) {
        Text(text, style = Dshm.typography.rowPreview, color = Dshm.colors.danger)
        if (onRetry != null) {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.action_retry), color = Dshm.colors.accent)
            }
        }
    }
}

@Composable
fun SettingsEmpty(text: String) {
    SettingsSection {
        Text(
            text = text,
            style = Dshm.typography.rowPreview,
            color = Dshm.colors.textSecondary,
            modifier = Modifier.padding(Dshm.spacing.lg),
        )
    }
}

/** 输入框（设置弹层内共用）。 */
@Composable
fun SettingsInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val shape = RoundedCornerShape(Dshm.radius.md)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = Dshm.typography.settingsRowTitle.copy(color = Dshm.colors.textPrimary),
        cursorBrush = SolidColor(Dshm.colors.accent),
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Dshm.colors.surface)
            .border(Dshm.spacing.hairline, Dshm.colors.divider, shape)
            .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.md),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty() && placeholder != null) {
                    Text(
                        text = placeholder,
                        style = Dshm.typography.settingsRowTitle,
                        color = Dshm.colors.textTertiary,
                    )
                }
                inner()
            }
        },
    )
}

/** 点击行后在行下方弹出的选择面板（右对齐，使用设置卡片的颜色与圆角）。 */
@Composable
fun SettingsAnchoredMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchorHeightPx: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, anchorHeightPx),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val shape = RoundedCornerShape(Dshm.radius.lg)
        Column(
            modifier = Modifier
                .width(Dshm.spacing.menuWidth)
                .shadow(
                    elevation = Dshm.spacing.cardElevation,
                    shape = shape,
                    ambientColor = Dshm.colors.shadowCard,
                    spotColor = Dshm.colors.shadowCard,
                )
                .clip(shape)
                .background(Dshm.colors.surface)
                .border(Dshm.spacing.hairline, Dshm.colors.divider, shape)
                .padding(vertical = Dshm.spacing.xs),
            content = content,
        )
    }
}

@Composable
fun SettingsMenuItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = Dshm.typography.settingsRowTitle,
            color = if (selected) Dshm.colors.accent else Dshm.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(
                text = "✓",
                style = Dshm.typography.settingsRowValue,
                color = Dshm.colors.accent,
            )
        }
    }
}

/** 顶栏统一刷新按钮（和返回键同一套悬浮圆钮）。 */
@Composable
fun SettingsRefreshButton(onClick: () -> Unit) {
    FloatingIconButton(
        icon = R.drawable.ic_lucide_refresh_cw,
        contentDescription = stringResource(R.string.action_refresh),
        onClick = onClick,
    )
}

@Composable
fun SettingsPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) Dshm.colors.danger else Dshm.colors.accent,
            contentColor = if (danger) Dshm.colors.onInk else Dshm.colors.onAccent,
            disabledContainerColor = Dshm.colors.surfaceMuted,
            disabledContentColor = Dshm.colors.textTertiary,
        ),
        shape = RoundedCornerShape(Dshm.radius.md),
    ) {
        Text(text, style = Dshm.typography.rowTitle)
    }
}

@Composable
fun SettingsDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(text, color = Dshm.colors.danger, style = Dshm.typography.rowTitle)
    }
}

/** 只读值行：用于 schema 里无法编辑的字段。 */
@Composable
fun SettingsReadOnlyValue(label: String, value: String, subtitle: String? = null) {
    SettingsRow(
        title = label,
        subtitle = subtitle,
        value = value.takeIf { it.isNotBlank() } ?: stringResource(R.string.settings_value_not_set),
        enabled = false,
    )
}
