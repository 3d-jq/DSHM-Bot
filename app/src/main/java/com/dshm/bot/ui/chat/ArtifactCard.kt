package com.dshm.bot.ui.chat

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.runtime.RuntimeEndpoint
import com.dshm.bot.ui.settings.SettingsRow
import com.dshm.bot.ui.settings.SettingsRowDivider
import com.dshm.bot.ui.settings.SettingsSection
import kotlinx.coroutines.launch

/** 收起时最多列几个（官方网页版同为 4）。 */
private const val ARTIFACTS_SHOWN = 4

/**
 * 一轮结束时的**产物卡**：这一轮 Bot 用 `present` 交付给用户的文件。
 *
 * ```
 * ┌──────────────────────────────────────┐
 * │ ▣ 交付了 3 个文件                     │
 * │ [▦] 季度销售报告.pptx              ⋯ │
 * │     季度汇总                          │
 * │ [▤] trend.png                      ⋯ │
 * │     图片 · PNG                        │
 * │ 全部 6 个文件 ⌄                       │
 * └──────────────────────────────────────┘
 * ```
 *
 * 点一行：从本机引擎取字节，交给手机上能打开它的应用；⋯ 是完整动作清单。
 */
@Composable
fun ArtifactCard(
    artifacts: List<ChatArtifact>,
    endpoint: RuntimeEndpoint?,
    sessionId: String,
    modifier: Modifier = Modifier,
) {
    if (artifacts.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notes = remember(artifacts) { mutableStateMapOf<String, ArtifactNote>() }
    var busyPath by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<ChatArtifact?>(null) }
    var expanded by remember { mutableStateOf(false) }

    val collapsible = artifacts.size > ARTIFACTS_SHOWN
    val shown = if (collapsible) artifacts.take(ARTIFACTS_SHOWN) else artifacts
    val more = if (collapsible) artifacts.drop(ARTIFACTS_SHOWN) else emptyList()
    val shape = RoundedCornerShape(Dshm.radius.md)

    fun report(artifact: ChatArtifact, text: String, failed: Boolean) {
        notes[artifact.path] = ArtifactNote(text, failed)
    }

    fun run(artifact: ChatArtifact, block: suspend (java.io.File) -> Unit) {
        if (busyPath != null) return
        if (endpoint == null) {
            report(artifact, context.getString(R.string.chat_artifact_offline), failed = true)
            return
        }
        notes.remove(artifact.path)
        busyPath = artifact.path
        scope.launch {
            try {
                val file = ChatArtifactFiles.fetch(context, endpoint, sessionId, artifact)
                block(file)
            } catch (missing: ActivityNotFoundException) {
                report(artifact, context.getString(R.string.chat_artifact_no_app), failed = true)
            } catch (error: Exception) {
                report(artifact, artifactErrorText(context, error), failed = true)
            } finally {
                busyPath = null
            }
        }
    }

    fun open(artifact: ChatArtifact, chooser: Boolean) {
        run(artifact) { file -> ChatArtifactFiles.open(context, file, artifact, chooser) }
    }

    fun share(artifact: ChatArtifact) {
        run(artifact) { file -> ChatArtifactFiles.share(context, file, artifact) }
    }

    fun save(artifact: ChatArtifact) {
        run(artifact) { file ->
            val path = ChatArtifactFiles.saveToDownloads(context, file, artifact)
            report(
                artifact,
                if (path == null) {
                    context.getString(R.string.chat_artifact_saved)
                } else {
                    context.getString(R.string.chat_artifact_saved_path, path)
                },
                failed = false,
            )
        }
    }

    fun tile(artifact: ChatArtifact): @Composable () -> Unit = {
        ArtifactTile(
            artifact = artifact,
            busy = busyPath == artifact.path,
            note = notes[artifact.path],
            onOpen = { open(artifact, chooser = false) },
            onMore = { menuFor = artifact },
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Dshm.colors.surface)
            .border(Dshm.spacing.hairline, Dshm.colors.divider, shape),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                start = Dshm.spacing.md,
                end = Dshm.spacing.md,
                top = Dshm.spacing.md,
                bottom = Dshm.spacing.sm,
            ),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_package),
                contentDescription = null,
                tint = Dshm.colors.textSecondary,
                modifier = Modifier.size(Dshm.spacing.settingsRowIcon),
            )
            Spacer(Modifier.width(Dshm.spacing.sm))
            Text(
                text = stringResource(R.string.chat_artifacts_delivered, artifacts.size),
                style = Dshm.typography.settingsRowTitle,
                color = Dshm.colors.textPrimary,
            )
        }
        shown.forEach { artifact -> tile(artifact)() }
        if (collapsible) {
            if (expanded) more.forEach { artifact -> tile(artifact)() }
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
                        stringResource(R.string.chat_artifacts_all, artifacts.size)
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
    }

    menuFor?.let { artifact ->
        ArtifactMenu(
            artifact = artifact,
            canFetch = endpoint != null,
            onDismiss = { menuFor = null },
            onOpen = {
                menuFor = null
                open(artifact, chooser = false)
            },
            onOpenWith = {
                menuFor = null
                open(artifact, chooser = true)
            },
            onShare = {
                menuFor = null
                share(artifact)
            },
            onSave = {
                menuFor = null
                save(artifact)
            },
            onCopyPath = {
                ChatArtifactFiles.copyPath(context, artifact)
                Toast.makeText(context, R.string.chat_copied, Toast.LENGTH_SHORT).show()
            },
        )
    }
}

/** 一个文件一行：类型图标、文件名、说明（或失败提示）、⋯ 按钮。 */
@Composable
private fun ArtifactTile(
    artifact: ChatArtifact,
    busy: Boolean,
    note: ArtifactNote?,
    onOpen: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onOpen)
            .padding(start = Dshm.spacing.md, end = Dshm.spacing.xs, top = Dshm.spacing.sm, bottom = Dshm.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(Dshm.spacing.iconButton - Dshm.spacing.xs)
                .clip(RoundedCornerShape(Dshm.radius.sm))
                .background(Dshm.colors.surfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(artifactIcon(artifact)),
                contentDescription = null,
                tint = artifactTint(artifact),
                modifier = Modifier.size(Dshm.spacing.icon),
            )
        }
        Spacer(Modifier.width(Dshm.spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = artifact.name,
                style = Dshm.typography.settingsRowTitle,
                color = Dshm.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(Dshm.spacing.xxs))
            Text(
                text = when {
                    busy -> stringResource(R.string.chat_artifact_fetching)
                    note != null -> note.text
                    artifact.description != null -> artifact.description
                    else -> artifact.typeLabel
                },
                style = Dshm.typography.rowPreview,
                color = when {
                    note?.failed == true -> Dshm.colors.danger
                    note != null -> Dshm.colors.success
                    else -> Dshm.colors.textSecondary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Dshm.spacing.xs))
        if (busy) {
            Box(
                modifier = Modifier.size(Dshm.spacing.iconButton),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = Dshm.colors.accent,
                    modifier = Modifier.size(Dshm.spacing.lg),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(Dshm.spacing.iconButton)
                    .clip(RoundedCornerShape(Dshm.radius.sm))
                    .clickable(onClick = onMore),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_ellipsis),
                    contentDescription = stringResource(R.string.chat_artifact_more),
                    tint = Dshm.colors.textSecondary,
                    modifier = Modifier.size(Dshm.spacing.icon),
                )
            }
        }
    }
}

/** 文件类型色：跟常见办公软件的习惯走（PDF 红、表格绿、演示橙、文档蓝）。 */
@Composable
private fun artifactTint(artifact: ChatArtifact): Color = when (artifact.extension) {
    "pdf" -> Dshm.colors.danger
    "xls", "xlsx", "csv", "ods" -> Dshm.colors.success
    "ppt", "pptx", "odp", "key" -> Dshm.colors.warning
    "doc", "docx", "odt", "rtf", "txt", "md", "log" -> Dshm.colors.accent
    "png", "jpg", "jpeg", "gif", "webp", "svg" -> Dshm.colors.accent
    else -> Dshm.colors.textSecondary
}

private fun artifactIcon(artifact: ChatArtifact): Int = when (artifact.extension) {
    "png", "jpg", "jpeg", "gif", "webp", "svg" -> R.drawable.ic_lucide_image
    "zip", "tar", "gz", "7z", "rar" -> R.drawable.ic_lucide_package
    else -> R.drawable.ic_lucide_file_text
}

private data class ArtifactNote(val text: String, val failed: Boolean)

/** 一个产物的动作清单：打开 / 用其他应用打开 / 分享 / 保存到下载 / 复制路径。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtifactMenu(
    artifact: ChatArtifact,
    canFetch: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onOpenWith: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onCopyPath: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Dshm.colors.pageBackground,
        shape = RoundedCornerShape(topStart = Dshm.radius.xl, topEnd = Dshm.radius.xl),
        scrimColor = Dshm.colors.scrim,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Dshm.spacing.lg)
                .padding(bottom = Dshm.spacing.xl),
        ) {
            Text(text = artifact.name, style = Dshm.typography.sheetTitle, color = Dshm.colors.textPrimary)
            artifact.description?.let { description ->
                Spacer(Modifier.size(Dshm.spacing.xxs))
                Text(
                    text = description,
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(Dshm.spacing.xs))
            Text(
                text = artifact.path,
                style = Dshm.typography.rowTime.copy(fontFamily = FontFamily.Monospace),
                color = Dshm.colors.textTertiary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(Dshm.spacing.lg))
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.chat_artifact_open),
                    icon = R.drawable.ic_lucide_external_link,
                    enabled = canFetch,
                    onClick = onOpen,
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.chat_artifact_open_with),
                    icon = R.drawable.ic_lucide_globe,
                    enabled = canFetch,
                    onClick = onOpenWith,
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.chat_artifact_share),
                    icon = R.drawable.ic_lucide_share_2,
                    enabled = canFetch,
                    onClick = onShare,
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.chat_artifact_save),
                    icon = R.drawable.ic_lucide_download,
                    enabled = canFetch,
                    onClick = onSave,
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.chat_artifact_copy_path),
                    icon = R.drawable.ic_lucide_copy,
                    onClick = onCopyPath,
                )
            }
        }
    }
}

/** 已知错误码给白话；其余显示引擎给的原话。 */
private fun artifactErrorText(context: android.content.Context, error: Throwable): String =
    when (artifactErrorCode(error)) {
        "workspace-file/not-found" -> context.getString(R.string.chat_artifact_not_found)
        "workspace-file/too-large" -> context.getString(R.string.chat_artifact_too_large)
        "workspace-file/not-allowed" -> context.getString(R.string.chat_artifact_not_allowed)
        else -> context.getString(
            R.string.chat_artifact_failed,
            error.message ?: error.javaClass.simpleName,
        )
    }
