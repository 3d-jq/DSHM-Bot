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
    var pickModel by rememberSaveable { mutableStateOf(false) }
    var pickShape by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var editWorkdir by rememberSaveable { mutableStateOf(false) }
    var memoryOpen by rememberSaveable { mutableStateOf(false) }
    var newSchedule by rememberSaveable { mutableStateOf(false) }
    var instructions by remember(info?.instructions) { mutableStateOf(info?.instructions.orEmpty()) }

    SettingsPage(
        title = stringResource(R.string.bot_settings_page_title),
        subtitle = info?.name ?: bot.name,
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
                SettingsRow(
                    title = stringResource(R.string.bot_settings_model),
                    value = modelLabel(info?.model, state.models) ?: stringResource(R.string.settings_value_default),
                    onClick = { pickModel = true },
                )
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
                    onClick = { viewModel.update(JSONObject().put("instructions", instructions.trim())) },
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
                viewModel.update(JSONObject().put("name", value))
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
                viewModel.update(JSONObject().put("role", value))
            },
        )
    }
    if (pickModel) {
        AlertDialog(
            onDismissRequest = { pickModel = false },
            containerColor = Dshm.colors.surface,
            title = { Text(stringResource(R.string.bot_settings_model)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    state.models.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pickModel = false
                                    viewModel.update(JSONObject().put("model", option.ref))
                                }
                                .padding(vertical = Dshm.spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = option.ref == info?.model,
                                onClick = null,
                            )
                            Text(
                                text = option.name,
                                style = Dshm.typography.rowTitle,
                                color = Dshm.colors.textPrimary,
                                modifier = Modifier.padding(start = Dshm.spacing.sm),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickModel = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
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
                                        JSONObject().put("avatar", JSONObject().put("shape", shape)),
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
        TextEditDialog(
            title = stringResource(R.string.bot_settings_workdir),
            initial = info.workDir.orEmpty(),
            onDismiss = { editWorkdir = false },
            onSave = { value ->
                editWorkdir = false
                viewModel.update(JSONObject().put("workDir", value))
            },
        )
    }
    if (memoryOpen) {
        AlertDialog(
            onDismissRequest = { memoryOpen = false },
            containerColor = Dshm.colors.surface,
            title = { Text(stringResource(R.string.bot_settings_memory)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (state.memory.isEmpty()) {
                        Text(stringResource(R.string.bot_settings_memory_empty))
                    } else {
                        state.memory.forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Dshm.spacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = entry.text,
                                    style = Dshm.typography.rowPreview,
                                    color = Dshm.colors.textPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { viewModel.forgetMemory(entry) }) {
                                    Text(
                                        text = stringResource(R.string.bot_settings_delete),
                                        color = Dshm.colors.danger,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { memoryOpen = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
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
private fun TextEditDialog(
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

private fun modelLabel(ref: String?, models: List<BotModelOption>): String? {
    if (ref.isNullOrBlank()) return null
    return models.firstOrNull { it.ref == ref }?.name ?: ref
}

private fun usageLabel(tokens: Long, cacheHit: Float?): String {
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
private fun PermissionRows(current: String?, onSelect: (String) -> Unit) {
    val language = settingsLanguage()
    val permissions = listOf("read-only", "workspace-write", "danger-full-access")
    permissions.forEachIndexed { index, preset ->
        if (index > 0) SettingsRowDivider()
        val selected = preset == current
        SettingsRow(
            title = SettingsCopy.permissionLabel(preset, language),
            subtitle = SettingsCopy.permissionHint(preset, language),
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
    var showModels by rememberSaveable { mutableStateOf(false) }
    var editFolder by rememberSaveable { mutableStateOf(false) }
    var editInstructions by rememberSaveable { mutableStateOf(false) }
    var showMemory by rememberSaveable { mutableStateOf(false) }
    var showSchedules by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 面板占屏幕 86%：内容撑到这个高度，ModalBottomSheet 贴底显示，上面留出聊天。
    val sheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.86f

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
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Dshm.spacing.lg)
                .padding(bottom = Dshm.spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Hero 区：按设计文档 §8.4，顶部信息包成一张卡。
            SettingsSection {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BotAvatar(
                        inkId = bot.inkId,
                        glyph = bot.glyph,
                        avatarImage = bot.avatarImage,
                        avatarShape = info?.shape ?: bot.avatarShape,
                        size = Dshm.spacing.settingsAvatar,
                    )
                    Spacer(Modifier.height(Dshm.spacing.sm))
                    Text(
                        text = info?.name ?: bot.name,
                        style = Dshm.typography.sheetTitle,
                        color = Dshm.colors.textPrimary,
                    )
                    info?.role?.takeIf { it.isNotBlank() }?.let { role ->
                        Text(
                            text = role,
                            style = Dshm.typography.rowPreview,
                            color = Dshm.colors.textSecondary,
                        )
                    }
                    Spacer(Modifier.height(Dshm.spacing.md))
                    // 两个入口：详情 / 浏览器。做成行内动作，不是裸按钮。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                    ) {
                        SettingsRow(
                            title = stringResource(R.string.bot_settings_details),
                            icon = R.drawable.ic_lucide_info,
                            modifier = Modifier.weight(1f),
                            trailing = {},
                            onClick = onDetails,
                        )
                        SettingsRow(
                            title = stringResource(R.string.bot_settings_browser),
                            icon = R.drawable.ic_lucide_globe,
                            modifier = Modifier.weight(1f),
                            trailing = {},
                            onClick = onBrowser,
                        )
                    }
                }
            }

            Spacer(Modifier.height(Dshm.spacing.xxl))
            SettingsSection(title = stringResource(R.string.bot_settings_model)) {
                SettingsRow(
                    title = modelLabel(info?.model, state.models)
                        ?: stringResource(R.string.settings_value_default),
                    subtitle = info?.model,
                    onClick = { showModels = true },
                )
            }

            Spacer(Modifier.height(Dshm.spacing.xxl))
            SettingsSection(title = stringResource(R.string.bot_settings_permission)) {
                PermissionRows(
                    current = info?.permission,
                    onSelect = { viewModel.setPermission(it) },
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

            Spacer(Modifier.height(Dshm.spacing.xxl))
            SettingsSection(title = stringResource(R.string.bot_settings_usage)) {
                SettingsRow(
                    title = info?.name ?: bot.name,
                    value = usageLabel(state.usageTokens, state.cacheHit),
                    enabled = false,
                )
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

    if (editFolder && info != null) {
        TextEditDialog(
            title = stringResource(R.string.bot_settings_workdir),
            initial = info.workDir.orEmpty(),
            onDismiss = { editFolder = false },
            onSave = { value ->
                editFolder = false
                viewModel.update(JSONObject().put("workDir", value))
            },
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
                viewModel.update(JSONObject().put("instructions", value))
            },
        )
    }
    if (showMemory) {
        AlertDialog(
            onDismissRequest = { showMemory = false },
            containerColor = Dshm.colors.surface,
            title = { Text(stringResource(R.string.bot_settings_memory)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (state.memory.isEmpty()) {
                        Text(stringResource(R.string.bot_settings_memory_empty))
                    } else {
                        state.memory.forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Dshm.spacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = entry.text,
                                    style = Dshm.typography.rowPreview,
                                    color = Dshm.colors.textPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { viewModel.forgetMemory(entry) }) {
                                    Text(
                                        text = stringResource(R.string.bot_settings_delete),
                                        color = Dshm.colors.danger,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMemory = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
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

    if (showModels) {
        AlertDialog(
            onDismissRequest = { showModels = false },
            containerColor = Dshm.colors.surface,
            title = { Text(stringResource(R.string.bot_settings_model)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    state.models.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showModels = false
                                    viewModel.update(JSONObject().put("model", option.ref))
                                }
                                .padding(vertical = Dshm.spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option.ref == info?.model, onClick = null)
                            Text(
                                text = option.name,
                                style = Dshm.typography.rowTitle,
                                color = Dshm.colors.textPrimary,
                                modifier = Modifier.padding(start = Dshm.spacing.sm),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModels = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
