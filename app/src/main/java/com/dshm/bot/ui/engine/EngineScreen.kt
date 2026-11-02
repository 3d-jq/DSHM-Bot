package com.dshm.bot.ui.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.runtime.RuntimeState
import com.dshm.bot.ui.components.FloatingIconButton

/** 默认下载地址：我们自己的 Release 运行时包。 */
const val DEFAULT_RUNTIME_URL =
    "https://github.com/3d-jq/DSHM-Bot/releases/latest/download/dshm-runtime-arm64-v8a.zip"

/**
 * 本机引擎页（参照 dsh-Go `engine_screen.dart` + `TerminalCard`）：
 * hero 状态卡 → 启动/停止/重启 → 启动日志终端卡 → 更多 → 运行时信息 → 危险区。
 */
@Composable
fun EngineScreen(
    state: RuntimeState,
    onBack: (() -> Unit)? = null,
    onInstall: (String) -> Unit = {},
    onStart: () -> Unit = {},
    onStop: () -> Unit = {},
    onRestart: () -> Unit = {},
    onReset: () -> Unit = {},
    version: String = "—",
    nodeVersion: String = "—",
    abi: String = "arm64-v8a",
    logText: () -> String = { "" },
    modifier: Modifier = Modifier,
) {
    var showResetDialog by remember { mutableStateOf(false) }
    val logs = remember(state) { logText() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Dshm.colors.background,
        topBar = { EngineHeader(onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Dshm.spacing.md),
        ) {
            HeroCard(state = state, version = version, nodeVersion = nodeVersion)
            ActionRow(state = state, onInstall = onInstall, onStart = onStart, onStop = onStop, onRestart = onRestart)
            if (state is RuntimeState.Installing) {
                LinearProgressIndicator(
                    progress = { if (state.progress > 0) state.progress / 100f else 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = Dshm.colors.accent,
                )
                Text(
                    text = "${state.message} ${if (state.progress > 0) "${state.progress}%" else ""}",
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
            }
            TerminalCard(logs = logs)
            MoreCard()
            RuntimeInfoCard(version = version, abi = abi)
            DangerCard(onReset = { showResetDialog = true })
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            containerColor = Dshm.colors.surface,
            title = {
                Text(
                    text = stringResource(R.string.engine_reset_title),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.engine_reset_body),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    onReset()
                }) {
                    Text(stringResource(R.string.engine_reset), color = Dshm.colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(android.R.string.cancel), color = Dshm.colors.accent)
                }
            },
        )
    }
}

@Composable
private fun EngineHeader(onBack: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            FloatingIconButton(
                icon = R.drawable.ic_lucide_arrow_left,
                contentDescription = stringResource(R.string.action_back),
                onClick = onBack,
            )
            Spacer(Modifier.width(Dshm.spacing.sm))
        }
        Text(
            text = stringResource(R.string.engine_title),
            style = Dshm.typography.pageTitle,
            color = Dshm.colors.textPrimary,
        )
    }
}

@Composable
private fun HeroCard(state: RuntimeState, version: String, nodeVersion: String) {
    val tone = when (state) {
        is RuntimeState.Running -> Dshm.colors.success
        is RuntimeState.Failed -> Dshm.colors.danger
        is RuntimeState.Installing, is RuntimeState.Starting -> Dshm.colors.warning
        else -> Dshm.colors.textTertiary
    }
    val detail = when (state) {
        is RuntimeState.Running -> stringResource(R.string.engine_value_running) + " · " + state.endpoint.baseUrl
        is RuntimeState.Installed -> stringResource(R.string.engine_value_installed)
        is RuntimeState.Starting -> stringResource(R.string.engine_value_starting)
        is RuntimeState.Installing -> stringResource(R.string.engine_value_installing)
        is RuntimeState.Failed -> state.message
        RuntimeState.Stopped -> stringResource(R.string.engine_value_stopped)
        RuntimeState.NotInstalled -> stringResource(R.string.engine_value_not_installed)
    }
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(Dshm.spacing.xxl)
                    .clip(CircleShape)
                    .border(Dshm.spacing.hairline * 2, tone, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    painter = painterResource(R.drawable.ic_lucide_server),
                    contentDescription = null,
                    tint = tone,
                    modifier = Modifier.size(Dshm.spacing.icon),
                )
            }
            Spacer(Modifier.width(Dshm.spacing.md))
            Column {
                Text(
                    text = stringResource(R.string.engine_local_title),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
                Text(
                    text = detail,
                    style = Dshm.typography.rowPreview,
                    color = if (state is RuntimeState.Failed) Dshm.colors.danger else Dshm.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(Dshm.spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.xxl)) {
            Stat(value = "v$version", label = "DSH")
            Stat(value = "v$nodeVersion", label = "Node.js")
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(text = value, style = Dshm.typography.rowTitle, color = Dshm.colors.textPrimary)
        Text(text = label, style = Dshm.typography.rowTime, color = Dshm.colors.textTertiary)
    }
}

@Composable
private fun ActionRow(
    state: RuntimeState,
    onInstall: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    if (state is RuntimeState.NotInstalled || state is RuntimeState.Failed) {
        Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xs)) {
            Text(
                text = stringResource(R.string.engine_intro),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
            )
            Text(
                text = stringResource(R.string.engine_intro_hint),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textTertiary,
            )
        }
        Button(onClick = { onInstall(DEFAULT_RUNTIME_URL) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.engine_install_action))
        }
        return
    }
    val running = state is RuntimeState.Running
    val ready = state is RuntimeState.Installed || state is RuntimeState.Stopped
    Row(horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm), modifier = Modifier.fillMaxWidth()) {
        FilledTonalButton(
            onClick = onStart,
            enabled = ready,
            modifier = Modifier.weight(1f).heightIn(min = Dshm.spacing.composerKey),
        ) { Text(stringResource(R.string.engine_start_short)) }
        OutlinedButton(
            onClick = onStop,
            enabled = running,
            modifier = Modifier.weight(1f).heightIn(min = Dshm.spacing.composerKey),
        ) { Text(stringResource(R.string.engine_stop_short)) }
        OutlinedButton(
            onClick = onRestart,
            enabled = running,
            modifier = Modifier.weight(1f).heightIn(min = Dshm.spacing.composerKey),
        ) { Text(stringResource(R.string.engine_restart_short)) }
    }
}

@Composable
private fun TerminalCard(logs: String) {
    val clipboard = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    var copied by remember { mutableStateOf(false) }
    val lines = remember(logs) { logs.lines().filter { it.isNotBlank() } }
    val shape = RoundedCornerShape(Dshm.radius.lg)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Dshm.colors.terminalBackground),
    ) {
        Row(
            modifier = Modifier.padding(start = Dshm.spacing.lg, end = Dshm.spacing.md, top = Dshm.spacing.sm, bottom = Dshm.spacing.xs),
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
                color = Dshm.colors.textTertiary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (copied) stringResource(R.string.engine_logs_copied) else stringResource(R.string.engine_logs_copy),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.accent,
                modifier = Modifier
                    .clickable {
                        clipboard.setPrimaryClip(ClipData.newPlainText("engine-log", logs))
                        copied = true
                    }
                    .padding(Dshm.spacing.xs),
            )
        }
        // 只展示最新 20 行（最新在上），避免超长启动命令把日志井撑满。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dshm.spacing.xxl * 6)
                .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xxs),
        ) {
            val tail = if (lines.isEmpty()) {
                listOf(stringResource(R.string.engine_logs_empty))
            } else {
                lines.takeLast(20).reversed()
            }
            tail.forEach { line ->
                Text(
                    text = line,
                    style = Dshm.typography.rowPreview.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    ),
                    color = if (lines.isEmpty()) Dshm.colors.textTertiary else Dshm.colors.terminalText,
                )
            }
        }
    }
}

@Composable
private fun MoreCard() {
    SettingsCard {
        Spacer(Modifier.height(Dshm.spacing.xs))
        Text(
            text = stringResource(R.string.engine_background),
            style = Dshm.typography.rowTitle,
            color = Dshm.colors.textPrimary,
        )
        Text(
            text = stringResource(R.string.engine_keep_alive_hint),
            style = Dshm.typography.rowPreview,
            color = Dshm.colors.textSecondary,
        )
        Spacer(Modifier.height(Dshm.spacing.sm))
        Text(
            text = stringResource(R.string.engine_diagnostics),
            style = Dshm.typography.rowTitle,
            color = Dshm.colors.textPrimary,
        )
        Text(
            text = stringResource(R.string.engine_diagnostics_hint),
            style = Dshm.typography.rowPreview,
            color = Dshm.colors.textSecondary,
        )
    }
}

@Composable
private fun RuntimeInfoCard(version: String, abi: String) {
    SettingsCard {
        Spacer(Modifier.height(Dshm.spacing.xs))
        InfoRow(stringResource(R.string.engine_version), version, mono = true)
        HorizontalDivider(color = Dshm.colors.divider, thickness = Dshm.spacing.hairline)
        InfoRow(stringResource(R.string.engine_arch), abi, mono = true)
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dshm.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = Dshm.typography.rowPreview,
            color = Dshm.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = if (mono) Dshm.typography.rowPreview.copy(fontFamily = FontFamily.Monospace) else Dshm.typography.rowPreview,
            color = Dshm.colors.textPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun DangerCard(onReset: () -> Unit) {
    SettingsCard {
        Text(
            text = stringResource(R.string.engine_reset),
            style = Dshm.typography.rowTitle,
            color = Dshm.colors.danger,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onReset)
                .padding(vertical = Dshm.spacing.xs),
        )
        Text(
            text = stringResource(R.string.engine_reset_hint),
            style = Dshm.typography.rowPreview,
            color = Dshm.colors.textTertiary,
        )
    }
}

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
            .border(Dshm.spacing.hairline, Dshm.colors.divider, shape)
            .padding(Dshm.spacing.lg),
        content = content,
    )
}


