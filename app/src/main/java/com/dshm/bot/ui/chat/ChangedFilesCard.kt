package com.dshm.bot.ui.chat

import android.content.ActivityNotFoundException
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.core.design.HapticLevel
import com.dshm.bot.core.design.rememberHaptics
import com.dshm.bot.data.remote.ChangedFile
import com.dshm.bot.data.remote.ChangesSummary
import com.dshm.bot.data.runtime.RuntimeEndpoint
import kotlinx.coroutines.launch

/**
 * 收起时最多列几个文件行（对齐官方 `dsh-client-ui-deliverables` 的 `ChangedFiles`：
 * 超过阈值才折起，全部展开入口在下方）。
 */
private const val CHANGED_FILES_SHOWN = 4 // 对齐官方 COLLAPSED_ROWS

/**
 * 一轮**代码产物卡**：这一轮改动了哪些文件（对齐 DSH 官方 `ChangedFiles`）。
 *
 * ```
 * ┌──────────────────────────────────────┐
 * │ [<>] 编辑了 3 个文件        +42 −7    │
 * │ ──────────────────────────────────── │
 * │ [▤] src/host/projection.js   +30 −2  │
 * │ [▤] src/host/feed.js          +12 −5  │
 * │ [▦] assets/logo.png        二进制      │
 * │ 全部 6 个文件 ⌄                       │
 * └──────────────────────────────────────┘
 * ```
 *
 * 数据来自 DSH `workspace/changes` 通告（行里只有 `turn`）+ 按 `(sessionId, seq)` 取的
 * `/api/changes.summary` 摘要。单文件只画标题行（官方做法）；多文件列出行。
 *
 * **点一行**：打开**改动详情**（diff 页，对齐 dsh-go 的 `ChangeDiffPage`；数据由客户端
 * 从事件日志自己算）。
 * **长按一行**：走现有产物打开链路（`ChatArtifactFiles` 取字节 + FileProvider/ACTION_VIEW）
 * —— 两个能力都留着。
 */
@Composable
fun ChangedFilesCard(
    summary: ChangesSummary,
    endpoint: RuntimeEndpoint?,
    sessionId: String,
    modifier: Modifier = Modifier,
    /** 打开改动详情（diff 页）；为 null 时点击直接打开文件。 */
    onShowDiff: ((ChangedFile) -> Unit)? = null,
) {
    if (summary.files.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notes = remember(summary) { mutableStateMapOf<String, String>() }
    var busyPath by remember(summary) { mutableStateOf<String?>(null) }
    var expanded by remember(summary) { mutableStateOf(false) }

    val single = summary.singleFile
    val collapsible = summary.files.size > CHANGED_FILES_SHOWN
    val shown = if (collapsible && !expanded) summary.files.take(CHANGED_FILES_SHOWN) else summary.files
    val shape = RoundedCornerShape(Dshm.radius.md)

    fun open(file: ChangedFile) {
        if (busyPath != null) return
        if (endpoint == null) {
            notes[file.path] = context.getString(R.string.chat_artifact_offline)
            return
        }
        notes.remove(file.path)
        busyPath = file.path
        scope.launch {
            try {
                // 打开的是 DSH 工作区里那份真文件：`path` 相对会话工作目录（引擎解析）。
                val artifact = ChatArtifact(path = file.path, description = null)
                val local = ChatArtifactFiles.fetch(context, endpoint, sessionId, artifact)
                ChatArtifactFiles.open(context, local, artifact, chooser = false)
            } catch (missing: ActivityNotFoundException) {
                notes[file.path] = context.getString(R.string.chat_artifact_no_app)
            } catch (error: Exception) {
                notes[file.path] = artifactErrorText(context, error)
            } finally {
                busyPath = null
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Dshm.colors.surface)
            .border(Dshm.spacing.hairline, Dshm.colors.divider, shape),
    ) {
        // 标题行：单文件可直接点（看 diff）；多文件是卡片的抬头（点文件行走行内动作）。
        val headerClickable = single != null
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (headerClickable) {
                        Modifier.clickable(enabled = busyPath == null) {
                            if (onShowDiff != null) onShowDiff(single) else open(single)
                        }
                    } else {
                        Modifier
                    },
                )
                .padding(
                    start = Dshm.spacing.md,
                    end = Dshm.spacing.md,
                    top = Dshm.spacing.md,
                    bottom = Dshm.spacing.sm,
                ),
        ) {
            Box(
                modifier = Modifier
                    .size(Dshm.spacing.iconButton - Dshm.spacing.xs)
                    .clip(RoundedCornerShape(Dshm.radius.sm))
                    .background(Dshm.colors.surfaceMuted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    // 单文件按路径取类型图标；多文件用「代码」图标（官方 `FileTypeIcon kind=code`）。
                    painter = painterResource(if (single != null) artifactIcon(single.path) else R.drawable.ic_lucide_code),
                    contentDescription = null,
                    tint = Dshm.colors.textSecondary,
                    modifier = Modifier.size(Dshm.spacing.icon),
                )
            }
            Spacer(Modifier.width(Dshm.spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (single != null) {
                        stringResource(R.string.chat_changes_edited_single, fileNameOf(single.path))
                    } else {
                        stringResource(R.string.chat_changes_edited, summary.total)
                    },
                    style = Dshm.typography.settingsRowTitle,
                    color = Dshm.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(Dshm.spacing.xxs))
                // 副行：binary/oversized 特判（官方逻辑），否则总增删行数。
                ChangedCounts(
                    added = summary.added,
                    deleted = summary.deleted,
                    binary = single?.binary == true,
                    oversized = single?.oversized == true,
                )
            }
            if (single != null && busyPath == single.path) {
                Box(Modifier.size(Dshm.spacing.iconButton), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = Dshm.colors.accent,
                        modifier = Modifier.size(Dshm.spacing.lg),
                    )
                }
            }
        }

        // 单文件官方只用标题；多文件列出文件行。文件行之间有细分隔线（官方 `list` 上边框）。
        if (single == null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(Dshm.spacing.hairline)
                    .background(Dshm.colors.divider),
            )
            shown.forEach { file ->
                ChangedFileRow(
                    file = file,
                    busy = busyPath == file.path,
                    note = notes[file.path],
                    onOpen = { if (onShowDiff != null) onShowDiff(file) else open(file) },
                    onLongPress = { open(file) },
                )
            }
            if (collapsible) {
                Spacer(Modifier.size(Dshm.spacing.xxs))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(vertical = Dshm.spacing.sm),
                ) {
                    Text(
                        text = if (expanded) {
                            stringResource(R.string.chat_collapse)
                        } else {
                            stringResource(R.string.chat_artifacts_all, summary.files.size)
                        },
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.accent,
                    )
                    Spacer(Modifier.width(Dshm.spacing.xs))
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_chevron_down),
                        contentDescription = null,
                        tint = Dshm.colors.accent,
                        modifier = Modifier.size(Dshm.spacing.lg),
                    )
                }
            } else {
                Spacer(Modifier.size(Dshm.spacing.sm))
            }
        } else {
            Spacer(Modifier.size(Dshm.spacing.sm))
        }
    }
}

/** 一个改动文件一行：类型图标 + 展示名 + 增删行数（binary/oversized 特判）。 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun ChangedFileRow(
    file: ChangedFile,
    busy: Boolean,
    note: String?,
    onOpen: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    // 触感：打开这份文件的 diff 是动作，轻震（对齐 dsh-Go diff_page 的 lightImpact）。
    val haptics = rememberHaptics()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = !busy,
                onClick = {
                    haptics(HapticLevel.LIGHT)
                    onOpen()
                },
                onLongClick = onLongPress,
            )
            .padding(start = Dshm.spacing.md, end = Dshm.spacing.md, top = Dshm.spacing.sm, bottom = Dshm.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(Dshm.spacing.iconButton - Dshm.spacing.sm)
                .clip(RoundedCornerShape(Dshm.radius.xs))
                .background(Dshm.colors.surfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(artifactIcon(file.path)),
                contentDescription = null,
                tint = Dshm.colors.textSecondary,
                modifier = Modifier.size(Dshm.spacing.icon),
            )
        }
        Spacer(Modifier.width(Dshm.spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = file.display,
                // 路径用等宽（官方 `row path` 也是）。
                style = Dshm.typography.rowPreview.copy(fontFamily = FontFamily.Monospace),
                color = Dshm.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (note != null) {
                Spacer(Modifier.size(Dshm.spacing.xxs))
                Text(
                    text = note,
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.danger,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(Dshm.spacing.md))
        when {
            busy -> CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = Dshm.colors.accent,
                modifier = Modifier.size(Dshm.spacing.lg),
            )
            file.binary -> ChangedTag(stringResource(R.string.chat_changes_binary))
            file.oversized -> ChangedTag(stringResource(R.string.chat_changes_oversized))
            else -> ChangedCounts(added = file.added, deleted = file.deleted, binary = false, oversized = false)
        }
    }
}

/** `+N −M`：新增绿色、删除红色（官方 `Counts`，等宽数字）。 */
@Composable
private fun ChangedCounts(added: Int, deleted: Int, binary: Boolean, oversized: Boolean) {
    when {
        binary -> ChangedTag(stringResource(R.string.chat_changes_binary))
        oversized -> ChangedTag(stringResource(R.string.chat_changes_oversized))
        else -> Row(horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm)) {
            Text(
                text = stringResource(R.string.chat_changes_added, added),
                style = Dshm.typography.rowTime.copy(fontFamily = FontFamily.Monospace),
                color = Dshm.colors.success,
            )
            Text(
                text = stringResource(R.string.chat_changes_deleted, deleted),
                style = Dshm.typography.rowTime.copy(fontFamily = FontFamily.Monospace),
                color = Dshm.colors.danger,
            )
        }
    }
}

@Composable
private fun ChangedTag(text: String) {
    Text(
        text = text,
        style = Dshm.typography.rowTime,
        color = Dshm.colors.textTertiary,
        fontWeight = FontWeight.Normal,
    )
}

/** 路径的最后一段（官方 `basename`）。 */
private fun fileNameOf(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
