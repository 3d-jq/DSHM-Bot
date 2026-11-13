package com.dshm.bot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import org.json.JSONObject
import androidx.core.graphics.toColorInt

/**
 * 个性化：DS Bot 团队级 `set-prefs` 面板（主题 / 强调色 / 布局 / 宽度 / 思考行 / 动效）
 * + Agent 预设入口。
 *
 * 这些是**团队**偏好：存在引擎里，网页端和其它设备共用，**本机也照着渲染**
 * （配色、强调色、气泡布局、内容宽度、动效、思考过程）。改了立刻在界面上生效。
 *
 * 不再编辑本机 `AGENTS.md`（那是引擎级指令，不属于 DS Bot 个性化），也没有
 * 「可视化回复」——它只是往 AGENTS.md 里塞一段 dsh-ui 提示词，而我们用的是 DS Bot 视图卡。
 */
@Composable
fun PersonalizationScreen(
    viewModel: BotPrefsViewModel,
    onBack: () -> Unit,
    onOpenAgentPresets: () -> Unit = {},
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val prefs = state.prefs
    // 保存走乐观更新（BotPrefsViewModel.set），不整页禁用、也不插「保存中」行，
    // 否则每点一次 Switch 都会先禁用再启用、文字和 toggle 肉眼闪一下。
    val enabled = !state.loading && state.error == null

    SettingsPage(
        title = stringResource(R.string.settings_personalization),
        onBack = onBack,
        actions = { SettingsRefreshButton(onClick = viewModel::load) },
    ) {
        when {
            state.loading -> item { SettingsLoading() }
            state.error != null -> item { SettingsError(state.error.orEmpty(), onRetry = viewModel::load) }
            else -> {
                item {
                    SettingsSection(
                        title = stringResource(R.string.bot_prefs_appearance),
                        footer = stringResource(R.string.bot_prefs_team_hint),
                    ) {
                        ChoiceRow(
                            title = stringResource(R.string.bot_prefs_theme),
                            options = BOT_THEMES,
                            selected = prefs.theme,
                            enabled = enabled,
                            onSelect = { viewModel.set(JSONObject().put("theme", it)) },
                        )
                        SettingsRowDivider()
                        Column(Modifier.padding(Dshm.spacing.lg)) {
                            Text(
                                text = stringResource(R.string.bot_prefs_accent),
                                style = Dshm.typography.settingsRowTitle,
                                color = Dshm.colors.textPrimary,
                            )
                            Text(
                                text = stringResource(R.string.bot_prefs_accent_hint),
                                style = Dshm.typography.settingsRowSubtitle,
                                color = Dshm.colors.textSecondary,
                                modifier = Modifier.padding(top = Dshm.spacing.xxs),
                            )
                            Row(
                                modifier = Modifier.padding(top = Dshm.spacing.md),
                                horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AccentSwatch(
                                    color = null,
                                    label = stringResource(R.string.bot_prefs_accent_default),
                                    selected = prefs.accent == null,
                                    enabled = enabled,
                                    onClick = { viewModel.set(JSONObject().put("accent", JSONObject.NULL)) },
                                )
                                BOT_ACCENTS.forEach { (id, color) ->
                                    AccentSwatch(
                                        color = color,
                                        label = id,
                                        selected = prefs.accent.equals(color, ignoreCase = true),
                                        enabled = enabled,
                                        onClick = { viewModel.set(JSONObject().put("accent", color)) },
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    SettingsSection(title = stringResource(R.string.bot_prefs_chat)) {
                        ChoiceRow(
                            title = stringResource(R.string.bot_prefs_layout),
                            options = BOT_LAYOUTS,
                            selected = prefs.layout,
                            enabled = enabled,
                            onSelect = { viewModel.set(JSONObject().put("layout", it)) },
                        )
                        SettingsRowDivider()
                        // 宽度跟着布局走：扁平布局限的是正文宽度，气泡布局限的是气泡宽度
                        // （网页端也是两个字段分开存）。
                        val flat = prefs.layout == "flat"
                        WidthRow(
                            title = stringResource(
                                if (flat) R.string.bot_prefs_flat_width else R.string.bot_prefs_bubble_width,
                            ),
                            value = if (flat) prefs.flatWidth else prefs.width,
                            enabled = enabled,
                            onCommit = { percent ->
                                viewModel.set(
                                    JSONObject().put(if (flat) "flatWidth" else "width", percent),
                                )
                            },
                        )
                        SettingsRowDivider()
                        SettingsSwitchRow(
                            title = stringResource(R.string.bot_prefs_thinking),
                            subtitle = stringResource(R.string.bot_prefs_thinking_hint),
                            checked = prefs.thinking,
                            enabled = enabled,
                            onCheckedChange = { viewModel.set(JSONObject().put("thinking", it)) },
                        )
                    }
                }

                item {
                    SettingsSection(title = stringResource(R.string.bot_prefs_motion)) {
                        ChoiceRow(
                            title = stringResource(R.string.bot_prefs_motion_level),
                            options = BOT_MOTIONS,
                            selected = prefs.motion,
                            enabled = enabled,
                            onSelect = { value ->
                                viewModel.set(
                                    JSONObject().put(
                                        "motion",
                                        if (value == "normal") JSONObject.NULL else value,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.bot_prefs_work_style)) {
                SettingsRow(
                    title = stringResource(R.string.settings_agent_presets),
                    subtitle = stringResource(R.string.presets_subtitle),
                    onClick = onOpenAgentPresets,
                )
            }
        }
    }
}
private data class Choice(val id: String, val labelRes: Int)

private val BOT_LAYOUTS = listOf(
    Choice("bubble", R.string.bot_prefs_layout_bubble),
    Choice("flat", R.string.bot_prefs_layout_flat),
)

private val BOT_THEMES = listOf(
    Choice("deepseek", R.string.bot_prefs_theme_deepseek),
    Choice("mono", R.string.bot_prefs_theme_mono),
    Choice("paper", R.string.bot_prefs_theme_paper),
    Choice("moonlight", R.string.bot_prefs_theme_moonlight),
)
private val BOT_MOTIONS = listOf(
    Choice("normal", R.string.bot_prefs_motion_normal),
    Choice("quiet", R.string.bot_prefs_motion_quiet),
    Choice("lively", R.string.bot_prefs_motion_lively),
)
private val BOT_ACCENTS = listOf(
    "deepseek" to "#4D6BFE",
    "charcoal" to "#2F3437",
    "graphite" to "#6B7280",
    "rose" to "#E8688A",
    "sky" to "#2C9CD6",
    "tangerine" to "#E88A3C",
    "cobalt" to "#3A5FD9",
)

/**
 * 宽度滑块（50%..100%，步长 5%）。
 *
 * 拖动时只改本地草稿，松手才写引擎 —— 否则每挪一格就发一次 `set-prefs`，
 * 而且本机宽度会跟着抖。
 */
@Composable
private fun WidthRow(
    title: String,
    value: Int,
    enabled: Boolean,
    onCommit: (Int) -> Unit,
) {
    var draft by remember { mutableFloatStateOf(value.toFloat()) }
    // 引擎回报的新值（写失败回滚、别的设备改了）同步进草稿。
    LaunchedEffect(value) {
        if (value.toFloat() != draft) draft = value.toFloat()
    }
    Column(Modifier.padding(Dshm.spacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = Dshm.typography.settingsRowTitle,
                color = Dshm.colors.textPrimary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${draft.toInt()}%",
                style = Dshm.typography.rowTime,
                color = Dshm.colors.textSecondary,
            )
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onCommit(draft.toInt()) },
            valueRange = 50f..100f,
            steps = 9,
            enabled = enabled,
        )
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    options: List<Choice>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.padding(Dshm.spacing.lg)) {
        Text(
            text = title,
            style = Dshm.typography.settingsRowTitle,
            color = Dshm.colors.textPrimary,
        )
        Row(
            modifier = Modifier
                .padding(top = Dshm.spacing.md)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dshm.radius.md))
                .background(Dshm.colors.surfaceMuted)
                .padding(Dshm.spacing.xxs),
        ) {
            options.forEach { option ->
                val active = option.id == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Dshm.radius.sm))
                        .background(if (active) Dshm.colors.surface else Color.Transparent)
                        .clickable(enabled = enabled) { onSelect(option.id) }
                        .padding(vertical = Dshm.spacing.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(option.labelRes),
                        style = Dshm.typography.rowTitle,
                        color = if (active) Dshm.colors.accent else Dshm.colors.textSecondary,
                    )
                }
            }
        }
    }
}
@Composable
private fun AccentSwatch(
    color: String?,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val fill = color?.let { runCatching { Color(it.toColorInt()) }.getOrNull() }
        ?: Dshm.colors.accent
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (color == null) Dshm.colors.surfaceMuted else fill)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Dshm.colors.accent else Dshm.colors.divider,
                shape = CircleShape,
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (color == null) {
            Text(
                text = stringResource(R.string.bot_prefs_accent_auto_short),
                style = Dshm.typography.rowTime,
                color = Dshm.colors.textTertiary,
            )
        }
    }
}
