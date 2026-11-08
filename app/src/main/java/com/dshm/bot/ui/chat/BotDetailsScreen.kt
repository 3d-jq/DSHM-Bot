package com.dshm.bot.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.runtime.RuntimeEndpoint
import com.dshm.bot.domain.model.BotShapeDefaults
import com.dshm.bot.ui.components.BotAvatar
import com.dshm.bot.ui.home.ChatRowUi
import com.dshm.bot.ui.settings.SettingsCopy
import com.dshm.bot.ui.settings.SettingsPage
import com.dshm.bot.ui.settings.SettingsRow
import com.dshm.bot.ui.settings.SettingsRowDivider
import com.dshm.bot.ui.settings.SettingsSection
import com.dshm.bot.ui.settings.SettingsSegmentedPill
import com.dshm.bot.ui.settings.settingsLanguage
import org.json.JSONObject

/** Bot 完整设置页（顶栏右侧按钮进入，走系统 push 动效）。 */
@Composable
fun BotDetailsScreen(
    bot: ChatRowUi,
    endpoint: RuntimeEndpoint?,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: BotDetailsViewModel = viewModel(
        key = "bot-details:${bot.id}:${endpoint?.baseUrl.orEmpty()}:${endpoint?.token.orEmpty()}",
        factory = BotDetailsViewModel.factory(bot.id, endpoint),
    ),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val info = state.info
    var editName by rememberSaveable { mutableStateOf(false) }
    var editRole by rememberSaveable { mutableStateOf(false) }
    var pickShape by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var editWorkdir by rememberSaveable { mutableStateOf(false) }
    var memoryOpen by rememberSaveable { mutableStateOf(false) }
    var newSchedule by rememberSaveable { mutableStateOf(false) }
    var instructions by remember(info?.instructions) { mutableStateOf(info?.instructions.orEmpty()) }
    // 这一行要在 LazyColumn 的 item 里用，所以在可组合上下文里先算好。
    val runStatsLine = runStatsLabel(state.runStats)

    SettingsPage(
        title = stringResource(R.string.bot_settings_page_title),
        onBack = onBack,
    ) {
        if (state.error != null) {
            item {
                Text(
                    text = state.error.orEmpty(),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.danger,
                    modifier = Modifier.padding(horizontal = Dshm.spacing.lg),
                )
            }
        }
        item {
            SettingsSection {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Dshm.spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BotAvatar(
                        inkId = bot.inkId,
                        glyph = bot.glyph,
                        avatarImage = bot.avatarImage,
                        avatarShape = info?.shape ?: bot.avatarShape,
                        size = Dshm.spacing.settingsAvatar,
                        modifier = Modifier.clickable { pickShape = true },
                    )
                    Spacer(Modifier.width(Dshm.spacing.md))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = info?.name ?: bot.name,
                                style = Dshm.typography.sheetTitle,
                                color = Dshm.colors.textPrimary,
                            )
                            if (state.isMain) {
                                Spacer(Modifier.width(Dshm.spacing.xs))
                                Text(
                                    text = stringResource(R.string.bot_settings_main_badge),
                                    style = Dshm.typography.rowTime,
                                    color = Dshm.colors.accent,
                                )
                            }
                        }
                        Text(
                            text = info?.role?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.bot_settings_no_role),
                            style = Dshm.typography.rowPreview,
                            color = Dshm.colors.textSecondary,
                        )
                    }
                }
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.bot_settings_profile)) {
                SettingsRow(
                    title = stringResource(R.string.bot_settings_name),
                    value = info?.name ?: bot.name,
                    onClick = { editName = true },
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.bot_settings_role),
                    value = info?.role?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.bot_settings_no_role),
                    onClick = { editRole = true },
                )
                SettingsRowDivider()
                Column(Modifier.padding(Dshm.spacing.md)) {
                    BotModelComboBox(
                        models = state.models,
                        selectedRef = info?.model,
                        onSelect = { ref ->
                            viewModel.update(
                                patch = JSONObject().put("model", ref),
                                optimistic = { it.copy(model = ref) },
                            )
                        },
                        enabled = info != null && !state.saving,
                    )
                }
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.bot_settings_workdir),
                    subtitle = info?.workDir ?: stringResource(R.string.bot_settings_workdir_default),
                    onClick = { editWorkdir = true },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.bot_settings_privacy)) {
                SettingsRow(
                    title = stringResource(R.string.bot_settings_private),
                    subtitle = if (state.isMain) {
                        stringResource(R.string.bot_settings_private_main_hint)
                    } else {
                        stringResource(R.string.bot_settings_private_hint)
                    },
                    enabled = !state.isMain,
                    trailing = {
                        androidx.compose.material3.Switch(
                            checked = info?.hidden == true,
                            enabled = !state.isMain && !state.saving,
                            onCheckedChange = { hidden -> viewModel.setHidden(hidden) },
                        )
                    },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.bot_settings_note)) {
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    placeholder = { Text(stringResource(R.string.bot_settings_note_hint)) },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Dshm.spacing.md),
                )
                SettingsRow(
                    title = stringResource(R.string.bot_settings_note_save),
                    value = if (state.saving) stringResource(R.string.bot_settings_saving) else null,
                    enabled = instructions.trim() != info?.instructions.orEmpty().trim(),
                    onClick = {
                        viewModel.update(
                            patch = JSONObject().put("instructions", instructions.trim()),
                            optimistic = { it.copy(instructions = instructions.trim()) },
                        )
                    },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.bot_settings_usage)) {
                SettingsRow(
                    title = info?.name ?: bot.name,
                    value = usageLabel(state.usageTokens, state.cacheHit),
                    enabled = false,
                )
            }
        }

        // 缓存命中 / 上下文占用单独一张卡：跟「用量」一样有自己的标题，才对得齐。
        runStatsLine?.let { label ->
            item {
                SettingsSection(title = stringResource(R.string.bot_settings_context)) {
                    SettingsRow(title = label, enabled = false)
                }
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.bot_settings_memory)) {
                SettingsRow(
                    title = if (state.memory.isEmpty()) {
                        stringResource(R.string.bot_settings_memory_empty)
                    } else {
                        stringResource(R.string.bot_settings_memory_count, state.memory.size)
                    },
                    value = stringResource(R.string.bot_settings_manage),
                    onClick = { memoryOpen = true },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.bot_settings_schedules)) {
                state.schedules.forEachIndexed { index, task ->
                    if (index > 0) SettingsRowDivider()
                    SettingsRow(
                        title = task.title,
                        subtitle = task.prompt,
                        value = stringResource(R.string.bot_settings_schedule_delete),
                        valueColor = Dshm.colors.danger,
                        onClick = { viewModel.deleteSchedule(task) },
                    )
                }
                if (state.schedules.isNotEmpty()) SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.bot_settings_schedule_new),
                    onClick = { newSchedule = true },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.bot_settings_main)) {
                if (state.isMain) {
                    SettingsRow(
                        title = stringResource(R.string.bot_settings_main_badge),
                        subtitle = stringResource(R.string.bot_settings_main_hint),
                        enabled = false,
                    )
                } else {
                    SettingsRow(
                        title = stringResource(R.string.bot_settings_make_main),
                        subtitle = stringResource(R.string.bot_settings_make_main_hint),
                        value = stringResource(R.string.bot_settings_change),
                        onClick = { viewModel.setMain() },
                    )
                }
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.bot_settings_look)) {
                SettingsRow(
                    title = stringResource(R.string.bot_settings_shape),
                    value = shapeLabel(info?.shape),
                    onClick = { pickShape = true },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.bot_settings_manage)) {
                SettingsRow(
                    title = stringResource(R.string.bot_settings_duplicate),
                    subtitle = stringResource(R.string.bot_settings_duplicate_hint),
                    onClick = { viewModel.duplicate() },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.bot_settings_danger)) {
                SettingsRow(
                    title = stringResource(R.string.bot_settings_delete),
                    subtitle = stringResource(R.string.bot_settings_delete_hint),
                    value = stringResource(R.string.bot_settings_delete),
                    valueColor = Dshm.colors.danger,
                    onClick = { confirmDelete = true },
                )
            }
        }
    }

    if (editName && info != null) {
        TextEditDialog(
            title = stringResource(R.string.bot_settings_name),
            initial = info.name,
            onDismiss = { editName = false },
            onSave = { value ->
                editName = false
                viewModel.update(
                    patch = JSONObject().put("name", value),
                    optimistic = { it.copy(name = value) },
                )
            },
        )
    }
    if (editRole && info != null) {
        TextEditDialog(
            title = stringResource(R.string.bot_settings_role),
            initial = info.role,
            onDismiss = { editRole = false },
            onSave = { value ->
                editRole = false
                viewModel.update(
                    patch = JSONObject().put("role", value),
                    optimistic = { it.copy(role = value) },
                )
            },
        )
    }
    if (pickShape) {
        val shapes = listOf("whale") + BotShapeDefaults.shapes
        AlertDialog(
            onDismissRequest = { pickShape = false },
            containerColor = Dshm.colors.surface,
            title = { Text(stringResource(R.string.bot_settings_shape)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    shapes.forEach { shape ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pickShape = false
                                    viewModel.update(
                                        patch = JSONObject().put("avatar", JSONObject().put("shape", shape)),
                                        optimistic = { it.copy(shape = shape) },
                                    )
                                }
                                .padding(vertical = Dshm.spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = shape == (info?.shape ?: "whale"), onClick = null)
                            Text(
                                text = shapeLabel(shape),
                                style = Dshm.typography.rowTitle,
                                color = Dshm.colors.textPrimary,
                                modifier = Modifier.padding(start = Dshm.spacing.sm),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickShape = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Dshm.colors.surface,
            title = { Text(stringResource(R.string.bot_settings_delete)) },
            text = { Text(stringResource(R.string.bot_settings_delete_confirm, bot.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete { error -> if (error == null) onDeleted() }
                }) {
                    Text(stringResource(R.string.bot_settings_delete), color = Dshm.colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    if (editWorkdir && info != null) {
        FolderPickerDialog(
            value = info.workDir,
            fallback = stringResource(R.string.bot_settings_workdir_default),
            folders = state.folders,
            picker = state.folderPicker,
            onList = { path, then -> viewModel.listFolders(path, then) },
            onPickNative = { then -> viewModel.pickFolder(then) },
            onChoose = { path ->
                editWorkdir = false
                viewModel.update(
                    patch = JSONObject().put("workDir", path.orEmpty()),
                    optimistic = { it.copy(workDir = path) },
                )
            },
            onDismiss = { editWorkdir = false },
            onLoad = { viewModel.loadFolders() },
        )
    }
    if (memoryOpen) {
        MemoryPanelDialog(
            ownerName = info?.name ?: bot.name,
            group = false,
            entries = state.memory,
            sizes = state.memorySizes,
            summary = state.memorySummary,
            summarizing = state.memorySummarizing,
            fresh = state.memoryFresh,
            onAdd = { scope, topic, text -> viewModel.addMemory(scope, topic, text) },
            onEdit = { entry, next, topic -> viewModel.editMemory(entry, next, topic) },
            onForget = { entry -> viewModel.forgetMemory(entry) },
            onDismiss = { memoryOpen = false },
        )
    }
    if (newSchedule) {
        NewScheduleDialog(
            onDismiss = { newSchedule = false },
            onSave = { title, prompt, time ->
                newSchedule = false
                viewModel.createDailySchedule(title, prompt, time)
            },
        )
    }
}

@Composable
private fun NewScheduleDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("09:00") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Dshm.colors.surface,
        title = { Text(stringResource(R.string.bot_settings_schedule_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.bot_settings_schedule_title)) },
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    minLines = 2,
                    maxLines = 4,
                    label = { Text(stringResource(R.string.bot_settings_schedule_prompt)) },
                )
                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.bot_settings_schedule_time)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isNotBlank() && prompt.isNotBlank()) {
                        onSave(title.trim(), prompt.trim(), time.trim())
                    }
                },
                enabled = title.isNotBlank() && prompt.isNotBlank(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
internal fun TextEditDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    multiline: Boolean = false,
    placeholder: String? = null,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Dshm.colors.surface,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = !multiline,
                minLines = if (multiline) 4 else 1,
                maxLines = if (multiline) 10 else 1,
                placeholder = placeholder?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

internal fun usageLabel(tokens: Long, cacheHit: Float?): String {
    if (tokens <= 0L) return "—"
    val amount = when {
        tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000.0)
        tokens >= 1_000 -> String.format("%.1fK", tokens / 1_000.0)
        else -> tokens.toString()
    }
    val hit = cacheHit?.let { " · ${(it * 100).toInt()}%" } ?: ""
    return "$amount tok$hit"
}

@Composable
private fun shapeLabel(shape: String?): String = when (shape) {
    null, "", "whale" -> stringResource(R.string.bot_shape_whale)
    "star" -> "Star"
    "moon" -> "Moon"
    "gumdrop" -> "Gumdrop"
    "peanut" -> "Peanut"
    "bunny" -> "Bunny"
    "kitty" -> "Kitty"
    "acorn" -> "Acorn"
    else -> shape
}

/**
 * 点击头像/名字胶囊弹出的高面板：模型、权限、工作目录和说明都在这里，
 * 顶栏右侧按钮进入 [BotDetailsScreen] 完整页。
 */
@OptIn(ExperimentalMaterial3Api::class)
/**
 * 权限档位：每一档一行（行高 56dp），右边一个勾选圆点。
 * 选中的那档用主色，「完全权限」那档用危险色。
 */
@Composable
private fun PermissionRows(
    current: String?,
    options: List<BotPermissionOption>,
    onSelect: (String) -> Unit,
) {
    val language = settingsLanguage()
    val permissions = options.map { it.ref }.ifEmpty {
        listOf("read-only", "workspace-write", "danger-full-access")
    }
    val labels = options.associate { it.ref to it.name }
    val hints = options.associate { it.ref to it.hint }
    permissions.forEachIndexed { index, preset ->
        if (index > 0) SettingsRowDivider()
        val selected = preset == current
        SettingsRow(
            title = labels[preset] ?: SettingsCopy.permissionLabel(preset, language),
            subtitle = hints[preset] ?: SettingsCopy.permissionHint(preset, language),
            trailing = {
                Box(
                    modifier = Modifier
                        .size(Dshm.spacing.selectionTick)
                        .clip(CircleShape)
                        .background(
                            if (selected) Dshm.colors.accent else androidx.compose.ui.graphics.Color.Transparent,
                        )
                        .border(
                            Dshm.spacing.hairline + Dshm.spacing.xs / 2,
                            if (selected) Dshm.colors.accent else Dshm.colors.textTertiary,
                            CircleShape,
                        ),
                )
            },
            onClick = { onSelect(preset) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotSettingsSheet(
    bot: ChatRowUi,
    endpoint: RuntimeEndpoint?,
    onDismiss: () -> Unit,
    onDetails: () -> Unit,
    onBrowser: () -> Unit = {},
    viewModel: BotDetailsViewModel = viewModel(
        key = "bot-sheet:${bot.id}:${endpoint?.baseUrl.orEmpty()}:${endpoint?.token.orEmpty()}",
        factory = BotDetailsViewModel.factory(bot.id, endpoint),
    ),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val info = state.info
    val language = settingsLanguage()
    var editFolder by rememberSaveable { mutableStateOf(false) }
    var editInstructions by rememberSaveable { mutableStateOf(false) }
    var showMemory by rememberSaveable { mutableStateOf(false) }
    var showSchedules by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(DETAILS_TAB) }
    var sheetNotice by rememberSaveable { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 面板占屏幕 86%：内容撑到这个高度，ModalBottomSheet 贴底显示，上面留出聊天。
    val sheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.86f
    // 浏览器分类直接在面板里显示页面（不是「打开浏览器」按钮）。
    val browserContext = LocalContext.current
    val browserSession = remember(bot.id) { BotBrowserStore.of(browserContext).session(bot.id) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 底板底用页面底色，卡片（surface）才分得开 —— 都用 surface 会糊成一片。
        containerColor = Dshm.colors.pageBackground,
        shape = RoundedCornerShape(topStart = Dshm.radius.xl, topEnd = Dshm.radius.xl),
        scrimColor = Dshm.colors.scrim,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dshm.spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            // 身份卡不放了：这一页是从 Bot 名字胶囊点进来的，头像和名字上方就有，
            // 面板里再占一块，浏览器和设置就只剩一点点空间。
            Spacer(Modifier.height(Dshm.spacing.md))
            val tabLabels = listOf(
                stringResource(R.string.bot_settings_details),
                stringResource(R.string.bot_settings_browser),
            )
            SettingsSegmentedPill(
                options = tabLabels,
                selected = tabLabels[selectedTab],
                onSelect = { label ->
                    val next = tabLabels.indexOf(label)
                    selectedTab = next
                    sheetNotice = null
                },
            )
            sheetNotice?.let { notice ->
                Spacer(Modifier.height(Dshm.spacing.sm))
                Text(
                    text = notice,
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textTertiary,
                )
            }
            }
            Spacer(Modifier.height(Dshm.spacing.md))
            if (selectedTab == BROWSER_TAB) {
                BotBrowserPanel(
                    session = browserSession,
                    modifier = Modifier.weight(1f),
                    onExpand = {
                        onDismiss()
                        onBrowser()
                    },
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Dshm.spacing.lg)
                        .padding(bottom = Dshm.spacing.xl),
                ) {
            SettingsSection(title = stringResource(R.string.bot_settings_page_title)) {
                SettingsRow(
                    title = stringResource(R.string.bot_settings_details),
                    onClick = {
                        onDismiss()
                        onDetails()
                    },
                )
            }

            Spacer(Modifier.height(Dshm.spacing.xxl))
            SettingsSection(title = stringResource(R.string.bot_settings_model)) {
                Column(Modifier.padding(Dshm.spacing.md)) {
                    BotModelComboBox(
                        models = state.models,
                        selectedRef = info?.model,
                        onSelect = { ref ->
                            viewModel.update(
                                patch = JSONObject().put("model", ref),
                                optimistic = { it.copy(model = ref) },
                            )
                        },
                        enabled = info != null && !state.saving,
                    )
                }
                if (state.saving) {
                    SettingsRow(
                        title = stringResource(R.string.bot_settings_saving),
                        enabled = false,
                    )
                }
            }

            Spacer(Modifier.height(Dshm.spacing.xxl))
            SettingsSection(title = stringResource(R.string.bot_settings_permission)) {
                PermissionRows(
                    current = info?.permission,
                    options = state.permissions,
                    onSelect = viewModel::setPermission,
                )
            }

            Spacer(Modifier.height(Dshm.spacing.xxl))
            SettingsSection(title = stringResource(R.string.bot_settings_workdir)) {
                SettingsRow(
                    title = stringResource(R.string.bot_settings_folder),
                    subtitle = info?.workDir ?: stringResource(R.string.bot_settings_workdir_default),
                    onClick = { editFolder = true },
                )
            }

            if (state.memoryEnabled) {
                Spacer(Modifier.height(Dshm.spacing.xxl))
                SettingsSection(title = stringResource(R.string.bot_settings_memory)) {
                SettingsRow(
                    title = if (state.memory.isEmpty()) {
                        stringResource(R.string.bot_settings_memory_empty)
                    } else {
                        stringResource(R.string.bot_settings_memory_count, state.memory.size)
                    },
                    value = stringResource(R.string.bot_settings_manage),
                    onClick = { showMemory = true },
                )
                }
            }

            Spacer(Modifier.height(Dshm.spacing.xxl))
            SettingsSection(title = stringResource(R.string.bot_settings_usage)) {
                SettingsRow(
                    title = info?.name ?: bot.name,
                    value = usageLabel(state.usageTokens, state.cacheHit),
                    enabled = false,
                )
            }

            // 缓存命中 / 上下文占用单独一张卡：跟「用量」一样有自己的标题，才对得齐。
            runStatsLabel(state.runStats)?.let { label ->
                Spacer(Modifier.height(Dshm.spacing.xxl))
                SettingsSection(title = stringResource(R.string.bot_settings_context)) {
                    SettingsRow(title = label, enabled = false)
                }
            }

            Spacer(Modifier.height(Dshm.spacing.xxl))
            SettingsSection(title = stringResource(R.string.bot_settings_schedules)) {
                SettingsRow(
                    title = if (state.schedules.isEmpty()) {
                        stringResource(R.string.bot_settings_schedule_none)
                    } else {
                        stringResource(R.string.bot_settings_memory_count, state.schedules.size)
                    },
                    value = stringResource(R.string.bot_settings_manage),
                    onClick = { showSchedules = true },
                )
            }

            Spacer(Modifier.height(Dshm.spacing.xxl))
            SettingsSection(title = stringResource(R.string.bot_settings_instructions)) {
                SettingsRow(
                    title = (info?.instructions?.trim()?.takeIf { it.isNotEmpty() }
                        ?: stringResource(R.string.bot_settings_note_add)),
                    subtitle = info?.instructions?.trim().orEmpty()
                        .ifBlank { stringResource(R.string.bot_settings_instructions) },
                    onClick = { editInstructions = true },
                )
            }
                }
            }
        }
    }

    if (editFolder && info != null) {
        FolderPickerDialog(
            value = info.workDir,
            fallback = stringResource(R.string.bot_settings_workdir_default),
            folders = state.folders,
            picker = state.folderPicker,
            onList = { path, then -> viewModel.listFolders(path, then) },
            onPickNative = { then -> viewModel.pickFolder(then) },
            onChoose = { path ->
                editFolder = false
                viewModel.update(
                    patch = JSONObject().put("workDir", path.orEmpty()),
                    optimistic = { it.copy(workDir = path) },
                )
            },
            onDismiss = { editFolder = false },
            onLoad = { viewModel.loadFolders() },
        )
    }
    if (editInstructions && info != null) {
        TextEditDialog(
            title = stringResource(R.string.bot_settings_instructions),
            initial = info.instructions,
            multiline = true,
            placeholder = stringResource(R.string.bot_settings_note_hint),
            onDismiss = { editInstructions = false },
            onSave = { value ->
                editInstructions = false
                viewModel.update(
                    patch = JSONObject().put("instructions", value),
                    optimistic = { it.copy(instructions = value) },
                )
            },
        )
    }
    if (showMemory) {
        MemoryPanelDialog(
            ownerName = info?.name ?: bot.name,
            group = false,
            entries = state.memory,
            sizes = state.memorySizes,
            summary = state.memorySummary,
            summarizing = state.memorySummarizing,
            fresh = state.memoryFresh,
            onAdd = { scope, topic, text -> viewModel.addMemory(scope, topic, text) },
            onEdit = { entry, next, topic -> viewModel.editMemory(entry, next, topic) },
            onForget = { entry -> viewModel.forgetMemory(entry) },
            onDismiss = { showMemory = false },
        )
    }
    if (showSchedules) {
        AlertDialog(
            onDismissRequest = { showSchedules = false },
            containerColor = Dshm.colors.surface,
            title = { Text(stringResource(R.string.bot_settings_schedules)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (state.schedules.isEmpty()) {
                        Text(stringResource(R.string.bot_settings_schedule_none))
                    } else {
                        state.schedules.forEach { task ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Dshm.spacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = task.title,
                                        style = Dshm.typography.rowTitle,
                                        color = Dshm.colors.textPrimary,
                                    )
                                    Text(
                                        text = task.prompt,
                                        style = Dshm.typography.rowPreview,
                                        color = Dshm.colors.textSecondary,
                                    )
                                }
                                TextButton(onClick = { viewModel.deleteSchedule(task) }) {
                                    Text(
                                        text = stringResource(R.string.bot_settings_schedule_delete),
                                        color = Dshm.colors.danger,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSchedules = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

}

private const val DETAILS_TAB = 0
private const val BROWSER_TAB = 1
