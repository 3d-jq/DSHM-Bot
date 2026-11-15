package com.dshm.bot.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.ui.components.FloatingIconButton
import com.dshm.bot.ui.settings.SettingsPage

/**
 * 四期 4b-2：代码产物卡的**改动详情页**（对齐 dsh-go 的 `ChangeDiffPage`）。
 *
 * 顶栏**直接用项目的统一脚手架** [SettingsPage]（左返回钮 + 居中标题 + 右动作区，
 * 与设置里那些界面同一套），不另写一个。
 *
 * - 副标题：文件数 + 增删行数；
 * - 右上动作：换行开关、复制补丁；
 * - 内容：第一行是**文件胶囊**（点它切文件），下面是这个文件的**带行号槽统一 diff**。
 *
 * 数据来自客户端自己的事件日志（[changesFromEvents]），不向宿主要 diff。
 */
@Composable
fun ChangeDiffScreen(
    changes: List<FileChange>,
    modifier: Modifier = Modifier,
    initialIndex: Int = 0,
    onBack: () -> Unit = {},
    onCopyPatch: (String) -> Unit = {},
) {
    if (changes.isEmpty()) {
        SettingsPage(
            title = "改动",
            onBack = onBack,
        ) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Dshm.spacing.xxl),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "没有可显示的改动",
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.textTertiary,
                    )
                }
            }
        }
        return
    }
    var index by rememberSaveable { mutableIntStateOf(initialIndex.coerceIn(0, changes.lastIndex)) }
    var wrap by rememberSaveable { mutableStateOf(true) }
    val added = changes.sumOf { it.added }
    val removed = changes.sumOf { it.removed }

    SettingsPage(
        title = "改动",
        onBack = onBack,
        // 顶栏**不做副标题**（全项目统一）；动作是**悬浮圆钮**（和返回键同一套）。
        actions = {
            FloatingIconButton(
                icon = R.drawable.ic_lucide_wrap_text,
                contentDescription = if (wrap) "不换行" else "自动换行",
                onClick = { wrap = !wrap },
            )
            FloatingIconButton(
                icon = R.drawable.ic_lucide_copy,
                contentDescription = stringResource(R.string.chat_menu_copy),
                onClick = { onCopyPatch(patchOf(changes.getOrNull(index))) },
            )
        },
    ) {
        // 文件数与增删行数（顶栏没有副标题，放在内容区第一行，靠左）。
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Dshm.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${changes.size} 个文件 · +$added −$removed",
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (wrap) "自动换行" else "不换行",
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.textTertiary,
                )
            }
        }
        // 文件胶囊：点它切文件（dsh-go 同款位置）。
        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.xs),
                modifier = Modifier.fillMaxWidth().padding(vertical = Dshm.spacing.xs),
            ) {
                items(changes.size) { position ->
                    val selected = position == index
                    Text(
                        text = changes[position].path.substringAfterLast('/'),
                        style = Dshm.typography.rowPreview,
                        color = if (selected) Dshm.colors.onAccent else Dshm.colors.textSecondary,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Dshm.radius.pill))
                            .background(if (selected) Dshm.colors.accent else Dshm.colors.surfaceMuted)
                            .clickable { index = position }
                            .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.xs),
                    )
                }
            }
        }
        val change = changes.getOrNull(index)
        if (change == null) return@SettingsPage
        items(change.hunks.size) { hunkIndex ->
            val hunk = change.hunks[hunkIndex]
            Column(modifier = Modifier.fillMaxWidth()) {
                // hunk 头：起止行号（算不出就只说「改动」）。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Dshm.colors.surfaceMuted)
                        .padding(horizontal = Dshm.spacing.sm, vertical = Dshm.spacing.xs),
                ) {
                    Text(
                        text = hunkHeader(hunk),
                        style = Dshm.typography.rowTime,
                        color = Dshm.colors.textTertiary,
                    )
                }
                DiffLines(
                    lines = hunk.lines,
                    oldStart = hunk.oldStart,
                    newStart = hunk.newStart,
                    wrap = wrap,
                )
            }
        }
    }
}

/** 一处改动的行（含左右行号槽）。 */
@Composable
private fun DiffLines(
    lines: List<DiffLine>,
    oldStart: Int?,
    newStart: Int?,
    wrap: Boolean,
) {
    // 等宽：diff 的行号与代码都要等宽。
    val mono = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
    var oldLine = oldStart
    var newLine = newStart
    Column(modifier = Modifier.fillMaxWidth()) {
        for (line in lines) {
            val oldNumber = if (line.kind == DiffLineKind.ADD) null else oldLine?.also { oldLine = it + 1 }
            val newNumber = if (line.kind == DiffLineKind.DELETE) null else newLine?.also { newLine = it + 1 }
            Row(modifier = Modifier.fillMaxWidth()) {
                // 行号**靠左**：旧行号没有时不占那一列（纯新增的文件数字才不会被顶到中间）。
                if (oldNumber != null) {
                    Text(
                        text = oldNumber.toString(),
                        style = mono,
                        color = Dshm.colors.textTertiary,
                        modifier = Modifier.width(34.dp).padding(end = Dshm.spacing.xs),
                    )
                }
                Text(
                    text = newNumber?.toString() ?: "",
                    style = mono,
                    color = Dshm.colors.textTertiary,
                    modifier = Modifier.width(34.dp).padding(end = Dshm.spacing.sm),
                )
                Text(
                    text = line.text,
                    style = mono,
                    color = when (line.kind) {
                        DiffLineKind.ADD -> Dshm.colors.success
                        DiffLineKind.DELETE -> Dshm.colors.danger
                        DiffLineKind.CONTEXT -> Dshm.colors.textPrimary
                    },
                    softWrap = wrap,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** hunk 头：能算出起止行就写行号，否则只说「改动」。 */
private fun hunkHeader(hunk: DiffHunk): String {
    val oldStart = hunk.oldStart
    val newStart = hunk.newStart
    if (oldStart == null && newStart == null) return "改动"
    val oldCount = hunk.lines.count { it.kind != DiffLineKind.ADD }
    val newCount = hunk.lines.count { it.kind != DiffLineKind.DELETE }
    val oldPart = oldStart?.let { "旧 $it" }
    val newPart = newStart?.let { "新 $it" }
    return listOfNotNull(oldPart, newPart).joinToString(" · ")
        .let { "$it（−$oldCount +$newCount）" }
}

/** 统一 diff 文本（复制补丁用）。 */
internal fun patchOf(change: FileChange?): String {
    if (change == null) return ""
    val out = StringBuilder()
    out.append("--- a/").append(change.path).append('\n')
    out.append("+++ b/").append(change.path).append('\n')
    for (hunk in change.hunks) {
        out.append("@@ -").append(hunk.oldStart ?: 0).append(",0 +").append(hunk.newStart ?: 0).append(",0 @@\n")
        for (line in hunk.lines) out.append(line.text).append('\n')
    }
    return out.toString()
}
