package com.dshm.bot.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm

/**
 * 记忆面板：`memory-view` 的条目 + `memory-add` / `memory-edit` / `memory-forget`。
 *
 * 分工照 DS Bot：Bot 有 `own`（它自己的）和 `team`（全队都读）两份，群只有 `group` 一份。
 * 网页端那个「对着记忆说话」的入口走 `memory-ask`（要模型改），这里给的是直接增删改。
 */
@Composable
internal fun MemoryPanelDialog(
    ownerName: String,
    group: Boolean,
    entries: List<BotMemoryEntry>,
    sizes: Map<String, String>,
    summary: String?,
    summarizing: Boolean,
    fresh: Boolean,
    onAdd: (scope: String, topic: String?, text: String) -> Unit,
    onEdit: (entry: BotMemoryEntry, next: String, topic: String?) -> Unit,
    onForget: (BotMemoryEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val scopes = if (group) listOf("group") else listOf("own", "team")
    var scope by remember(group) { mutableStateOf(scopes.first()) }
    var text by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<BotMemoryEntry?>(null) }
    val current = entries.filter { it.scope == scope }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Dshm.colors.surface,
        title = { Text(stringResource(R.string.bot_settings_memory)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp)) {
                if (scopes.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.xs)) {
                        scopes.forEach { tag ->
                            val title = scopeTitle(tag, ownerName)
                            val size = sizes[tag]?.toIntOrNull()
                            ScopeChip(
                                label = if (size != null && size > 0) {
                                    "$title · " + stringResource(R.string.bot_details_memory_chars, size)
                                } else {
                                    title
                                },
                                picked = scope == tag,
                                onClick = { scope = tag },
                            )
                        }
                    }
                    Spacer(Modifier.size(Dshm.spacing.sm))
                }
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                ) {
                    summary?.takeIf { current.isNotEmpty() }?.let { text ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Dshm.radius.sm))
                                .background(Dshm.colors.surfaceMuted)
                                .padding(Dshm.spacing.sm),
                        ) {
                            Text(
                                text = stringResource(R.string.bot_details_memory_summary),
                                style = Dshm.typography.rowTime,
                                color = Dshm.colors.textTertiary,
                            )
                            Text(
                                text = text,
                                style = Dshm.typography.rowPreview,
                                color = Dshm.colors.textSecondary,
                            )
                            if (!fresh || summarizing) {
                                Text(
                                    text = stringResource(
                                        if (summarizing) {
                                            R.string.bot_details_memory_summary_writing
                                        } else {
                                            R.string.bot_details_memory_summary_stale
                                        },
                                    ),
                                    style = Dshm.typography.rowTime,
                                    color = Dshm.colors.warning,
                                )
                            }
                        }
                    }
                    if (current.isEmpty()) {
                        Text(
                            text = stringResource(R.string.bot_settings_memory_empty),
                            style = Dshm.typography.rowPreview,
                            color = Dshm.colors.textTertiary,
                        )
                    } else {
                        current.groupBy { it.topic.orEmpty() }.forEach { (topicName, groupEntries) ->
                            if (topicName.isNotEmpty()) {
                                Text(
                                    text = topicName,
                                    style = Dshm.typography.settingsSectionTitle,
                                    color = Dshm.colors.textSecondary,
                                )
                            }
                            groupEntries.forEach { entry ->
                                MemoryEntryRow(
                                    entry = entry,
                                    onEdit = { editing = entry },
                                    onForget = { onForget(entry) },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.size(Dshm.spacing.sm))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 2,
                    maxLines = 4,
                    placeholder = { Text(stringResource(R.string.bot_details_memory_add_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(Dshm.spacing.xs))
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.bot_details_memory_topic_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    onAdd(scope, topic.trim().takeIf { it.isNotEmpty() }, text.trim())
                    text = ""
                    topic = ""
                },
            ) {
                Text(stringResource(R.string.bot_details_memory_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )

    editing?.let { entry ->
        var draft by remember(entry.text) { mutableStateOf(entry.text) }
        var draftTopic by remember(entry.text) { mutableStateOf(entry.topic.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = Dshm.colors.surface,
            title = { Text(stringResource(R.string.bot_details_memory_edit_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draftTopic,
                        onValueChange = { draftTopic = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.bot_details_memory_topic_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        onEdit(entry, draft.trim(), draftTopic.trim().takeIf { it.isNotEmpty() })
                        editing = null
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun MemoryEntryRow(
    entry: BotMemoryEntry,
    onEdit: () -> Unit,
    onForget: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dshm.radius.sm))
            .background(Dshm.colors.surfaceMuted)
            .padding(Dshm.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.text,
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textPrimary,
            )
            val meta = listOfNotNull(
                entry.added,
                entry.source?.let { source ->
                    if (source == "memory panel") {
                        stringResource(R.string.bot_details_memory_by_you)
                    } else {
                        entry.by
                    }
                } ?: entry.by,
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.textTertiary,
                )
            }
        }
        TextButton(onClick = onEdit) {
            Text(stringResource(R.string.bot_details_memory_edit))
        }
        TextButton(onClick = onForget) {
            Text(stringResource(R.string.bot_settings_delete), color = Dshm.colors.danger)
        }
    }
}

/** 工作目录：已知目录 + 浏览 / 系统选择器 / 手动输入（对齐 DS Bot 的 `FolderPicker`）。 */
@Composable
internal fun FolderPickerDialog(
    value: String?,
    fallback: String?,
    folders: List<BotFolder>,
    picker: String?,
    onList: (path: String?, then: (BotFolderListing?) -> Unit) -> Unit,
    onPickNative: ((String?) -> Unit) -> Unit,
    onChoose: (String?) -> Unit,
    onDismiss: () -> Unit,
    onLoad: () -> Unit,
) {
    var mode by remember { mutableStateOf("list") }
    var listing by remember { mutableStateOf<BotFolderListing?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val errorText = stringResource(R.string.bot_details_folder_error)
    var typing by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) { onLoad() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Dshm.colors.surface,
        title = { Text(stringResource(R.string.bot_settings_workdir)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                when (mode) {
                    "browse" -> {
                        val here = listing
                        if (here != null) {
                            Text(
                                text = here.path,
                                style = Dshm.typography.rowTime,
                                color = Dshm.colors.textTertiary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val parent = here.crumbs.getOrNull(here.crumbs.size - 2)
                            if (parent != null) {
                                FolderRow(
                                    title = stringResource(R.string.bot_details_folder_up),
                                    subtitle = parent.second,
                                    onClick = {
                                        onList(parent.second) { next -> listing = next }
                                    },
                                )
                            }
                            here.entries.forEach { entry ->
                                FolderRow(
                                    title = entry.title,
                                    subtitle = entry.path,
                                    onClick = { onList(entry.path) { next -> listing = next } },
                                )
                            }
                            if (here.entries.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.bot_details_folder_empty),
                                    style = Dshm.typography.rowPreview,
                                    color = Dshm.colors.textTertiary,
                                )
                            }
                            if (here.truncated) {
                                Text(
                                    text = stringResource(R.string.bot_details_folder_truncated),
                                    style = Dshm.typography.rowTime,
                                    color = Dshm.colors.textTertiary,
                                )
                            }
                            Spacer(Modifier.size(Dshm.spacing.sm))
                            TextButton(onClick = { onChoose(here.path) }) {
                                Text(stringResource(R.string.bot_details_folder_use))
                            }
                        }
                        Spacer(Modifier.size(Dshm.spacing.xs))
                        TextButton(onClick = { mode = "list" }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    }

                    else -> {
                        val options = buildList {
                            if (value != null && folders.none { it.path == value }) {
                                add(BotFolder(value, value.substringAfterLast('/')))
                            }
                            addAll(folders)
                        }
                        FolderRow(
                            title = fallback ?: stringResource(R.string.bot_settings_workdir_default),
                            subtitle = null,
                            picked = value == null,
                            onClick = { onChoose(null) },
                        )
                        options.forEach { folder ->
                            FolderRow(
                                title = folder.title,
                                subtitle = folder.path,
                                picked = folder.path == value,
                                onClick = { onChoose(folder.path) },
                            )
                        }
                        if (picker == "native") {
                            FolderRow(
                                title = stringResource(R.string.bot_details_folder_choose),
                                subtitle = null,
                                onClick = { onPickNative { path -> if (path != null) onChoose(path) } },
                            )
                        }
                        if (picker == "browse" || picker == null) {
                            FolderRow(
                                title = stringResource(R.string.bot_details_folder_browse),
                                subtitle = null,
                                onClick = {
                                    problem = null
                                    onList(value) { next ->
                                        if (next == null) problem = errorText else {
                                            listing = next
                                            mode = "browse"
                                        }
                                    }
                                },
                            )
                        }
                        FolderRow(
                            title = stringResource(R.string.bot_details_folder_type),
                            subtitle = null,
                            onClick = { typing = true },
                        )
                    }
                }
                problem?.let { message ->
                    Text(
                        text = message,
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.danger,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )

    if (typing) {
        TextEditDialog(
            title = stringResource(R.string.bot_details_folder_path),
            initial = value.orEmpty(),
            placeholder = stringResource(R.string.bot_details_folder_path_hint),
            onDismiss = { typing = false },
            onSave = { path ->
                typing = false
                onChoose(path.trim().takeIf { it.isNotEmpty() })
            },
        )
    }
}

@Composable
private fun FolderRow(
    title: String,
    subtitle: String?,
    picked: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dshm.spacing.xxs)
            .clip(RoundedCornerShape(Dshm.radius.sm))
            .background(if (picked) Dshm.colors.accent.copy(alpha = 0.1f) else Dshm.colors.surfaceMuted)
            .clickable(onClick = onClick)
            .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = Dshm.typography.settingsRowTitle,
                color = if (picked) Dshm.colors.accent else Dshm.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.takeIf { it.isNotEmpty() }?.let { path ->
                Text(
                    text = path,
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            Modifier
                .size(Dshm.spacing.statusDot)
                .clip(RoundedCornerShape(Dshm.radius.pill))
                .background(if (picked) Dshm.colors.accent else Dshm.colors.divider),
        )
    }
}

@Composable
private fun ScopeChip(label: String, picked: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = Dshm.typography.rowPreview,
        color = if (picked) Dshm.colors.accent else Dshm.colors.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(Dshm.radius.pill))
            .background(if (picked) Dshm.colors.accent.copy(alpha = 0.12f) else Dshm.colors.surfaceMuted)
            .border(
                Dshm.spacing.hairline,
                if (picked) Dshm.colors.accent.copy(alpha = 0.35f) else Dshm.colors.divider,
                RoundedCornerShape(Dshm.radius.pill),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.xs),
    )
}

@Composable
private fun scopeTitle(scope: String, ownerName: String): String = when (scope) {
    "team" -> stringResource(R.string.bot_details_memory_scope_team)
    "group" -> stringResource(R.string.bot_details_memory_scope_group)
    else -> stringResource(R.string.bot_details_memory_scope_own, ownerName)
}

/**
 * 详情页那一行的文字。
 *
 * 照 DS Bot `runStatsOf` 的口径，但用户明确不要「多少轮 / 多少 tok/s」，
 * 只留缓存命中和上下文占用。
 */
@Composable
internal fun runStatsLabel(stats: BotRunStats?): String? {
    if (stats == null || stats.empty) return null
    val parts = mutableListOf<String>()
    stats.cacheHit?.let { hit ->
        parts += stringResource(R.string.run_stats_cache, percentText(hit.toDouble()))
    }
    stats.contextPercent?.let { percent ->
        parts += stringResource(R.string.run_stats_context, percent)
    }
    return parts.joinToString(" · ").takeIf { it.isNotEmpty() }
}

/** DS Bot `percentText`：不四舍五入到 100，最多三位小数。 */
private fun percentText(value: Double): String {
    val percent = value * 100
    if (percent >= 100.0) return "100"
    for (places in 0..2) {
        val text = String.format(java.util.Locale.ROOT, "%.${places}f", percent)
        if (text.toDouble() < 100.0) return text
    }
    return "99.99"
}
