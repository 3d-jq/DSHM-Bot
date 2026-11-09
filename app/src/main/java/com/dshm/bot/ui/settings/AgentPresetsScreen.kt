package com.dshm.bot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.AgentPresetDocument
import com.dshm.bot.data.remote.AgentPresetInfo
import com.dshm.bot.data.remote.BUILT_IN_AGENT_PRESETS

/**
 * 「Agent 预设」页，对齐 dsh-Go `app/lib/ui/features/agent_presets_page.dart`：
 *
 * 列出引擎提供的预设（标准模式 / PTC / 极简 / 创造模式 + 用户自己建的），
 * 点一行弹出操作：设为新对话默认 / 查看配置（原始 YAML）/ 删除。
 *
 * 两条硬约束跟 dsh-Go 一样：
 * 1. 加载失败的预设不能被设为默认；
 * 2. **默认预设不能删** —— 得先把别的预设设为默认（删除项置灰并说明原因）。
 *
 * 「新建 / 编辑」需要创造模式会话（cordis）来落地两个文件，本项目还没接，
 * 按「能力没接的标不可用」的原则标注，不做假按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPresetsScreen(
    viewModel: EngineSettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var actionFor by remember { mutableStateOf<AgentPresetInfo?>(null) }
    var document by remember { mutableStateOf<AgentPresetDocument?>(null) }
    var deleteFor by remember { mutableStateOf<AgentPresetInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.refreshAgentPresets() }

    val presets = state.agentPresets

    SettingsPage(
        title = stringResource(R.string.presets_title),
        onBack = onBack,
        actions = { SettingsRefreshButton(onClick = viewModel::refreshAgentPresets) },
    ) {
        when {
            state.agentPresetsError != null && presets.isEmpty() -> item {
                SettingsError(state.agentPresetsError.orEmpty(), onRetry = viewModel::refreshAgentPresets)
            }
            presets.isEmpty() -> item {
                SettingsEmpty(stringResource(R.string.presets_empty))
            }
            else -> {
                if (error != null) {
                    item { SettingsError(error.orEmpty()) }
                }
                item {
                    SettingsSection {
                        presets.forEachIndexed { index, preset ->
                            if (index > 0) SettingsRowDivider()
                            PresetRow(preset = preset, onClick = { actionFor = preset })
                        }
                    }
                }
            }
        }
    }

    // 行操作弹层
    val action = actionFor
    if (action != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { actionFor = null },
            sheetState = sheetState,
            containerColor = Dshm.colors.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = Dshm.spacing.lg)
                    .padding(bottom = Dshm.spacing.xxl),
            ) {
                Text(
                    text = presetDisplayName(action),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
                action.description?.let {
                    Spacer(Modifier.heightIn(min = 0.dp))
                    Text(
                        text = it,
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.textSecondary,
                        modifier = Modifier.padding(top = Dshm.spacing.xs),
                    )
                }
                Spacer(Modifier.heightIn(min = Dshm.spacing.md))
                PresetActionRow(
                    title = stringResource(
                        if (action.isDefault) {
                            R.string.presets_already_default
                        } else {
                            R.string.presets_set_default
                        },
                    ),
                    icon = R.drawable.ic_lucide_check,
                    enabled = !action.isDefault && action.broken == null,
                    onClick = {
                        error = null
                        viewModel.setDefaultAgentPreset(action.id) { failure ->
                            if (failure != null) {
                                error = failure
                            } else {
                                actionFor = null
                            }
                        }
                    },
                )
                PresetActionRow(
                    title = stringResource(R.string.presets_view_config),
                    icon = R.drawable.ic_lucide_file_text,
                    onClick = {
                        viewModel.agentPresetDocument(action.id) { loaded ->
                            document = loaded
                        }
                    },
                )
                PresetActionRow(
                    title = stringResource(R.string.presets_copy_id),
                    icon = R.drawable.ic_lucide_copy,
                    onClick = {
                        clipboard.setText(AnnotatedString(action.id))
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.presets_copied),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        actionFor = null
                    },
                )
                PresetActionRow(
                    title = stringResource(R.string.presets_copy_config),
                    icon = R.drawable.ic_lucide_copy,
                    onClick = {
                        viewModel.agentPresetDocument(action.id) { loaded ->
                            clipboard.setText(AnnotatedString(loaded?.content ?: action.id))
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.presets_copied),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            actionFor = null
                        }
                    },
                )
                if (action.id !in BUILT_IN_AGENT_PRESETS) {
                    PresetActionRow(
                        title = stringResource(R.string.presets_delete),
                        icon = R.drawable.ic_lucide_trash_2,
                        enabled = !action.isDefault,
                        subtitle = if (action.isDefault) {
                            stringResource(R.string.presets_delete_default_hint)
                        } else {
                            null
                        },
                        onClick = {
                            actionFor = null
                            deleteFor = action
                        },
                    )
                }
            }
        }
    }

    // 「查看配置」：原始 YAML
    val shown = document
    if (shown != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { document = null },
            sheetState = sheetState,
            containerColor = Dshm.colors.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = Dshm.spacing.lg)
                    .padding(bottom = Dshm.spacing.xxl),
            ) {
                Text(
                    text = stringResource(R.string.presets_view_config),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
                Spacer(Modifier.heightIn(min = Dshm.spacing.md))
                Text(
                    text = shown.content.ifBlank { stringResource(R.string.presets_no_config) },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = Dshm.colors.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }

    // 删除确认
    val deleting = deleteFor
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            containerColor = Dshm.colors.surface,
            title = {
                Text(
                    text = stringResource(R.string.presets_delete_title, presetDisplayName(deleting)),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.presets_delete_hint),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteFor = null
                    error = null
                    viewModel.deleteAgentPreset(deleting) { failure ->
                        if (failure != null) error = failure
                    }
                }) {
                    Text(stringResource(R.string.action_delete), color = Dshm.colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteFor = null }) {
                    Text(stringResource(R.string.action_cancel), color = Dshm.colors.textSecondary)
                }
            },
        )
    }
}

/** 预设列表里的一行：名字 + 徽标 + 描述。 */
@Composable
private fun PresetRow(preset: AgentPresetInfo, onClick: () -> Unit) {
    val icon = presetIcon(preset.id)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dshm.spacing.rowMinHeight - Dshm.spacing.sm)
            .clickable(onClick = onClick)
            .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(Dshm.spacing.composerKey)
                .clip(CircleShape)
                .background(Dshm.colors.surfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = Dshm.colors.textPrimary,
                modifier = Modifier.size(Dshm.spacing.topBarIcon),
            )
        }
        Spacer(Modifier.width(Dshm.spacing.md))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = presetDisplayName(preset),
                    style = Dshm.typography.settingsRowTitle,
                    color = Dshm.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (preset.broken != null) {
                    Spacer(Modifier.width(Dshm.spacing.sm))
                    PresetBadge(
                        text = stringResource(R.string.presets_broken),
                        color = Dshm.colors.danger,
                    )
                } else if (preset.isDefault) {
                    Spacer(Modifier.width(Dshm.spacing.sm))
                    PresetBadge(
                        text = stringResource(R.string.presets_default_badge),
                        color = Dshm.colors.accent,
                    )
                }
            }
            preset.description?.let {
                Text(
                    text = it,
                    style = Dshm.typography.settingsRowSubtitle,
                    color = Dshm.colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** dsh-Go `agent_presets_page.dart`：四个内建预设各有图标，自定义用通用图标（统一单色）。 */
private fun presetIcon(id: String): Int = when (id) {
    "standard" -> R.drawable.ic_lucide_bot
    "ptc" -> R.drawable.ic_lucide_code
    "minimal" -> R.drawable.ic_lucide_sparkles
    "cordis" -> R.drawable.ic_lucide_puzzle
    else -> R.drawable.ic_lucide_sliders_horizontal
}

/** 操作弹层里的一行。 */
@Composable
private fun PresetActionRow(
    title: String,
    icon: Int,
    onClick: () -> Unit,
    enabled: Boolean = true,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Dshm.spacing.sm, vertical = Dshm.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = if (enabled) Dshm.colors.iconTint else Dshm.colors.textTertiary,
            modifier = Modifier.size(Dshm.spacing.topBarIcon),
        )
        Spacer(Modifier.width(Dshm.spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = Dshm.typography.settingsRowTitle,
                color = if (enabled) Dshm.colors.textPrimary else Dshm.colors.textTertiary,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textTertiary,
                )
            }
        }
    }
}

/** 预设行右侧的小徽标。 */
@Composable
private fun PresetBadge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = Dshm.typography.rowTime,
        color = color,
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = Dshm.spacing.sm, vertical = Dshm.spacing.xxs),
    )
}

/** 内建预设的官方中文名；自定义预设用自己起的名字。 */
fun presetDisplayName(preset: AgentPresetInfo): String = when (preset.id) {
    "standard" -> "标准模式"
    "ptc" -> "PTC 模式"
    "minimal" -> "极简模式"
    "cordis" -> "创造模式"
    else -> preset.displayName()
}
