package com.dshm.bot.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.ScheduleTarget
import com.dshm.bot.data.remote.ScheduleTargetKind
import com.dshm.bot.data.remote.ScheduleTiming
import com.dshm.bot.data.remote.ScheduledTask
import com.dshm.bot.data.remote.scheduleAtIso
import com.dshm.bot.data.remote.scheduleTimingLabel
import com.dshm.bot.data.remote.validateScheduleTiming
import com.dshm.bot.domain.model.AvatarGlyph
import com.dshm.bot.ui.chat.RoomAvatar
import com.dshm.bot.ui.components.BotAvatar
import java.time.ZoneId
import com.dshm.bot.ui.settings.SettingsPrimaryButton
import com.dshm.bot.ui.settings.SettingsSecondaryButton

/**
 * 「定时任务」页（对齐 DS-Bot `client/schedules.js`）：
 *
 * 顶部「新建任务」展开表单（发送给 / 名字 / 做什么 / 什么时候跑），下面按目标分组列出任务，
 * 行里带目标头像、时间规则、下次运行与两次点击删除。宿主没开 Automation tasks 时直接说明。
 */
@Composable
fun ScheduledTasksScreen(
    viewModel: ScheduledTasksViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<ScheduledTask?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // 「新建任务」是页面里的内联表单（不是 sheet）：系统返回先收表单，别整页退出去。
    BackHandler(enabled = adding) { adding = false }

    LaunchedEffect(Unit) { viewModel.refresh() }

    val targetById = state.targets.associateBy { it.id }
    val groups = mutableListOf<Pair<ScheduleTarget?, List<ScheduledTask>>>()
    state.targets.forEach { target ->
        val tasks = state.tasks.filter {
            (target.kind == ScheduleTargetKind.BOT && it.botId == target.id) ||
                (target.kind == ScheduleTargetKind.ROOM && it.roomId == target.id)
        }
        if (tasks.isNotEmpty()) groups += target to tasks
    }
    val grouped = groups.flatMap { (_, tasks) -> tasks.map { it.id } }.toSet()
    val orphans = state.tasks.filter { it.id !in grouped }
    if (orphans.isNotEmpty()) groups += null to orphans

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
            else -> {
                if (error != null) item { SettingsError(error.orEmpty()) }
                item {
                    if (adding) {
                        NewTaskForm(
                            targets = state.targets,
                            onCancel = { adding = false },
                            onCreate = { target, title, prompt, timing, onFailure ->
                                error = null
                                viewModel.create(target, title, prompt, timing) { failure ->
                                    if (failure == null) {
                                        adding = false
                                    } else {
                                        onFailure(failure)
                                    }
                                }
                            },
                        )
                    } else {
                        SettingsSection {
                            SettingsRow(
                                title = stringResource(R.string.bot_settings_schedule_new),
                                icon = R.drawable.ic_lucide_plus,
                                onClick = { adding = true },
                            )
                        }
                    }
                }
                if (state.tasks.isEmpty() && !adding) {
                    item { SettingsEmpty(stringResource(R.string.schedules_empty)) }
                }
                groups.forEach { (target, tasks) ->
                    // 按分组身份给 key：列表变化时不再按「位置」复用旧 item，
                    // 杜绝「删掉旧的、新的填到旧位置」看起来像两层叠着。
                    item(key = "group-${target?.id ?: "orphans"}") {
                        SettingsSection(
                            title = target?.name ?: stringResource(R.string.schedules_unknown_target),
                        ) {
                            tasks.forEachIndexed { index, task ->
                                if (index > 0) SettingsRowDivider()
                                TaskRow(
                                    task = task,
                                    target = target,
                                    roomName = task.roomId?.let { id -> targetById[id]?.name },
                                    onOpen = { detail = task },
                                    onDelete = {
                                        error = null
                                        viewModel.delete(task) { failure -> error = failure }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

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
                    if (shown.prompt.isNotBlank()) {
                        Spacer(Modifier.height(Dshm.spacing.sm))
                        Text(
                            text = shown.prompt,
                            style = Dshm.typography.rowPreview,
                            color = Dshm.colors.textPrimary,
                        )
                    }
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                ) {
                    SettingsSecondaryButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = { detail = null },
                        modifier = Modifier.weight(1f),
                        fillWidth = false,
                        onBackground = Dshm.colors.surface,
                    )
                    SettingsPrimaryButton(
                        text = stringResource(R.string.action_delete),
                        onClick = {
                            detail = null
                            error = null
                            viewModel.delete(shown) { failure -> error = failure }
                        },
                        modifier = Modifier.weight(1f),
                        fillWidth = false,
                        danger = true,
                    )
                }
            },
        )
    }
}

/** 一条任务：目标头像 + 标题 + 时间规则 / 内容 + 下次时间 + 两次点击删除。 */
@Composable
private fun TaskRow(
    task: ScheduledTask,
    target: ScheduleTarget?,
    roomName: String?,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirming by remember(task.id) { mutableStateOf(false) }
    LaunchedEffect(confirming) {
        if (confirming) {
            kotlinx.coroutines.delay(3_000)
            confirming = false
        }
    }
    SettingsRow(
        title = task.title.ifEmpty { stringResource(R.string.schedules_untitled) },
        // 只留一行时间规则；下次时间 / 提纲 / 群名都进详情，列表行不再被挤换行。
        subtitle = listOfNotNull(
            scheduleTimingLabel(task),
            roomName?.let { stringResource(R.string.schedules_in_room, it) },
        ).joinToString(" · "),
        singleLineTitle = true,
        leading = {
            TargetAvatar(target, size = Dshm.spacing.lg + Dshm.spacing.sm)
        },
        onClick = onOpen,
        trailing = {
            Box(
                modifier = Modifier
                    .size(Dshm.spacing.iconButton)
                    .clip(CircleShape)
                    .clickable {
                        if (confirming) {
                            confirming = false
                            onDelete()
                        } else {
                            confirming = true
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(
                        if (confirming) R.drawable.ic_lucide_check else R.drawable.ic_lucide_trash_2,
                    ),
                    contentDescription = stringResource(
                        if (confirming) R.string.schedules_delete_again else R.string.action_delete,
                    ),
                    tint = if (confirming) Dshm.colors.danger else Dshm.colors.textTertiary,
                    modifier = Modifier.size(Dshm.spacing.topBarIcon),
                )
            }
        },
    )
}

/** Bot 用它的头像，群聊用成员头像堆叠；认不出目标时留一个占位。 */
@Composable
private fun TargetAvatar(target: ScheduleTarget?, size: Dp) {
    when {
        target == null -> Spacer(Modifier.size(size))
        target.kind == ScheduleTargetKind.ROOM -> RoomAvatar(members = target.members, size = size)
        else -> BotAvatar(
            inkId = target.bot?.inkId,
            glyph = AvatarGlyph.BOT,
            avatarImage = target.bot?.avatarImage,
            avatarShape = target.bot?.avatarShape,
            size = size,
        )
    }
}

/** 新建任务表单：发送给 / 名字 / 做什么 / 什么时候跑。 */
@Composable
private fun NewTaskForm(
    targets: List<ScheduleTarget>,
    onCancel: () -> Unit,
    onCreate: (ScheduleTarget, String, String, ScheduleTiming, (String) -> Unit) -> Unit,
) {
    var targetId by rememberSaveable { mutableStateOf(targets.firstOrNull()?.id.orEmpty()) }
    var title by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableIntStateOf(1) }
    var atText by rememberSaveable { mutableStateOf("") }
    var timeText by rememberSaveable { mutableStateOf("09:00") }
    var weekdayMask by rememberSaveable { mutableIntStateOf(0b0001_1111) }
    var countText by rememberSaveable { mutableStateOf("1") }
    var unit by rememberSaveable { mutableIntStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val target = targets.firstOrNull { it.id == targetId } ?: targets.firstOrNull()
    val kinds = listOf(
        stringResource(R.string.schedules_kind_at),
        stringResource(R.string.schedules_kind_daily),
        stringResource(R.string.schedules_kind_weekly),
        stringResource(R.string.schedules_kind_every),
    )
    val units = listOf(
        stringResource(R.string.schedules_unit_minutes),
        stringResource(R.string.schedules_unit_hours),
        stringResource(R.string.schedules_unit_days),
    )
    val zone = remember { ZoneId.systemDefault().id }
    val weekdays = (1..7).filter { weekdayMask and (1 shl (it - 1)) != 0 }
    val needFields = stringResource(R.string.schedules_need_fields)

    fun timing(): ScheduleTiming? = when (kind) {
        0 -> scheduleAtIso(atText)?.let { ScheduleTiming.At(it) }
        1 -> ScheduleTiming.Daily(timeText.trim(), zone)
        2 -> ScheduleTiming.Weekly(timeText.trim(), zone, weekdays)
        else -> {
            val count = countText.trim().toIntOrNull() ?: return null
            ScheduleTiming.Every(count * listOf(1, 60, 1_440)[unit])
        }
    }

    fun submit() {
        if (busy) return
        val picked = target
        if (picked == null) {
            error = "团队里还没有可用的 Bot"
            return
        }
        if (title.isBlank() || prompt.isBlank()) {
            error = needFields
            return
        }
        val whenValue = timing()
        if (whenValue == null) {
            error = "时间格式不对"
            return
        }
        validateScheduleTiming(whenValue)?.let { message ->
            error = message
            return
        }
        busy = true
        error = null
        onCreate(picked, title.trim(), prompt.trim(), whenValue) { failure ->
            busy = false
            error = failure
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm)) {
        SettingsSection(title = stringResource(R.string.bot_settings_schedule_new)) {
            Column(
                modifier = Modifier.padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
                verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
            ) {
                Text(
                    text = stringResource(R.string.schedules_send_to),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
                TargetComboBox(
                    targets = targets,
                    selectedId = target?.id,
                    onSelect = { targetId = it },
                )
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
                    modifier = Modifier.heightIn(min = Dshm.spacing.rowMinHeight * 2),
                )
            }
        }
        SettingsSection(title = stringResource(R.string.schedules_when)) {
            Column(
                modifier = Modifier.padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
                verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
            ) {
                SettingsSegmentedPill(
                    options = kinds,
                    selected = kinds.getOrElse(kind) { kinds[1] },
                    onSelect = { picked -> kind = kinds.indexOf(picked).coerceAtLeast(0) },
                )
                when (kind) {
                    0 -> DateTimePickerField(
                        value = atText,
                        onValueChange = { atText = it },
                    )

                    2 -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.xxs),
                    ) {
                        listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, label ->
                            Box(modifier = Modifier.weight(1f)) {
                                SettingsChoiceChip(
                                    label = label,
                                    selected = weekdayMask and (1 shl index) != 0,
                                    onClick = { weekdayMask = weekdayMask xor (1 shl index) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
                if (kind == 1 || kind == 2) {
                    ScheduleTimePickerField(
                        value = timeText,
                        onValueChange = { timeText = it },
                    )
                }
                if (kind == 3) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            SettingsInput(
                                value = countText,
                                onValueChange = { countText = it },
                                placeholder = stringResource(R.string.schedules_every_hint),
                            )
                        }
                    }
                    SettingsSegmentedPill(
                        options = units,
                        selected = units.getOrElse(unit) { units[0] },
                        onSelect = { picked -> unit = units.indexOf(picked).coerceAtLeast(0) },
                    )
                }
            }
        }
        error?.let { SettingsError(it) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dshm.spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
        ) {
            SettingsSecondaryButton(
                text = stringResource(R.string.action_cancel),
                onClick = onCancel,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                fillWidth = false,
            )
            SettingsPrimaryButton(
                text = stringResource(
                    if (busy) R.string.schedules_creating else R.string.schedules_create,
                ),
                onClick = ::submit,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                fillWidth = false,
            )
        }
    }
}

private val atFormatter: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** 只读点选字段：外观和输入框一致，点它弹选择器；不再让用户手打时间。 */
@Composable
internal fun SchedulePickerField(
    text: String,
    placeholder: String,
    onClick: () -> Unit,
) {
    // 和刚统一的表单输入框同一套 M3 描边框（只读）；点整块弹选择器。
    OutlinedTextField(
        value = text,
        onValueChange = {},
        readOnly = true,
        enabled = false,
        singleLine = true,
        label = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_clock_3),
                contentDescription = null,
                tint = Dshm.colors.textTertiary,
                modifier = Modifier.size(Dshm.spacing.icon),
            )
        },
        shape = dshmFieldShape(),
        colors = dshmFieldColors(),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

/** 每天 / 每周的 24 小时时间轮；对齐 dsh-Go `showClockSheet`。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleTimePickerField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val parts = value.split(":")
    val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 9
    val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0

    SchedulePickerField(
        text = value,
        placeholder = stringResource(R.string.schedules_time_hint),
        onClick = { open = true },
    )
    if (open) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = Dshm.colors.surface,
            title = {
                Text(
                    text = stringResource(R.string.schedules_pick_time),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
            },
            text = { TimePicker(state = state) },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                ) {
                    SettingsSecondaryButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = { open = false },
                        modifier = Modifier.weight(1f),
                        fillWidth = false,
                        onBackground = Dshm.colors.surface,
                    )
                    SettingsPrimaryButton(
                        text = stringResource(R.string.action_done),
                        onClick = {
                            onValueChange("%02d:%02d".format(state.hour, state.minute))
                            open = false
                        },
                        modifier = Modifier.weight(1f),
                        fillWidth = false,
                        danger = false,
                    )
                }
            },
        )
    }
}

/** 「一次」任务：先选日期，再选时间，拼成 `yyyy-MM-dd HH:mm`。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimePickerField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    var stage by remember { mutableStateOf(0) }
    var pickedDate by remember { mutableStateOf<java.time.LocalDate?>(null) }

    SchedulePickerField(
        text = value,
        placeholder = stringResource(R.string.schedules_at_hint),
        onClick = { stage = 1 },
    )
    if (stage == 1) {
        val parsed = runCatching { java.time.LocalDateTime.parse(value, atFormatter) }.getOrNull()
            ?: java.time.LocalDateTime.now().plusHours(1)
        val state = rememberDatePickerState(
            // M3 DatePicker 把 selectedDateMillis 按 **UTC 零点** 解释日期；直接传
            // 本地午夜的 instant，UTC+ 时区会回显成前一天。这里按 UTC 语义构造，
            // 只取日期部分；回读也统一走 UTC（见下面 confirmButton）。
            initialSelectedDateMillis = parsed
                .toLocalDate()
                .atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
        )
        AlertDialog(
            onDismissRequest = { stage = 0 },
            containerColor = Dshm.colors.surface,
            title = {
                Text(
                    text = stringResource(R.string.schedules_pick_date),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
            },
            text = { DatePicker(state = state) },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                ) {
                    SettingsSecondaryButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = { stage = 0 },
                        modifier = Modifier.weight(1f),
                        fillWidth = false,
                        onBackground = Dshm.colors.surface,
                    )
                    SettingsPrimaryButton(
                        text = stringResource(R.string.action_done),
                        onClick = {
                            pickedDate = state.selectedDateMillis?.let {
                                java.time.Instant.ofEpochMilli(it)
                                    .atZone(java.time.ZoneOffset.UTC)
                                    .toLocalDate()
                            }
                            stage = 2
                        },
                        modifier = Modifier.weight(1f),
                        fillWidth = false,
                        danger = false,
                    )
                }
            },
        )
    }
    if (stage == 2) {
        val parsed = runCatching { java.time.LocalDateTime.parse(value, atFormatter) }.getOrNull()
        val state = rememberTimePickerState(
            initialHour = parsed?.hour ?: 9,
            initialMinute = parsed?.minute ?: 0,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { stage = 0 },
            containerColor = Dshm.colors.surface,
            title = {
                Text(
                    text = stringResource(R.string.schedules_pick_time),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
            },
            text = { TimePicker(state = state) },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                ) {
                    SettingsSecondaryButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = {
                            pickedDate = null
                            stage = 0
                        },
                        modifier = Modifier.weight(1f),
                        fillWidth = false,
                        onBackground = Dshm.colors.surface,
                    )
                    SettingsPrimaryButton(
                        text = stringResource(R.string.action_done),
                        onClick = {
                            val date = pickedDate ?: java.time.LocalDate.now()
                            onValueChange(date.atTime(state.hour, state.minute).format(atFormatter))
                            pickedDate = null
                            stage = 0
                        },
                        modifier = Modifier.weight(1f),
                        fillWidth = false,
                        danger = false,
                    )
                }
            },
        )
    }
}

/** 目标选择：Bot 在前，群聊在后（带「群聊」后缀），不支持搜索。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetComboBox(
    targets: List<ScheduleTarget>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = targets.firstOrNull { it.id == selectedId }
    val roomSuffix = stringResource(R.string.schedules_room_suffix)

    fun label(target: ScheduleTarget): String =
        if (target.kind == ScheduleTargetKind.ROOM) "${target.name}（$roomSuffix）" else target.name

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (targets.isNotEmpty()) expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.let { label(it) } ?: "",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            enabled = targets.isNotEmpty(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = dshmFieldShape(),
            colors = dshmFieldColors(),
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(Dshm.radius.lg),
            containerColor = Dshm.colors.surface,
            border = androidx.compose.foundation.BorderStroke(
                Dshm.spacing.hairline,
                Dshm.colors.divider,
            ),
            shadowElevation = Dshm.spacing.cardElevation,
        ) {
            targets.forEach { target ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label(target),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(target.id)
                    },
                )
            }
        }
    }
}
