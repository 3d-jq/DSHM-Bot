package com.dshm.bot.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.ScheduledTask
import com.dshm.bot.data.remote.scheduleTimingLabel

/**
 * 「定时任务」页（对齐 DS-Bot 的 `client/schedules.js`）：
 *
 * 列出 DS Bot 挂在 Bot / 群聊上的定时任务，点开看内容并能删。
 *
 * 宿主没开 DSH 的 Automation tasks 插件时，接口会回 `available=false` ——
 * 那时说明白，不给一个空列表让人猜。
 */
@Composable
fun ScheduledTasksScreen(
    viewModel: ScheduledTasksViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<ScheduledTask?>(null) }
    var deleting by remember { mutableStateOf<ScheduledTask?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    SettingsPage(
        title = stringResource(R.string.schedules_title),
        onBack = onBack,
        actions = { SettingsRefreshButton(onClick = viewModel::refresh) },
    ) {
        when {
            state.loading && state.tasks.isEmpty() -> item { SettingsLoading() }
            !state.available -> item {
                SettingsSection {
                    SettingsRow(
                        title = stringResource(R.string.schedules_unavailable),
                        subtitle = stringResource(R.string.schedules_unavailable_hint),
                        enabled = false,
                    )
                }
            }
            state.tasks.isEmpty() -> item { SettingsEmpty(stringResource(R.string.schedules_empty)) }
            else -> {
                if (error != null) {
                    item { SettingsError(error.orEmpty()) }
                }
                item {
                    SettingsSection {
                        state.tasks.forEachIndexed { index, task ->
                            if (index > 0) SettingsRowDivider()
                            SettingsRow(
                                title = task.title.ifEmpty { stringResource(R.string.schedules_untitled) },
                                subtitle = scheduleTimingLabel(task),
                                value = stringResource(
                                    if (task.active) {
                                        R.string.schedules_active
                                    } else {
                                        R.string.schedules_inactive
                                    },
                                ),
                                valueColor = if (task.active) null else Dshm.colors.textTertiary,
                                onClick = { detail = task },
                            )
                        }
                    }
                }
            }
        }
    }

    // 任务详情：内容 + 删除
    val shown = detail
    if (shown != null) {
        AlertDialog(
            onDismissRequest = { detail = null },
            containerColor = Dshm.colors.surface,
            title = {
                Text(
                    text = shown.title.ifEmpty { stringResource(R.string.schedules_untitled) },
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
            },
            text = {
                Column {
                    Text(
                        text = scheduleTimingLabel(shown),
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.textSecondary,
                    )
                    Spacer(Modifier.heightIn(min = Dshm.spacing.sm))
                    Text(
                        text = shown.prompt,
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.textPrimary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    detail = null
                    deleting = shown
                }) {
                    Text(stringResource(R.string.action_delete), color = Dshm.colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { detail = null }) {
                    Text(stringResource(R.string.action_cancel), color = Dshm.colors.textSecondary)
                }
            },
        )
    }

    // 删除确认
    val removing = deleting
    if (removing != null) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = Dshm.colors.surface,
            title = {
                Text(
                    text = stringResource(R.string.schedules_delete_title, removing.title),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.schedules_delete_hint),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    error = null
                    viewModel.delete(removing) { failure -> error = failure }
                }) {
                    Text(stringResource(R.string.action_delete), color = Dshm.colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.action_cancel), color = Dshm.colors.textSecondary)
                }
            },
        )
    }
}
