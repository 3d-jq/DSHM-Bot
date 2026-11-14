package com.dshm.bot.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.core.design.DshmMotion
import com.dshm.bot.ui.components.FloatingIconButton

/** 测试用 tag：设置列表。 */
const val SETTINGS_LIST_TAG = "settings_list"

/**
 * 全项目表单输入框统一用新建 Bot「名字 / 角色」那套 M3 `OutlinedTextField`：
 * `radius.md` 圆角 + Dshm 边框/底色/游标色。对话输入框（`ChatComposer`）是带附件、
 * 语音、发送键的专用复合控件，不在这里、也不要改成这套。
 */
@Composable
fun dshmFieldShape(): Shape = RoundedCornerShape(Dshm.radius.md)

@Composable
fun dshmFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = Dshm.colors.divider,
    focusedBorderColor = Dshm.colors.accent,
    disabledBorderColor = Dshm.colors.divider,
    errorBorderColor = Dshm.colors.danger,
    unfocusedContainerColor = Dshm.colors.surface,
    focusedContainerColor = Dshm.colors.surface,
    disabledContainerColor = Dshm.colors.surface,
    errorContainerColor = Dshm.colors.surface,
    cursorColor = Dshm.colors.accent,
    focusedLabelColor = Dshm.colors.accent,
    unfocusedLabelColor = Dshm.colors.textSecondary,
    unfocusedPlaceholderColor = Dshm.colors.textTertiary,
    focusedPlaceholderColor = Dshm.colors.textTertiary,
)

/** 设置子页统一骨架：固定顶栏 + LazyColumn 内容。 */
@Composable
fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    androidx.compose.material3.Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Dshm.colors.pageBackground,
        bottomBar = { bottomBar?.invoke() },
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
                modifier = Modifier.padding(start = Dshm.spacing.lg, bottom = Dshm.spacing.sm),
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

/**
 * 列表底部「添加 xxx」行左侧的圆形加号。
 *
 * 行首有首字母方块的列表（模型渠道、密钥）底部再加一个「添加」行时，
 * 要放一个同尺寸的方块，标题才不会和上面几行错位。
 */
@Composable
fun SettingsAddTile() {
    Box(
        modifier = Modifier
            .size(Dshm.spacing.composerKey)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lucide_plus),
            contentDescription = null,
            tint = Dshm.colors.accent,
            modifier = Modifier.size(Dshm.spacing.topBarIcon),
        )
    }
}

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
    /** 局部 opt-in：长值行让标题保持单行，值按剩余宽度尾部省略。 */
    singleLineTitle: Boolean = false,
    maxValueWidth: Dp? = null,
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
                    maxLines = if (singleLineTitle) 1 else Int.MAX_VALUE,
                    overflow = if (singleLineTitle) TextOverflow.Ellipsis else TextOverflow.Clip,
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
                    // 副标题最多两行：同一张卡里几行的高度才一致（dsh-Go 同理）。
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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
                modifier = Modifier
                    .padding(start = Dshm.spacing.sm)
                    .then(if (maxValueWidth != null) Modifier.widthIn(max = maxValueWidth) else Modifier),
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
fun SettingsError(text: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
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

/**
 * 表单输入框（设置弹层 / Bot 设置 / 定时任务 / 账号 / 模型供应商等共用）。
 *
 * 现在统一是 M3 [OutlinedTextField]，和新建 Bot 的名字 / 角色同款；`placeholder`
 * 文案作为浮动 label 显示，避免和框内占位文字重复。保留原签名，所有调用点无需改。
 */
@Composable
fun SettingsInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    /** 输入框尾部控件（如 API Key 的显示/隐藏）。 */
    trailing: (@Composable () -> Unit)? = null,
    /** 覆盖浮动 label；不传时用 [placeholder]。 */
    label: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
) {
    val fieldLabel = (label ?: placeholder)?.takeIf { it.isNotEmpty() }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        readOnly = false,
        textStyle = Dshm.typography.settingsRowTitle.copy(color = Dshm.colors.textPrimary),
        label = fieldLabel?.let { text -> { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        placeholder = if (label != null && placeholder != null) {
            { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else {
            null
        },
        trailingIcon = trailing,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        maxLines = if (singleLine) 1 else 8,
        shape = dshmFieldShape(),
        colors = dshmFieldColors(),
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
        // 主按钮统一高度：同类按钮在不同页面不能高矮不一。
        modifier = modifier.fillMaxWidth().heightIn(min = Dshm.spacing.iconButton),
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

/**
 * 全屏表单底部的「取消」：**白色胶囊**，与旁边的实心主按钮同高同形（M3 默认圆角），
 * 一眼能看出是按钮 —— 纯文字 TextButton 没有底，会让人以为它不是可点的。
 */
@Composable
fun SettingsCancelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = Dshm.spacing.iconButton),
        colors = ButtonDefaults.buttonColors(
            containerColor = Dshm.colors.floatingSurface,
            contentColor = Dshm.colors.textPrimary,
            disabledContainerColor = Dshm.colors.surfaceMuted,
            disabledContentColor = Dshm.colors.textTertiary,
        ),
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
    /** 可选的删除图标（dsh-Go 的 TextButton.icon）。 */
    @DrawableRes icon: Int? = null,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = if (enabled) Dshm.colors.danger else Dshm.colors.textTertiary,
                modifier = Modifier.size(Dshm.spacing.settingsRowIcon),
            )
            Spacer(Modifier.width(Dshm.spacing.xs))
        }
        Text(
            text = text,
            color = if (enabled) Dshm.colors.danger else Dshm.colors.textTertiary,
            style = Dshm.typography.rowTitle,
        )
    }
}

/**
 * 可折叠的分组卡（dsh-Go 的 `SchemaCollapsedGroup`）：
 * 标题行显示「标题 · N 项」和展开箭头，点一下展开 / 收起。
 */
@Composable
fun SettingsCollapsedSection(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val shape = RoundedCornerShape(Dshm.radius.settingsCard)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Dshm.colors.surface)
            .border(Dshm.spacing.hairline, Dshm.colors.divider, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 和设置行一样高（56dp），别比里面的行矮一截。
                .heightIn(min = Dshm.spacing.rowMinHeight - Dshm.spacing.sm)
                .clickable { expanded = !expanded }
                .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = Dshm.typography.settingsRowTitle,
                color = Dshm.colors.textPrimary,
            )
            Spacer(Modifier.width(Dshm.spacing.sm))
            Text(
                text = stringResource(R.string.settings_count_items, count),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textTertiary,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                painter = painterResource(
                    if (expanded) R.drawable.ic_lucide_chevrons_up_down else R.drawable.ic_lucide_chevrons_up_down,
                ),
                contentDescription = null,
                tint = Dshm.colors.textTertiary,
                modifier = Modifier.size(Dshm.spacing.topBarIcon),
            )
        }
        if (expanded) {
            content()
        }
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

// ---------------------------------------------------------------------------
// 胶囊 / 分段 / 勾选（dsh-Go 的 CapsuleGrid、SegmentedPill、SelectionTick）
// ---------------------------------------------------------------------------

/**
 * 胶囊选项（dsh-Go 的 `CapsuleChoiceChip`）：选中的那颗是主色描边 + 主色字，
 * 没选中的只有一圈浅描边。用于横向一排的预设切换。
 */
@Composable
fun SettingsChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = if (selected) Dshm.colors.accent else Dshm.colors.divider
    val ink = if (selected) Dshm.colors.accent else Dshm.colors.textSecondary
    Text(
        text = label,
        style = Dshm.typography.settingsRowSubtitle,
        color = ink,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(Dshm.radius.pill))
            .border(Dshm.spacing.hairline, border, RoundedCornerShape(Dshm.radius.pill))
            .background(if (selected) Dshm.colors.accent.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
    )
}

/**
 * 分段控件：一条胶囊底槽，选中项是一颗会滑动的胶囊。
 *
 * dsh-Go 用它代替 Material 的 SegmentedButton —— 后者的等宽分段在窄屏或大字号下
 * 会把中文标签折成一列一个。这里各段等宽、文字永远一行。
 */
@Composable
fun SettingsSegmentedPill(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    reduceMotion: Boolean = false,
) {
    if (options.isEmpty()) return
    val index = options.indexOf(selected).let { if (it < 0) 0 else it }
    val inset = Dshm.spacing.segmentedInset
    val track = Dshm.colors.textPrimary.copy(alpha = 0.06f)
    // 深色主题下底槽是「白 7%」，滑块要更亮一档才分得开。
    val thumb = if (Dshm.colors.isDark) {
        Dshm.colors.textPrimary.copy(alpha = 0.2f)
    } else {
        Dshm.colors.surface
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(Dshm.spacing.segmentedHeight)
            .clip(RoundedCornerShape(Dshm.radius.pill))
            .background(track),
    ) {
        val segment = (maxWidth - inset * 2) / options.size
        val offset by animateDpAsState(
            targetValue = inset + segment * index,
            animationSpec = tween(
                // 动效档位（团队偏好 `prefs.motion`）：`quiet` 不播这段动画。
                durationMillis = if (reduceMotion) 0 else Dshm.motion.quickMs,
                easing = DshmMotion.standard,
            ),
            label = "segmented-thumb",
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(offset.roundToPx(), 0) }
                .width(segment)
                .height(Dshm.spacing.segmentedHeight - inset * 2)
                .clip(RoundedCornerShape(Dshm.radius.pill))
                .background(thumb),
        )
        Row(modifier = Modifier.fillMaxSize()) {
            options.forEachIndexed { position, label ->
                val active = position == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(
                            if (enabled) {
                                Modifier.clickable { onSelect(label) }
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = Dshm.typography.rowPreview,
                        color = if (active) Dshm.colors.textPrimary else Dshm.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = Dshm.spacing.sm),
                    )
                }
            }
        }
    }
}

/**
 * 等宽的快捷胶囊网格：每行 [columns] 个，横向纵向都留 8dp。
 * 网格铺满父级，所以它的左右边缘和上面的字段对齐。
 */
@Composable
fun SettingsCapsuleGrid(
    columns: Int,
    children: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
) {
    if (columns <= 0 || children.isEmpty()) return
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
    ) {
        children.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm)) {
                row.forEach { child ->
                    Box(Modifier.weight(1f)) { child() }
                }
                // 末行不足时补空位，保持列宽一致。
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** 网格里的一颗快捷胶囊：安静的一格，选中时反色。 */
@Composable
fun SettingsChoiceCapsule(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fill = if (selected) {
        Dshm.colors.textPrimary
    } else {
        Dshm.colors.textPrimary.copy(alpha = 0.05f)
    }
    val ink = if (selected) Dshm.colors.surface else Dshm.colors.textSecondary
    Box(
        modifier = modifier
            .height(Dshm.spacing.capsuleHeight)
            .clip(RoundedCornerShape(Dshm.radius.pill))
            .background(fill)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = Dshm.typography.rowPreview,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Dshm.spacing.sm),
        )
    }
}

/** 勾选圆点：选中是实心主色 + 对勾，未选中是空心圆环。 */
@Composable
fun SettingsSelectionTick(
    checked: Boolean,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
    val ring = Dshm.colors.textPrimary.copy(alpha = 0.18f)
    val fill = if (dimmed) {
        Dshm.colors.textPrimary.copy(alpha = 0.28f)
    } else {
        Dshm.colors.accent
    }
    Box(
        modifier = modifier
            .size(Dshm.spacing.selectionTick)
            .clip(CircleShape)
            .then(
                if (checked) {
                    Modifier.background(fill)
                } else {
                    Modifier.border(Dshm.spacing.hairline + Dshm.spacing.xs / 2, ring, CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_check),
                contentDescription = null,
                tint = Dshm.colors.onAccent,
                modifier = Modifier.size(Dshm.spacing.topBarIcon - Dshm.spacing.xs),
            )
        }
    }
}

/** 档位行：勾选圆点在左，名字在右，整行可点。 */
@Composable
fun SettingsLevelRow(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dshm.spacing.levelRowHeight)
            .clip(RoundedCornerShape(Dshm.radius.md))
            .clickable(onClick = onClick)
            .padding(horizontal = Dshm.spacing.xs, vertical = Dshm.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsSelectionTick(checked = checked)
        Spacer(Modifier.width(Dshm.spacing.md))
        Text(
            text = label,
            style = Dshm.typography.settingsRowTitle,
            color = Dshm.colors.textPrimary,
        )
    }
}
