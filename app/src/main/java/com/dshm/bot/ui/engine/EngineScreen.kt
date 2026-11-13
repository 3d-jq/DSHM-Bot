package com.dshm.bot.ui.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.core.design.rememberLoopValue
import com.dshm.bot.data.runtime.RuntimeState
import com.dshm.bot.data.runtime.shortRuntimeVersion
import com.dshm.bot.ui.components.FloatingIconButton
import com.dshm.bot.ui.settings.SettingsPage
import com.dshm.bot.ui.settings.SettingsSection
import com.dshm.bot.ui.settings.SettingsRow
import com.dshm.bot.ui.settings.SettingsRowDivider
import com.dshm.bot.ui.settings.SettingsSwitchRow
import com.dshm.bot.ui.components.rememberWindowHeightDp

/** 默认下载地址：我们自己的 Release 运行时包。 */
const val DEFAULT_RUNTIME_URL =
    "https://github.com/3d-jq/DSHM-Bot/releases/latest/download/dshm-runtime-arm64-v8a.zip"

/**
 * 本机引擎页（对齐 dsh-Go `app/lib/ui/engine/engine_screen.dart`）：
 *
 * 顶栏（返回 + 标题 + 网页）→ Hero 状态卡 → 启动/停止/重启 → 引擎启动（自动启动）
 * → 日志终端井 → 更多（后台运行 / 诊断）→ 运行时信息 → 危险区（重置）。
 *
 * 页面骨架按设计文档 §8.1（设置子页模板）：分组卡、真实状态值、唯一主操作。
 */
@Composable
fun EngineScreen(
    state: RuntimeState,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onInstall: (String) -> Unit = {},
    onStart: () -> Unit = {},
    onStop: () -> Unit = {},
    onRestart: () -> Unit = {},
    onReset: (ResetKeep) -> Unit = {},
    onOpenWeb: () -> Unit = {},
    onOpenBackground: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    version: String = "—",
    nodeVersion: String = "—",
    bundledVersion: String = "—",
    installedVersion: String? = null,
    abi: String = "arm64-v8a",
    autoStart: Boolean = true,
    onAutoStartChange: (Boolean) -> Unit = {},
    logText: () -> String = { "" },
) {
    var showStopDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf(logText()) }
    LaunchedEffect(state) {
        while (true) {
            logs = logText()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val running = state is RuntimeState.Running
    val missing = state is RuntimeState.NotInstalled

    SettingsPage(
        title = stringResource(R.string.engine_title),
        onBack = { onBack?.invoke() },
        actions = {
            // 保留一个小的「网页版」入口：引擎跑着才可点。
            FloatingIconButton(
                icon = R.drawable.ic_lucide_globe,
                contentDescription = stringResource(R.string.engine_open_web),
                onClick = onOpenWeb,
                enabled = running,
            )
        },
    ) {
        item {
            EngineHero(
                state = state,
                version = version,
                nodeVersion = nodeVersion,
            )
        }

        item {
            EngineActions(
                state = state,
                onInstall = { onInstall(DEFAULT_RUNTIME_URL) },
                onStart = onStart,
                onStop = { showStopDialog = true },
                onRestart = onRestart,
            )
        }

        if (state is RuntimeState.Installing) {
            item {
                Column {
                    LinearProgressIndicator(
                        progress = { if (state.progress > 0) state.progress / 100f else 0f },
                        modifier = Modifier.fillMaxWidth(),
                        color = Dshm.colors.accent,
                    )
                    Spacer(Modifier.height(Dshm.spacing.xs))
                    Text(
                        text = if (state.progress > 0) {
                            "${state.message} ${state.progress}%"
                        } else {
                            state.message
                        },
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.textSecondary,
                    )
                }
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.engine_startup)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.engine_auto_start),
                    subtitle = stringResource(R.string.engine_auto_start_hint),
                    icon = R.drawable.ic_lucide_play,
                    checked = autoStart,
                    onCheckedChange = onAutoStartChange,
                )
            }
        }

        if (!missing) {
            item { EngineLogCard(logs = logs) }
        }

        item {
            SettingsSection(title = stringResource(R.string.engine_more)) {
                SettingsRow(
                    title = stringResource(R.string.engine_background),
                    icon = R.drawable.ic_lucide_smartphone,
                    subtitle = stringResource(R.string.engine_keep_alive_hint),
                    onClick = onOpenBackground,
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.engine_diagnostics),
                    icon = R.drawable.ic_lucide_info,
                    subtitle = stringResource(R.string.engine_diagnostics_hint),
                    onClick = onOpenDiagnostics,
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.engine_runtime_info)) {
                SettingsRow(
                    title = stringResource(R.string.engine_version),
                    icon = R.drawable.ic_lucide_box,
                    value = shortRuntimeVersion(installedVersion ?: bundledVersion),
                    trailing = {},
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.engine_arch),
                    icon = R.drawable.ic_lucide_cpu,
                    value = abi,
                    trailing = {},
                )
            }
        }

        if (!missing) {
            item {
                SettingsSection {
                    SettingsRow(
                        title = stringResource(R.string.engine_reset),
                        subtitle = stringResource(R.string.engine_reset_hint),
                        enabled = state !is RuntimeState.Starting && state !is RuntimeState.Installing,
                        onClick = { showResetDialog = true },
                        leading = {
                            Icon(
                                painter = painterResource(R.drawable.ic_lucide_trash_2),
                                contentDescription = null,
                                tint = Dshm.colors.danger,
                                modifier = Modifier.size(Dshm.spacing.icon - Dshm.spacing.xxs),
                            )
                        },
                        trailing = {},
                    )
                }
            }
        }
    }

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            containerColor = Dshm.colors.surface,
            title = {
                Text(
                    text = stringResource(R.string.engine_stop_title),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.engine_stop_body),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showStopDialog = false
                    onStop()
                }) {
                    Text(stringResource(R.string.engine_stop_short), color = Dshm.colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopDialog = false }) {
                    Text(stringResource(R.string.action_cancel), color = Dshm.colors.textSecondary)
                }
            },
        )
    }

    if (showResetDialog) {
        ResetDialog(
            onDismiss = { showResetDialog = false },
            onConfirm = { keep ->
                showResetDialog = false
                onReset(keep)
            },
        )
    }
}

/**
 * 重置运行环境：说清会删什么，并让用户选「保留对话与工作区」还是「全部删除」。
 * 保留的东西在下次装好运行时时会自动搬回来。
 */
@Composable
private fun ResetDialog(
    onDismiss: () -> Unit,
    onConfirm: (ResetKeep) -> Unit,
) {
    var keepWorkspace by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Dshm.colors.surface,
        title = {
            Text(
                text = stringResource(R.string.engine_reset_title),
                style = Dshm.typography.sheetTitle,
                color = Dshm.colors.textPrimary,
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.engine_reset_body),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
                Spacer(Modifier.height(Dshm.spacing.md))
                ResetKeepOption(
                    label = stringResource(R.string.engine_reset_keep_workspace),
                    checked = keepWorkspace,
                    onCheckedChange = { keepWorkspace = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(if (keepWorkspace) ResetKeep.KEEP_WORKSPACE else ResetKeep.DELETE_ALL)
            }) {
                Text(stringResource(R.string.engine_reset_delete), color = Dshm.colors.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = Dshm.colors.textSecondary)
            }
        },
    )
}

/** 重置弹窗里的整行勾选项。 */
@Composable
private fun ResetKeepOption(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dshm.radius.xs))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = Dshm.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(Dshm.spacing.selectionTick)
                .clip(CircleShape)
                .background(if (checked) Dshm.colors.accent else Color.Transparent)
                .border(
                    Dshm.spacing.hairline + Dshm.spacing.xs / 2,
                    if (checked) Dshm.colors.accent else Dshm.colors.textTertiary,
                    CircleShape,
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
        Spacer(Modifier.width(Dshm.spacing.md))
        Text(
            text = label,
            style = Dshm.typography.settingsRowSubtitle,
            color = Dshm.colors.textPrimary,
        )
    }
}

/** 引擎状态白卡：状态环 + 名称 + 当前状态，DSH / Node.js 两列左右对称。 */
@Composable
private fun EngineHero(
    state: RuntimeState,
    version: String,
    nodeVersion: String,
) {
    val tone = engineTone(state)
    val detail = when (state) {
        is RuntimeState.Running -> stringResource(
            R.string.engine_detail_running,
            state.endpoint.baseUrl,
        )
        is RuntimeState.Installed -> stringResource(R.string.engine_value_installed)
        is RuntimeState.Starting -> stringResource(R.string.engine_value_starting)
        is RuntimeState.Installing -> stringResource(R.string.engine_value_installing)
        is RuntimeState.Failed -> state.message
        RuntimeState.Stopped -> stringResource(R.string.engine_value_stopped)
        RuntimeState.NotInstalled -> stringResource(R.string.engine_value_not_installed)
    }
    SettingsSection {
        Column(
            modifier = Modifier.padding(
                horizontal = Dshm.spacing.lg,
                vertical = Dshm.spacing.md,
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EngineStatusRing(state = state, tone = tone)
                Spacer(Modifier.width(Dshm.spacing.lg))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.engine_local_title),
                        style = Dshm.typography.pageTitle,
                        color = Dshm.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = detail,
                        style = Dshm.typography.rowPreview,
                        color = if (state is RuntimeState.Failed) Dshm.colors.danger else Dshm.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(Dshm.spacing.xl))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.md),
            ) {
                EngineStat(value = "v$version", label = "DSH", modifier = Modifier.weight(1f))
                EngineStat(value = "v$nodeVersion", label = "Node.js", modifier = Modifier.weight(1f))
            }
        }
    }
}

/** dsh-Go 的 EngineRing：运行时呼吸，启动/安装时转弧，停止时留一圈灰环。 */
@Composable
private fun EngineStatusRing(
    state: RuntimeState,
    tone: Color,
    size: Dp = Dshm.spacing.settingsAvatar,
) {
    val busy = state is RuntimeState.Starting || state is RuntimeState.Installing
    val running = state is RuntimeState.Running
    val strokeWidth = Dshm.spacing.ringStroke
    val spin = rememberLoopValue(
        profile = Dshm.motion,
        label = "engine-ring-spin",
        from = 0f,
        to = 360f,
        durationMs = 1_100,
        repeatMode = RepeatMode.Restart,
        easing = LinearEasing,
    )
    val breath = rememberLoopValue(
        profile = Dshm.motion,
        label = "engine-ring-breath",
        from = 1f,
        to = 0.55f,
        durationMs = 1_200,
    )
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = strokeWidth.toPx()
            drawArc(
                color = tone.copy(alpha = 0.16f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            when {
                busy -> drawArc(
                    color = tone,
                    startAngle = spin,
                    sweepAngle = 72f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                running -> drawArc(
                    color = tone.copy(alpha = breath),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                else -> drawArc(
                    color = tone,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_lucide_server),
            contentDescription = null,
            tint = tone,
            modifier = Modifier.size(Dshm.spacing.icon),
        )
    }
}

/** 状态对应的语义色。 */
@Composable
private fun engineTone(state: RuntimeState): Color = when (state) {
    is RuntimeState.Running -> Dshm.colors.accent
    is RuntimeState.Failed -> Dshm.colors.danger
    is RuntimeState.Installing, is RuntimeState.Starting -> Dshm.colors.warning
    else -> Dshm.colors.textTertiary
}

@Composable
private fun EngineStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = value, style = Dshm.typography.rowTitle, color = Dshm.colors.textPrimary)
        Text(text = label, style = Dshm.typography.rowTime, color = Dshm.colors.textTertiary)
    }
}

/**
 * 「启动 · 停止 · 重启」三颗等宽胶囊（对齐 dsh-Go 的 `EngineActions`）。
 * 没装运行时时换成「安装运行时」主按钮。
 */
@Composable
private fun EngineActions(
    state: RuntimeState,
    onInstall: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    if (state is RuntimeState.NotInstalled) {
        EngineCapsule(
            icon = R.drawable.ic_lucide_download,
            label = stringResource(R.string.engine_install_action),
            primary = true,
            onClick = onInstall,
        )
        return
    }
    val running = state is RuntimeState.Running
    val busy = state is RuntimeState.Starting || state is RuntimeState.Installing
    Row(
        horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        EngineCapsule(
            icon = R.drawable.ic_lucide_play,
            label = if (state is RuntimeState.Failed) {
                stringResource(R.string.engine_start_again)
            } else {
                stringResource(R.string.engine_start_short)
            },
            primary = !running,
            busy = busy,
            onClick = if (running) null else onStart,
            modifier = Modifier.weight(1f),
        )
        EngineCapsule(
            icon = R.drawable.ic_lucide_circle_stop,
            label = stringResource(R.string.engine_stop_short),
            onClick = if (running) onStop else null,
            modifier = Modifier.weight(1f),
        )
        EngineCapsule(
            icon = R.drawable.ic_lucide_refresh_cw,
            label = stringResource(R.string.engine_restart_short),
            onClick = if (running) onRestart else null,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 一颗引擎操作胶囊：圆角胶囊 + 图标 + 短文案。
 * 主操作（引擎没跑时）用主色底，其余是安静的中性底。
 */
@Composable
private fun EngineCapsule(
    icon: Int,
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    busy: Boolean = false,
) {
    val enabled = onClick != null
    val fill = if (primary && enabled) {
        Dshm.colors.accent
    } else {
        Dshm.colors.textPrimary.copy(alpha = 0.06f)
    }
    val ink = if (primary && enabled) Dshm.colors.onAccent else Dshm.colors.textPrimary
    val opacity = if (enabled || busy) 1f else 0.42f
    Row(
        modifier = modifier
            .alpha(opacity)
            .heightIn(min = Dshm.spacing.iconButton)
            .clip(RoundedCornerShape(Dshm.radius.pill))
            .background(fill)
            .then(
                if (enabled) {
                    Modifier.clickable(onClick = onClick!!)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = Dshm.spacing.md),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(Dshm.spacing.topBarIcon),
                strokeWidth = Dshm.spacing.hairline * 2,
                color = ink,
            )
        } else {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(Dshm.spacing.topBarIcon),
            )
        }
        Spacer(Modifier.width(Dshm.spacing.xs))
        Text(
            text = label,
            style = Dshm.typography.settingsRowSubtitle,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 日志终端：深色底、等宽字、去掉 ANSI 色码，最新一条在最下面。 */
@Composable
private fun EngineLogCard(logs: String) {
    val context = LocalContext.current
    val clipboard = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    var copied by remember { mutableStateOf(false) }
    val lines = remember(logs) {
        logs.replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map(::stripAnsi)
            .filter { it.isNotBlank() }
            .toList()
    }
    val logState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty() && logState.firstVisibleItemIndex <= 1) {
            logState.scrollToItem(0)
        }
    }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_500)
            copied = false
        }
    }
    // 高度跟着屏幕走：约 42%，但不低于 260、不高于 520（对齐 dsh-Go 的 logHeight）。
    val screenHeight = rememberWindowHeightDp()
    val wellHeight = (screenHeight * LOG_HEIGHT_FRACTION)
        .coerceIn(Dshm.spacing.logWellMin, Dshm.spacing.logWellMax)
    val shape = RoundedCornerShape(Dshm.radius.settingsCard)
    val mono = Dshm.typography.rowPreview.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Dshm.colors.terminalBackground),
    ) {
        Row(
            modifier = Modifier.padding(
                start = Dshm.spacing.lg,
                end = Dshm.spacing.xs,
                top = Dshm.spacing.xxs,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.engine_logs),
                style = Dshm.typography.rowTitle,
                color = Dshm.colors.terminalText,
            )
            Spacer(Modifier.width(Dshm.spacing.sm))
            Text(
                text = stringResource(R.string.engine_lines, lines.size),
                style = Dshm.typography.rowTime,
                color = Dshm.colors.terminalText.copy(alpha = 0.45f),
            )
            if (copied) {
                Spacer(Modifier.width(Dshm.spacing.xs))
                Text(
                    text = stringResource(R.string.engine_logs_copied),
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.accent,
                )
            }
            Spacer(Modifier.weight(1f))
            EngineLogAction(
                icon = R.drawable.ic_lucide_copy,
                label = stringResource(R.string.engine_logs_copy),
                enabled = lines.isNotEmpty(),
                onClick = {
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("engine-log", lines.joinToString("\n")),
                    )
                    copied = true
                },
            )
            EngineLogAction(
                icon = R.drawable.ic_lucide_share_2,
                label = stringResource(R.string.engine_logs_share),
                enabled = lines.isNotEmpty(),
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n"))
                    }
                    context.startActivity(
                        Intent.createChooser(send, context.getString(R.string.engine_logs_share)),
                    )
                },
            )
        }
        LazyColumn(
            state = logState,
            reverseLayout = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(wellHeight)
                .padding(
                    start = Dshm.spacing.lg,
                    end = Dshm.spacing.lg,
                    top = Dshm.spacing.xxs,
                    bottom = Dshm.spacing.md,
                ),
        ) {
            if (lines.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.engine_logs_empty),
                        style = mono,
                        color = Dshm.colors.textTertiary,
                    )
                }
            } else {
                items(lines.asReversed()) { line ->
                    SelectionContainer {
                        Text(
                            text = line,
                            style = mono,
                            color = logLineColor(line),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineLogAction(
    icon: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(Dshm.spacing.iconButton)
            .clip(CircleShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = if (enabled) {
                Dshm.colors.terminalText
            } else {
                Dshm.colors.terminalText.copy(alpha = 0.3f)
            },
            modifier = Modifier.size(Dshm.spacing.topBarIcon - Dshm.spacing.xxs),
        )
    }
}

private val ANSI_ESCAPE = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]""")

private fun stripAnsi(line: String): String = line.replace(ANSI_ESCAPE, "")

@Composable
private fun logLineColor(line: String): Color {
    val lower = line.lowercase()
    return when {
        line.startsWith("[dsh-app]") || line.startsWith("[dshm]") -> Dshm.colors.accent
        "error" in lower || "fatal" in lower || "exception" in lower -> Dshm.colors.danger
        else -> Dshm.colors.terminalText
    }
}

/** 日志井占屏高的比例（dsh-Go 的 logHeight）。 */
private const val LOG_HEIGHT_FRACTION = 0.42f
