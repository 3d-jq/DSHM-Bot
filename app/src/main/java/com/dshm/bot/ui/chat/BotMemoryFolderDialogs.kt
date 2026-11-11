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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.verticalScroll
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.ui.settings.SettingsAnchoredMenu
import com.dshm.bot.ui.settings.SettingsInput
import com.dshm.bot.ui.settings.SettingsMenuItem
import com.dshm.bot.ui.settings.SettingsSheet

/**
 * 记忆面板，对齐 DS Bot 的 `MemoryDialog`：
 * - 两个页面：默认「记忆摘要」，「全部条目」从右上角 ··· 进，条目页左上角返回。
 * - 摘要页：模型写的摘要按 `#` 标题 / 段落渲染，生成中骨架、过期淡显、失败可重试。
 * - 全部条目：own / team（群→group）上下两段，各段带「已用 / 上限」字符数；
 *   一条记录右侧只有垃圾桶（二次确认删除），没有编辑。
 * - 底部是 `memory-ask` 的胶囊输入（问我 / 直接更新），没有直填增删表单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryPanelDialog(
    ownerName: String,
    group: Boolean,
    entries: List<BotMemoryEntry>,
    sizes: Map<String, String>,
    summary: String?,
    summaryAt: Long?,
    summaryError: String?,
    memoryLimit: Int?,
    summarizing: Boolean,
    fresh: Boolean,
    onRefresh: (regenerate: Boolean) -> Unit,
    onStopPolling: () -> Unit,
    onAsk: (text: String, onReply: (String?) -> Unit) -> Unit,
    onForget: (BotMemoryEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var page by remember { mutableStateOf(0) } // 0 = 摘要, 1 = 全部条目
    var menuOpen by remember { mutableStateOf(false) }
    val count = entries.size

    // 打开先拉一次，并让 DSH 去写摘要；关掉面板就停止轮询。
    LaunchedEffect(Unit) { onRefresh(false) }
    val close = {
        onStopPolling()
        onDismiss()
    }

    val head: @Composable () -> Unit = {
        MemoryHead(
            page = page,
            ownerName = ownerName,
            count = count,
            hasSummary = summary != null,
            summarizing = summarizing,
            summaryAt = summaryAt,
            menuOpen = menuOpen,
            onMenuOpen = { menuOpen = it },
            onBack = { page = 0 },
            onOpenEntries = {
                menuOpen = false
                page = 1
            },
            onRegenerate = {
                menuOpen = false
                page = 0
                onRefresh(true)
            },
        )
    }

    // 名字胶囊弹的底部面板：高度固定、拉不高，只预览（能删）。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.86f
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = sheetState,
        containerColor = Dshm.colors.surface,
        contentColor = Dshm.colors.textPrimary,
        scrimColor = Dshm.colors.scrim,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .navigationBarsPadding(),
        ) {
            head()
            HorizontalDivider(color = Dshm.colors.divider)
            MemoryBody(
                modifier = Modifier.fillMaxWidth().weight(1f),
                page = page,
                group = group,
                ownerName = ownerName,
                entries = entries,
                sizes = sizes,
                memoryLimit = memoryLimit,
                summary = summary,
                summaryAt = summaryAt,
                summaryError = summaryError,
                summarizing = summarizing,
                fresh = fresh,
                onRegenerate = { onRefresh(true) },
                onForget = onForget,
            )
        }
    }
}

/** 面板头：返回（条目页）/ 标题 / 状态 / ··· 菜单 / 关闭。 */
@Composable
private fun MemoryHead(
    page: Int,
    ownerName: String,
    count: Int,
    hasSummary: Boolean,
    summarizing: Boolean,
    summaryAt: Long?,
    menuOpen: Boolean,
    onMenuOpen: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenEntries: () -> Unit,
    onRegenerate: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dshm.spacing.sm)
            .height(Dshm.spacing.topBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (page == 1) {
            IconButton(onClick = onBack, modifier = Modifier.size(Dshm.spacing.iconButton)) {
                Icon(
                    painterResource(R.drawable.ic_lucide_arrow_left),
                    contentDescription = stringResource(R.string.memory_back),
                )
            }
        }
        Text(
            text = if (page == 0) {
                stringResource(R.string.memory_title_summary)
            } else {
                stringResource(R.string.memory_title_entries)
            },
            style = Dshm.typography.sheetTitle,
            color = Dshm.colors.textPrimary,
        )
        Spacer(Modifier.width(Dshm.spacing.sm))
        Text(
            text = when {
                page == 1 -> statusFromCount(count)
                summarizing && hasSummary -> stringResource(R.string.memory_updating)
                summaryAt != null -> updatedLabel(summaryAt)
                else -> ownerName
            },
            style = Dshm.typography.rowPreview,
            color = Dshm.colors.textTertiary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box {
            IconButton(onClick = { onMenuOpen(true) }, modifier = Modifier.size(Dshm.spacing.iconButton)) {
                Icon(
                    painterResource(R.drawable.ic_lucide_ellipsis),
                    contentDescription = stringResource(R.string.memory_menu),
                )
            }
            // 用我们自己的下拉面板（设置卡片的颜色和圆角），不是 M3 默认样式。
            SettingsAnchoredMenu(
                expanded = menuOpen,
                onDismiss = { onMenuOpen(false) },
                anchorHeightPx = with(LocalDensity.current) { Dshm.spacing.iconButton.roundToPx() },
            ) {
                SettingsMenuItem(
                    text = stringResource(R.string.memory_title_entries),
                    selected = page == 1,
                    onClick = onOpenEntries,
                )
                if (count > 0 && !summarizing) {
                    SettingsMenuItem(
                        text = stringResource(R.string.memory_regenerate),
                        selected = false,
                        onClick = onRegenerate,
                    )
                }
            }
        }
    }
}

/** 可滚动的面板主体：摘要页 / 全部条目页。 */
@Composable
private fun MemoryBody(
    modifier: Modifier,
    page: Int,
    group: Boolean,
    ownerName: String,
    entries: List<BotMemoryEntry>,
    sizes: Map<String, String>,
    memoryLimit: Int?,
    summary: String?,
    summaryAt: Long?,
    summaryError: String?,
    summarizing: Boolean,
    fresh: Boolean,
    onRegenerate: () -> Unit,
    onForget: (BotMemoryEntry) -> Unit,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Dshm.spacing.lg),
    ) {
        if (page == 1) {
            MemoryEntriesBlock(
                group = group,
                ownerName = ownerName,
                entries = entries,
                sizes = sizes,
                memoryLimit = memoryLimit,
                onForget = onForget,
            )
        } else {
            MemorySummaryBlock(
                group = group,
                ownerName = ownerName,
                entries = entries,
                summary = summary,
                summaryAt = summaryAt,
                summaryError = summaryError,
                summarizing = summarizing,
                fresh = fresh,
                onRegenerate = onRegenerate,
            )
        }
    }
}

/** 底部面板的抓手。 */
@Composable
private fun SheetHandle() {
    Box(Modifier.fillMaxWidth().padding(top = Dshm.spacing.md), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .width(Dshm.spacing.xxxl)
                .height(Dshm.spacing.xs)
                .clip(CircleShape)
                .background(Dshm.colors.divider),
        )
    }
}

/** 底部「问我或直接更新」胶囊（DS Bot `bt-mem-ask`），只在 Bot 设置的整屏界面里出现。 */
@Composable
internal fun MemoryAskBar(onAsk: (text: String, onReply: (String?) -> Unit) -> Unit) {
    var ask by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
    ) {
        reply?.let { text ->
            Text(
                text = text,
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
                modifier = Modifier.padding(bottom = Dshm.spacing.sm),
            )
        }
        // 和对话输入框同一套外壳（圆角 / 白底 / 描边 / 投影）。
        ComposerFieldSurface {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                BasicTextField(
                    value = ask,
                    onValueChange = {
                        ask = it
                        reply = null
                    },
                    textStyle = Dshm.typography.composerText,
                    cursorBrush = SolidColor(Dshm.colors.accent),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = Dshm.spacing.composerTextPad),
                    decorationBox = { inner ->
                        if (ask.isEmpty()) {
                            Text(
                                text = stringResource(R.string.memory_ask_placeholder),
                                style = Dshm.typography.composerText.copy(color = Dshm.colors.textTertiary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    },
                )
                Spacer(Modifier.width(Dshm.spacing.composerKeyGap))
                SendKey(
                    enabled = ask.isNotBlank() && !busy,
                    onSend = {
                        busy = true
                        onAsk(ask.trim()) { answer ->
                            busy = false
                            if (answer != null) reply = answer
                            ask = ""
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun statusFromCount(count: Int): String =
    when (count) {
        0 -> ""
        1 -> stringResource(R.string.memory_one_entry)
        else -> stringResource(R.string.bot_settings_memory_count, count)
    }


/** 摘要页（DS Bot `Summary`）：空态 / 失败+重试 / 生成中骨架 / 摘要正文或旧摘要淡显。 */
@Composable
internal fun MemorySummaryBlock(
    group: Boolean,
    ownerName: String,
    entries: List<BotMemoryEntry>,
    summary: String?,
    summaryAt: Long?,
    summaryError: String?,
    summarizing: Boolean,
    fresh: Boolean,
    onRegenerate: () -> Unit,
) {
    if (entries.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .size(Dshm.spacing.xxxl)
                    .clip(CircleShape)
                    .background(Dshm.colors.surfaceMuted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_dsbot_memory),
                    contentDescription = null,
                    tint = Dshm.colors.textSecondary,
                    modifier = Modifier.size(Dshm.spacing.lg),
                )
            }
            Text(
                text = if (group) {
                    stringResource(R.string.memory_empty_group_title)
                } else {
                    stringResource(R.string.memory_empty_bot_title, ownerName)
                },
                style = Dshm.typography.emptyTitle,
                color = Dshm.colors.textPrimary,
            )
            Text(
                text = if (group) {
                    stringResource(R.string.memory_empty_group_text)
                } else {
                    stringResource(R.string.memory_empty_bot_text, ownerName)
                },
                style = Dshm.typography.emptyHint,
                color = Dshm.colors.textSecondary,
            )
        }
        return
    }

    val failed = summaryError != null && !summarizing
    if (failed) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dshm.radius.md))
                .background(Dshm.colors.surfaceMuted)
                .padding(Dshm.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (summary != null) {
                    stringResource(R.string.memory_stale_reason, summaryError)
                } else {
                    stringResource(R.string.memory_failed_reason, summaryError)
                },
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRegenerate) { Text(stringResource(R.string.memory_retry)) }
        }
    }

    if (summary == null) {
        if (!failed) {
            Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.lg)) {
                repeat(2) { block ->
                    Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm)) {
                        Box(
                            Modifier
                                .width(if (block == 0) Dshm.spacing.xxxl else Dshm.spacing.xxl)
                                .height(Dshm.spacing.md)
                                .clip(RoundedCornerShape(Dshm.radius.xs))
                                .background(Dshm.colors.surfaceMuted),
                        )
                        Box(Modifier.height(Dshm.spacing.xs).fillMaxWidth().clip(RoundedCornerShape(Dshm.radius.xs)).background(Dshm.colors.surfaceMuted))
                        Box(Modifier.height(Dshm.spacing.xs).fillMaxWidth().clip(RoundedCornerShape(Dshm.radius.xs)).background(Dshm.colors.surfaceMuted))
                        Box(Modifier.height(Dshm.spacing.xs).fillMaxWidth().clip(RoundedCornerShape(Dshm.radius.xs)).background(Dshm.colors.surfaceMuted))
                        Box(Modifier.height(Dshm.spacing.xs).fillMaxWidth().clip(RoundedCornerShape(Dshm.radius.xs)).background(Dshm.colors.surfaceMuted))
                    }
                }
            }
            Spacer(Modifier.height(Dshm.spacing.sm))
            Text(
                text = stringResource(R.string.memory_writing),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textTertiary,
            )
        }
        return
    }

    // 摘要正文：`# 标题` / `## 标题` 用 section 标题渲染，段落用正文。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (!fresh) Modifier.graphicsLayer { alpha = 0.55f } else Modifier),
        verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xs),
    ) {
        summaryBlocks(summary).forEach { (kind, text) ->
            if (kind == "h") {
                Text(
                    text = text,
                    style = Dshm.typography.screenTitle,
                    color = Dshm.colors.textPrimary,
                )
            } else {
                Text(
                    text = text,
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
            }
        }
    }
}

/** 全部条目页（DS Bot `Entries`）：own/team（群→group）上下堆叠，带字符用量。 */
@Composable
internal fun MemoryEntriesBlock(
    group: Boolean,
    ownerName: String,
    entries: List<BotMemoryEntry>,
    sizes: Map<String, String>,
    memoryLimit: Int?,
    onForget: (BotMemoryEntry) -> Unit,
) {
    val sections = if (group) {
        listOf(Triple("group", stringResource(R.string.memory_section_group), stringResource(R.string.memory_section_group_hint)))
    } else {
        listOf(
            Triple("own", stringResource(R.string.memory_section_own, ownerName), null),
            Triple("team", stringResource(R.string.memory_section_team), stringResource(R.string.memory_section_team_hint)),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.lg)) {
        sections.forEach { (scope, title, hint) ->
            val sectionEntries = entries.filter { it.scope == scope }
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = title,
                        style = Dshm.typography.rowTitle,
                        color = Dshm.colors.textPrimary,
                    )
                    hint?.let {
                        Spacer(Modifier.width(Dshm.spacing.xs))
                        Text(
                            text = it,
                            style = Dshm.typography.rowTime,
                            color = Dshm.colors.textTertiary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    memoryLimit?.let { limit ->
                        val used = sizes[scope]?.toIntOrNull() ?: 0
                        Text(
                            text = stringResource(R.string.memory_size, used.toString(), limit.toString()),
                            style = Dshm.typography.rowTime,
                            color = Dshm.colors.textTertiary,
                        )
                    }
                }
                Spacer(Modifier.height(Dshm.spacing.xs))
                if (sectionEntries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.bot_settings_memory_empty),
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.textTertiary,
                    )
                } else {
                    // 按 `scope:topic` 分组，topic 作小节标题（DS Bot `groupEntries`）。
                    sectionEntries.groupBy { it.topic.orEmpty() }.forEach { (topic, groupEntries) ->
                        if (topic.isNotEmpty()) {
                            Text(
                                text = topic,
                                style = Dshm.typography.settingsSectionTitle,
                                color = Dshm.colors.textSecondary,
                            )
                        }
                        Column {
                            groupEntries.forEach { entry ->
                                EntryRow(entry = entry, onForget = { onForget(entry) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 一条记录（DS Bot `EntryRow`）：正文 + 元信息，右侧垃圾桶，二次确认删除。 */
@Composable
private fun EntryRow(entry: BotMemoryEntry, onForget: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(confirming) {
        if (confirming) {
            kotlinx.coroutines.delay(3_000)
            confirming = false
        }
    }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dshm.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xxs),
        ) {
            Text(
                text = entry.text,
                style = Dshm.typography.rowPreview,
                color = if (busy) Dshm.colors.textTertiary else Dshm.colors.textPrimary,
            )
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.textTertiary,
                )
            }
        }
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .then(if (confirming) Modifier.background(Dshm.colors.danger.copy(alpha = 0.1f)) else Modifier)
                .clickable {
                    if (!confirming) confirming = true
                    else {
                        busy = true
                        onForget()
                    }
                }
                .padding(horizontal = if (confirming) Dshm.spacing.md else Dshm.spacing.sm, vertical = Dshm.spacing.xs),
            contentAlignment = Alignment.Center,
        ) {
            if (confirming) {
                Text(
                    text = stringResource(R.string.memory_remove_confirm),
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.danger,
                )
            } else {
                Icon(
                    painterResource(R.drawable.ic_lucide_trash_2),
                    contentDescription = stringResource(R.string.memory_remove),
                    tint = Dshm.colors.textTertiary,
                    modifier = Modifier.size(Dshm.spacing.lg),
                )
            }
        }
    }
}

/** DS Bot `summaryBlocks`：把 `#` 标题和段落拆开。 */
private fun summaryBlocks(text: String): List<Pair<String, String>> {
    val blocks = mutableListOf<Pair<String, String>>()
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotBlank()) {
            blocks.add("p" to paragraph.toString().trim())
            paragraph.clear()
        }
    }
    text.lines().forEach { raw ->
        val line = raw.trim().replace("**", "").replace("__", "")
        val head = Regex("^#{1,6}\\s+(.+)$").find(line)?.groupValues?.get(1)?.trim()
        if (head != null) {
            flush()
            blocks.add("h" to head)
        } else if (line.isEmpty()) {
            flush()
        } else {
            if (paragraph.isNotBlank()) paragraph.append(" ")
            paragraph.append(line.replace(Regex("^[-*•]\\s+"), ""))
        }
    }
    flush()
    return blocks
}

/** DS Bot `ago`：分钟/小时/天相对时间；不到一分钟返回 null（显示「刚刚更新」）。 */
@Composable
private fun ago(at: Long): String? {
    val diff = System.currentTimeMillis() - at
    if (diff < 60_000L) return null
    val minutes = diff / 60_000L
    if (minutes < 60L) return stringResource(R.string.memory_ago_minutes, minutes)
    val hours = minutes / 60L
    if (hours < 24L) return stringResource(R.string.memory_ago_hours, hours)
    return stringResource(R.string.memory_ago_days, hours / 24L)
}

/** 头部的「更新于 X」/「刚刚更新」。 */
@Composable
internal fun updatedLabel(at: Long): String {
    val agoText = ago(at) ?: return stringResource(R.string.memory_updated_now)
    return stringResource(R.string.memory_updated_ago, agoText)
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
    SettingsSheet(
        title = stringResource(R.string.bot_settings_workdir),
        onDismiss = onDismiss,
    ) {
        Column {
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
                                onClick = { onList(parent.second) { next -> listing = next } },
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
                    TextButton(onClick = { mode = "list" }) { Text(stringResource(android.R.string.cancel)) }
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
    }

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
internal fun FolderRow(
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

/** 定时任务列表：底部面板，行内直接删除；可选一个「新增」入口。 */
@Composable
internal fun SchedulesSheet(
    tasks: List<BotScheduleTask>,
    onDelete: (BotScheduleTask) -> Unit,
    onDismiss: () -> Unit,
    onNew: (() -> Unit)? = null,
) {
    SettingsSheet(title = stringResource(R.string.bot_settings_schedules), onDismiss = onDismiss) {
        if (tasks.isEmpty()) {
            Text(
                text = stringResource(R.string.bot_settings_schedule_none),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textTertiary,
            )
        } else {
            Column {
                tasks.forEachIndexed { index, task ->
                    if (index > 0) {
                        androidx.compose.material3.HorizontalDivider(color = Dshm.colors.divider)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = Dshm.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(task.title, style = Dshm.typography.rowTitle, color = Dshm.colors.textPrimary)
                            Text(task.prompt, style = Dshm.typography.rowPreview, color = Dshm.colors.textSecondary)
                        }
                        TextButton(onClick = { onDelete(task) }) {
                            Text(stringResource(R.string.bot_settings_schedule_delete), color = Dshm.colors.danger)
                        }
                    }
                }
            }
        }
        if (onNew != null) {
            Spacer(Modifier.height(Dshm.spacing.md))
            TextButton(onClick = onNew) { Text(stringResource(R.string.bot_settings_schedule_new)) }
        }
    }
}

/** 新建定时任务：底部面板。 */
@Composable
internal fun NewScheduleSheet(
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("09:00") }
    SettingsSheet(title = stringResource(R.string.bot_settings_schedule_new), onDismiss = onDismiss) {
        SettingsInput(
            value = title,
            onValueChange = { title = it },
            placeholder = stringResource(R.string.bot_settings_schedule_title),
        )
        Spacer(Modifier.height(Dshm.spacing.sm))
        SettingsInput(
            value = prompt,
            onValueChange = { prompt = it },
            placeholder = stringResource(R.string.bot_settings_schedule_prompt),
            singleLine = false,
        )
        Spacer(Modifier.height(Dshm.spacing.sm))
        SettingsInput(
            value = time,
            onValueChange = { time = it },
            placeholder = stringResource(R.string.bot_settings_schedule_time),
        )
        Spacer(Modifier.height(Dshm.spacing.lg))
        TextButton(
            onClick = { onSave(title.trim(), prompt.trim(), time.trim()) },
            enabled = title.isNotBlank() && prompt.isNotBlank(),
        ) {
            Text(stringResource(android.R.string.ok))
        }
    }
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
