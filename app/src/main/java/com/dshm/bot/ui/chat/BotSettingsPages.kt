package com.dshm.bot.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalDensity
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.runtime.RuntimeEndpoint
import com.dshm.bot.ui.home.ChatRowUi
import com.dshm.bot.ui.components.FloatingIconButton
import com.dshm.bot.ui.settings.SettingsInput
import com.dshm.bot.ui.settings.SettingsAnchoredMenu
import com.dshm.bot.ui.settings.SettingsMenuItem
import com.dshm.bot.ui.settings.SettingsPage
import com.dshm.bot.ui.settings.SettingsPrimaryButton
import com.dshm.bot.ui.settings.SettingsRow
import com.dshm.bot.ui.settings.SettingsRowDivider
import com.dshm.bot.ui.settings.SettingsSection
import com.dshm.bot.ui.settings.SettingsSegmentedPill
import com.dshm.bot.ui.settings.ScheduleTimePickerField
import org.json.JSONObject

/**
 * Bot / 群设置页里推出来的**二级整屏页**。
 *
 * 这些入口在设置页里点开是整屏（我们的 `SettingsPage` 顶栏 + iOS push 动效），
 * 和「名字胶囊」弹出的底部面板是两回事：胶囊那边只预览。
 */
internal const val BOT_PAGE_MEMORY = "memory"
internal const val BOT_PAGE_SCHEDULES = "schedules"
internal const val BOT_PAGE_FOLDER = "folder"
internal const val BOT_PAGE_NOTICE = "notice"

@Composable
internal fun botDetailsViewModel(botId: String, endpoint: RuntimeEndpoint?): BotDetailsViewModel =
    viewModel(
        key = "bot-details:$botId:${endpoint?.baseUrl.orEmpty()}:${endpoint?.token.orEmpty()}",
        factory = BotDetailsViewModel.factory(botId, endpoint),
    )

@Composable
internal fun roomSettingsViewModel(roomId: String, endpoint: RuntimeEndpoint?): RoomSettingsViewModel =
    viewModel(
        key = "room-settings:$roomId:${endpoint?.baseUrl.orEmpty()}",
        factory = RoomSettingsViewModel.factory(roomId, endpoint),
    )

/** 记忆：整屏（摘要 / 全部条目 + 底部「问我或直接更新」输入框）。 */
@Composable
fun BotMemoryPage(row: ChatRowUi, endpoint: RuntimeEndpoint?, onBack: () -> Unit) {
    if (row.isGroup) {
        val vm: RoomSettingsViewModel = roomSettingsViewModel(row.id, endpoint)
        val state by vm.ui.collectAsStateWithLifecycle()
        MemoryPage(
            group = true,
            ownerName = state.info?.name ?: row.name,
            entries = state.memory,
            sizes = state.memorySizes,
            summary = state.memorySummary,
            summaryAt = state.memorySummaryAt,
            summaryError = state.memorySummaryError,
            memoryLimit = state.memoryLimit,
            summarizing = state.memorySummarizing,
            fresh = state.memoryFresh,
            onRefresh = vm::refreshMemory,
            onAsk = { text, onReply -> vm.memoryAsk(text, onReply) },
            onForget = { entry -> vm.forgetMemory(entry) },
            onStopPolling = vm::stopMemoryPolling,
            onBack = onBack,
        )
    } else {
        val vm: BotDetailsViewModel = botDetailsViewModel(row.id, endpoint)
        val state by vm.ui.collectAsStateWithLifecycle()
        MemoryPage(
            group = false,
            ownerName = state.info?.name ?: row.name,
            entries = state.memory,
            sizes = state.memorySizes,
            summary = state.memorySummary,
            summaryAt = state.memorySummaryAt,
            summaryError = state.memorySummaryError,
            memoryLimit = state.memoryLimit,
            summarizing = state.memorySummarizing,
            fresh = state.memoryFresh,
            onRefresh = vm::refreshMemory,
            onAsk = { text, onReply -> vm.memoryAsk(text, onReply) },
            onForget = { entry -> vm.forgetMemory(entry) },
            onStopPolling = vm::stopMemoryPolling,
            onBack = onBack,
        )
    }
}

@Composable
private fun MemoryPage(
    group: Boolean,
    ownerName: String,
    entries: List<BotMemoryEntry>,
    sizes: Map<String, String>,
    summary: String?,
    summaryAt: Long?,
    summaryError: String?,
    memoryLimit: Int?,
    summarizing: Boolean,
    fresh: Boolean,
    onRefresh: (regenerate: Boolean) -> Unit,
    onAsk: (text: String, onReply: (String?) -> Unit) -> Unit,
    onForget: (BotMemoryEntry) -> Unit,
    onStopPolling: () -> Unit,
    onBack: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    val options = listOf(
        stringResource(R.string.memory_title_summary),
        stringResource(R.string.memory_title_entries),
    )
    // 状态不占顶栏（顶栏只放我们自己的悬浮圆钮），放在分段选择下面一行。
    val status = when {
        summarizing && summary != null -> stringResource(R.string.memory_updating)
        tab == 1 -> statusFromCount(entries.size)
        summaryAt != null -> updatedLabel(summaryAt)
        else -> null
    }
    LaunchedEffect(Unit) { onRefresh(false) }
    DisposableEffect(Unit) { onDispose { onStopPolling() } }

    SettingsPage(
        title = stringResource(R.string.bot_settings_memory),
        onBack = onBack,
        actions = {
            Box {
                // 和返回键同一套悬浮圆钮（我们的顶栏设计）。
                FloatingIconButton(
                    icon = R.drawable.ic_lucide_ellipsis,
                    contentDescription = stringResource(R.string.memory_menu),
                    onClick = { menuOpen = true },
                )
                // 我们自己的下拉面板（设置卡片的颜色与圆角），不是 M3 默认样式。
                SettingsAnchoredMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    anchorHeightPx = with(LocalDensity.current) { Dshm.spacing.iconButton.roundToPx() },
                ) {
                    SettingsMenuItem(
                        text = stringResource(R.string.memory_regenerate),
                        selected = false,
                        onClick = {
                            menuOpen = false
                            tab = 0
                            onRefresh(true)
                        },
                    )
                }
            }
        },
        bottomBar = { MemoryAskBar(onAsk = onAsk) },
    ) {
        item {
            SettingsSection {
                SettingsSegmentedPill(
                    options = options,
                    selected = options[tab],
                    onSelect = { label -> tab = options.indexOf(label).coerceAtLeast(0) },
                    modifier = Modifier.padding(Dshm.spacing.md),
                )
                status?.takeIf { it.isNotEmpty() }?.let { text ->
                    Text(
                        text = text,
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.textTertiary,
                        modifier = Modifier.padding(
                            start = Dshm.spacing.lg,
                            end = Dshm.spacing.lg,
                            bottom = Dshm.spacing.md,
                        ),
                    )
                }
            }
        }
        item {
            SettingsSection {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Dshm.spacing.lg),
                ) {
                    if (tab == 0) {
                        MemorySummaryBlock(
                            group = group,
                            ownerName = ownerName,
                            entries = entries,
                            summary = summary,
                            summaryAt = summaryAt,
                            summaryError = summaryError,
                            summarizing = summarizing,
                            fresh = fresh,
                            onRegenerate = { onRefresh(true) },
                        )
                    } else {
                        MemoryEntriesBlock(
                            group = group,
                            ownerName = ownerName,
                            entries = entries,
                            sizes = sizes,
                            memoryLimit = memoryLimit,
                            onForget = onForget,
                        )
                    }
                }
            }
        }
    }
}

/** 定时任务：整屏（列表 + 页内新增表单）。 */
@Composable
fun BotSchedulesPage(row: ChatRowUi, endpoint: RuntimeEndpoint?, onBack: () -> Unit) {
    val vm: BotDetailsViewModel = botDetailsViewModel(row.id, endpoint)
    val state by vm.ui.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var time by rememberSaveable { mutableStateOf("09:00") }

    SettingsPage(
        title = stringResource(R.string.bot_settings_schedules),
        onBack = onBack,
    ) {
        item {
            SettingsSection {
                if (state.schedules.isEmpty()) {
                    SettingsRow(
                        title = stringResource(R.string.bot_settings_schedule_none),
                        enabled = false,
                    )
                } else {
                    state.schedules.forEachIndexed { index, task ->
                        if (index > 0) SettingsRowDivider()
                        SettingsRow(
                            title = task.title,
                            subtitle = task.prompt,
                            value = stringResource(R.string.bot_settings_schedule_delete),
                            valueColor = Dshm.colors.danger,
                            onClick = { vm.deleteSchedule(task) },
                        )
                    }
                }
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.bot_settings_schedule_new),
                    value = if (creating) stringResource(R.string.action_cancel) else null,
                    onClick = { creating = !creating },
                )
                if (creating) {
                    SettingsRowDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Dshm.spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                    ) {
                        SettingsInput(
                            value = title,
                            onValueChange = { title = it },
                            placeholder = stringResource(R.string.bot_settings_schedule_title),
                        )
                        SettingsInput(
                            value = prompt,
                            onValueChange = { prompt = it },
                            placeholder = stringResource(R.string.bot_settings_schedule_prompt),
                            singleLine = false,
                        )
                        // 和设置里的定时任务页同一个 24h 时间轮，不让用户手打时间。
                        ScheduleTimePickerField(
                            value = time,
                            onValueChange = { time = it },
                        )
                        Spacer(Modifier.height(Dshm.spacing.xs))
                        SettingsPrimaryButton(
                            text = stringResource(R.string.action_save),
                            enabled = title.isNotBlank() && prompt.isNotBlank(),
                            onClick = {
                                vm.createDailySchedule(title.trim(), prompt.trim(), time.trim())
                                title = ""
                                prompt = ""
                                time = "09:00"
                                creating = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 工作文件夹：整屏（已知目录 + 浏览 + 系统选择器 + 手填）。 */
@Composable
fun BotFolderPage(row: ChatRowUi, endpoint: RuntimeEndpoint?, onBack: () -> Unit) {
    if (row.isGroup) {
        val vm: RoomSettingsViewModel = roomSettingsViewModel(row.id, endpoint)
        val state by vm.ui.collectAsStateWithLifecycle()
        FolderPage(
            value = state.info?.workDir,
            fallback = state.teamFolder,
            folders = state.folders,
            picker = state.folderPicker,
            onLoad = { vm.loadFolders { } },
            onList = { path, then -> vm.listFolders(path, then) },
            onPickNative = { then -> vm.pickFolder(then) },
            onChoose = { path ->
                vm.updateRoom(
                    patch = JSONObject().put("workDir", path),
                    optimistic = { info -> info.copy(workDir = path?.takeIf { it.isNotEmpty() }) },
                )
                onBack()
            },
            onBack = onBack,
        )
    } else {
        val vm: BotDetailsViewModel = botDetailsViewModel(row.id, endpoint)
        val state by vm.ui.collectAsStateWithLifecycle()
        FolderPage(
            value = state.info?.workDir,
            fallback = stringResource(R.string.bot_settings_workdir_default),
            folders = state.folders,
            picker = state.folderPicker,
            onLoad = { vm.loadFolders() },
            onList = { path, then -> vm.listFolders(path, then) },
            onPickNative = { then -> vm.pickFolder(then) },
            onChoose = { path ->
                vm.update(
                    patch = JSONObject().put("workDir", path.orEmpty()),
                    optimistic = { info -> info.copy(workDir = path) },
                )
                onBack()
            },
            onBack = onBack,
        )
    }
}

@Composable
private fun FolderPage(
    value: String?,
    fallback: String?,
    folders: List<BotFolder>,
    picker: String?,
    onLoad: () -> Unit,
    onList: (String?, (BotFolderListing?) -> Unit) -> Unit,
    onPickNative: ((String?) -> Unit) -> Unit,
    onChoose: (String?) -> Unit,
    onBack: () -> Unit,
) {
    var mode by remember { mutableStateOf("list") }
    var listing by remember { mutableStateOf<BotFolderListing?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var typing by remember { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    val errorText = stringResource(R.string.bot_details_folder_error)
    LaunchedEffect(Unit) { onLoad() }

    SettingsPage(
        title = stringResource(R.string.bot_settings_workdir),
        onBack = onBack,
    ) {
        item {
            SettingsSection {
                if (mode == "browse") {
                    val here = listing
                    if (here != null) {
                        SettingsRow(title = here.path, enabled = false)
                        SettingsRowDivider()
                        val parent = here.crumbs.getOrNull(here.crumbs.size - 2)
                        if (parent != null) {
                            SettingsRow(
                                title = stringResource(R.string.bot_details_folder_up),
                                subtitle = parent.second,
                                onClick = { onList(parent.second) { next -> listing = next } },
                            )
                            SettingsRowDivider()
                        }
                        here.entries.forEachIndexed { index, entry ->
                            if (index > 0) SettingsRowDivider()
                            SettingsRow(
                                title = entry.title,
                                subtitle = entry.path,
                                onClick = { onList(entry.path) { next -> listing = next } },
                            )
                        }
                        SettingsRowDivider()
                        SettingsRow(
                            title = stringResource(R.string.bot_details_folder_use),
                            subtitle = here.path,
                            value = stringResource(R.string.action_choose),
                            onClick = { onChoose(here.path) },
                        )
                    }
                    if (here == null || here.entries.isEmpty()) {
                        SettingsRow(
                            title = stringResource(R.string.bot_details_folder_empty),
                            enabled = false,
                        )
                    }
                    SettingsRowDivider()
                    SettingsRow(
                        title = stringResource(R.string.action_back),
                        onClick = { mode = "list" },
                    )
                } else {
                    SettingsRow(
                        title = fallback ?: stringResource(R.string.bot_settings_workdir_default),
                        value = if (value == null) stringResource(R.string.settings_value_current) else null,
                        onClick = { onChoose(null) },
                    )
                    val options = buildList {
                        if (value != null && folders.none { it.path == value }) {
                            add(BotFolder(value, value.substringAfterLast('/')))
                        }
                        addAll(folders)
                    }
                    options.forEach { folder ->
                        SettingsRowDivider()
                        SettingsRow(
                            title = folder.title,
                            subtitle = folder.path,
                            subtitleColor = if (folder.path == value) Dshm.colors.accent else null,
                            onClick = { onChoose(folder.path) },
                        )
                    }
                    if (picker == "native") {
                        SettingsRowDivider()
                        SettingsRow(
                            title = stringResource(R.string.bot_details_folder_choose),
                            onClick = { onPickNative { path -> if (path != null) onChoose(path) } },
                        )
                    }
                    if (picker == "browse" || picker == null) {
                        SettingsRowDivider()
                        SettingsRow(
                            title = stringResource(R.string.bot_details_folder_browse),
                            onClick = {
                                problem = null
                                onList(value) { next ->
                                    if (next == null) {
                                        problem = errorText
                                    } else {
                                        listing = next
                                        mode = "browse"
                                    }
                                }
                            },
                        )
                    }
                    SettingsRowDivider()
                    SettingsRow(
                        title = stringResource(R.string.bot_details_folder_type),
                        value = if (typing) stringResource(R.string.action_cancel) else null,
                        onClick = {
                            typed = value.orEmpty()
                            typing = !typing
                        },
                    )
                    if (typing) {
                        SettingsRowDivider()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Dshm.spacing.lg),
                            verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                        ) {
                            SettingsInput(
                                value = typed,
                                onValueChange = { typed = it },
                                placeholder = stringResource(R.string.bot_details_folder_path_hint),
                            )
                            SettingsPrimaryButton(
                                text = stringResource(R.string.action_save),
                                onClick = {
                                    typing = false
                                    onChoose(typed.trim().takeIf { it.isNotEmpty() })
                                },
                            )
                        }
                    }
                }
            }
        }
        problem?.let { message ->
            item {
                Text(
                    text = message,
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.danger,
                    modifier = Modifier.padding(horizontal = Dshm.spacing.lg),
                )
            }
        }
    }
}

/** 群公告：整屏（多行文本 + 底部保存）。 */
@Composable
fun BotNoticePage(row: ChatRowUi, endpoint: RuntimeEndpoint?, onBack: () -> Unit) {
    val vm: RoomSettingsViewModel = roomSettingsViewModel(row.id, endpoint)
    val state by vm.ui.collectAsStateWithLifecycle()
    NoticePage(
        initial = state.info?.notice.orEmpty(),
        onSave = { value ->
            vm.updateRoom(
                patch = JSONObject().put("notice", value),
                optimistic = { info -> info.copy(notice = value) },
            )
        },
        onBack = onBack,
    )
}

@Composable
private fun NoticePage(initial: String, onSave: (String) -> Unit, onBack: () -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    val dirty = draft.trim() != initial.trim()
    SettingsPage(
        title = stringResource(R.string.room_settings_notice),
        onBack = onBack,
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
            ) {
                SettingsPrimaryButton(
                    text = stringResource(R.string.action_save),
                    enabled = dirty,
                    onClick = {
                        onSave(draft.trim())
                        onBack()
                    },
                )
            }
        },
    ) {
        item {
            SettingsSection {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Dshm.spacing.lg),
                ) {
                    SettingsInput(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = stringResource(R.string.room_settings_notice_hint),
                        singleLine = false,
                    )
                }
            }
        }
    }
}
