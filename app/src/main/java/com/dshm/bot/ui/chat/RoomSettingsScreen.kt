package com.dshm.bot.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.runtime.RuntimeEndpoint
import com.dshm.bot.domain.model.AvatarGlyph
import com.dshm.bot.ui.components.BotAvatar
import com.dshm.bot.ui.settings.SettingsPage
import com.dshm.bot.ui.settings.SettingsRow
import com.dshm.bot.ui.settings.SettingsRowDivider
import com.dshm.bot.ui.settings.SettingsSection
import com.dshm.bot.ui.settings.SettingsSegmentedPill
import com.dshm.bot.ui.settings.SettingsSheet
import org.json.JSONArray
import org.json.JSONObject

/** 群聊完整设置页：只读单 Bot 设置页，全部走 `state.rooms[]` 与 room 接口。 */
@Composable
fun RoomSettingsScreen(
    row: com.dshm.bot.ui.home.ChatRowUi,
    endpoint: RuntimeEndpoint?,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onOpenMember: (String) -> Unit = {},
    /** 设置页里的二级整屏页（记忆 / 工作文件夹 / 公告），由外层导航 push。 */
    onOpenPage: (String) -> Unit = {},
    viewModel: RoomSettingsViewModel = viewModel(
        key = "room-settings:${row.id}:${endpoint?.baseUrl.orEmpty()}",
        factory = RoomSettingsViewModel.factory(row.id, endpoint),
    ),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val info = state.info
    // 成员状态是实时的（谁在干活 / 谁等批准）：页面开着时轻量轮询，退出就停。
    DisposableEffect(Unit) {
        viewModel.startStatusPolling()
        onDispose { viewModel.stopStatusPolling() }
    }
    var editName by rememberSaveable { mutableStateOf(false) }
    var pickAdmin by rememberSaveable { mutableStateOf(false) }
    var addMember by rememberSaveable { mutableStateOf(false) }
    var permissionOpen by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Dshm.spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RoomAvatar(members = row.roomMembers, size = Dshm.spacing.settingsAvatar)
                    Spacer(Modifier.height(Dshm.spacing.sm))
                    Text(text = info?.name ?: row.name, style = Dshm.typography.sheetTitle)
                    Spacer(Modifier.height(Dshm.spacing.xxs))
                    Text(
                        text = stringResource(R.string.room_settings_member_count, info?.members?.size ?: 0),
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.textSecondary,
                    )
                    info?.admin?.let { admin ->
                        Text(
                            text = stringResource(
                                R.string.room_settings_admin_value,
                                state.bots.firstOrNull { it.id == admin }?.name ?: admin,
                            ),
                            style = Dshm.typography.rowPreview,
                            color = Dshm.colors.textTertiary,
                        )
                    }
                }
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.room_settings_profile)) {
                SettingsRow(
                    title = stringResource(R.string.bot_settings_name),
                    value = info?.name ?: row.name,
                    onClick = { editName = true },
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.room_settings_notice),
                    subtitle = info?.notice?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.room_settings_notice_empty),
                    onClick = { onOpenPage(BOT_PAGE_NOTICE) },
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.bot_settings_workdir),
                    subtitle = info?.workDir ?: stringResource(R.string.bot_settings_workdir_default),
                    onClick = { onOpenPage(BOT_PAGE_FOLDER) },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.room_settings_who_answers)) {
                val options = listOf(
                    stringResource(R.string.room_settings_mode_admin),
                    stringResource(R.string.room_settings_mode_everyone),
                )
                val selected = if (info?.mode == "everyone") options[1] else options[0]
                SettingsSegmentedPill(
                    options = options,
                    selected = selected,
                    onSelect = { label ->
                        val mode = if (label == options[1]) "everyone" else "admin"
                        viewModel.updateRoom(
                            patch = JSONObject().put("mode", mode),
                            optimistic = { it.copy(mode = mode) },
                        )
                    },
                    modifier = Modifier.padding(Dshm.spacing.md),
                )
                SettingsRow(
                    title = stringResource(R.string.room_settings_admin),
                    value = state.bots.firstOrNull { it.id == info?.admin }?.name
                        ?: stringResource(R.string.room_settings_admin_none),
                    onClick = { pickAdmin = true },
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.room_settings_relay_limit),
                    subtitle = stringResource(R.string.room_settings_relay_hint),
                    value = info?.relayLimit?.toString()
                        ?: info?.relayLimitDefault?.toString()
                        ?: stringResource(R.string.settings_value_default),
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.room_settings_members)) {
                val names = state.botRows.associateBy { it.id }
                info?.members?.forEachIndexed { index, id ->
                    if (index > 0) SettingsRowDivider()
                    val member = names[id]
                    // 实时状态优先：等你批准 / 正在干活（对齐 DS Bot `MemberState`），
                    // 空闲时才退回显示角色或管理员标记。
                    val status = state.memberStatus[id]
                    val badge = if (id == info.admin) {
                        stringResource(R.string.room_settings_admin_badge)
                    } else {
                        member?.role
                    }
                    SettingsRow(
                        title = member?.name ?: id,
                        subtitle = when (status?.state) {
                            "ask" -> stringResource(R.string.room_member_waiting_approval)
                            "work" -> stringResource(
                                R.string.room_member_working,
                                status.doing.orEmpty(),
                            )
                            else -> badge
                        },
                        subtitleColor = when (status?.state) {
                            "ask" -> Dshm.colors.warning
                            "work" -> Dshm.colors.accent
                            else -> null
                        },
                        leading = {
                            if (member != null) {
                                BotAvatar(
                                    inkId = member.inkId,
                                    glyph = AvatarGlyph.BOT,
                                    avatarImage = member.avatarImage,
                                    avatarShape = member.avatarShape,
                                    size = Dshm.spacing.lg + Dshm.spacing.sm,
                                )
                            } else {
                                Spacer(Modifier.size(Dshm.spacing.lg + Dshm.spacing.sm))
                            }
                        },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (id != info.admin) {
                                    TextButton(onClick = {
                                        viewModel.updateRoom(
                                            patch = JSONObject().put("admin", id),
                                            optimistic = { it.copy(admin = id) },
                                        )
                                    }) {
                                        Text(stringResource(R.string.room_settings_make_admin))
                                    }
                                }
                                if (info.members.size > 2) {
                                    TextButton(onClick = {
                                        viewModel.updateRoom(
                                            patch = JSONObject().put("remove", JSONArray().put(id)),
                                            optimistic = { it.copy(members = it.members - id) },
                                        )
                                    }) {
                                        Text(
                                            stringResource(R.string.room_settings_remove),
                                            color = Dshm.colors.danger,
                                        )
                                    }
                                }
                            }
                        },
                        onClick = { onOpenMember(id) },
                    )
                }
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.room_settings_add_member),
                    onClick = { addMember = true },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.bot_settings_permission)) {
                SettingsRow(
                    title = stringResource(R.string.room_settings_permission_shared),
                    subtitle = stringResource(R.string.room_settings_permission_hint),
                    trailing = {
                        Switch(
                            checked = info?.permission != null,
                            enabled = info != null && !state.saving,
                            onCheckedChange = { shared ->
                                val preset = if (shared) {
                                    state.permissions.firstOrNull()?.ref ?: "read-only"
                                } else {
                                    JSONObject.NULL
                                }
                                viewModel.updateRoom(
                                    patch = JSONObject().put("permission", preset),
                                    optimistic = {
                                        it.copy(permission = (preset as? String))
                                    },
                                )
                            },
                        )
                    },
                )
                if (info?.permission != null) {
                    SettingsRowDivider()
                    SettingsRow(
                        title = state.permissions.firstOrNull { it.ref == info.permission }?.name
                            ?: info.permission,
                        onClick = { permissionOpen = true },
                    )
                }
            }
        }

        if (state.memoryEnabled) {
            item {
                SettingsSection(title = stringResource(R.string.bot_settings_memory)) {
                    SettingsRow(
                        title = if (state.memory.isEmpty()) {
                            stringResource(R.string.bot_settings_memory_empty)
                        } else {
                            stringResource(R.string.bot_settings_memory_count, state.memory.size)
                        },
                        value = stringResource(R.string.bot_settings_manage),
                        onClick = { onOpenPage(BOT_PAGE_MEMORY) },
                    )
                }
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.bot_settings_usage)) {
                SettingsRow(
                    title = info?.name ?: row.name,
                    value = usageLabel(state.usageTokens, state.cacheHit),
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.room_settings_sidebar)) {
                SettingsRow(
                    title = stringResource(R.string.room_settings_pin),
                    trailing = {
                        Switch(
                            checked = info?.pinned == true,
                            enabled = info != null && !state.saving,
                            onCheckedChange = { pinned ->
                                viewModel.setFlags(
                                    patch = JSONObject().put("pinned", pinned),
                                    optimistic = { it.copy(pinned = pinned) },
                                )
                            },
                        )
                    },
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.room_settings_hide),
                    trailing = {
                        Switch(
                            checked = info?.hidden == true,
                            enabled = info != null && !state.saving,
                            onCheckedChange = { hidden ->
                                viewModel.setFlags(
                                    patch = JSONObject().put("hidden", hidden),
                                    optimistic = { it.copy(hidden = hidden) },
                                )
                            },
                        )
                    },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.bot_settings_danger)) {
                SettingsRow(
                    title = stringResource(R.string.room_settings_delete),
                    subtitle = stringResource(R.string.room_settings_delete_hint),
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
            initial = if (info.named) info.name else "",
            placeholder = stringResource(R.string.room_settings_name_hint),
            onDismiss = { editName = false },
            onSave = { value ->
                editName = false
                viewModel.updateRoom(
                    patch = JSONObject().put("name", value),
                    optimistic = { it.copy(name = value.ifBlank { row.name }, named = value.isNotBlank()) },
                )
            },
        )
    }
    if (pickAdmin && info != null) {
        ListPickDialog(
            title = stringResource(R.string.room_settings_admin),
            options = state.bots.filter { it.id in info.members },
            onDismiss = { pickAdmin = false },
            onSelect = { member ->
                pickAdmin = false
                viewModel.updateRoom(
                    patch = JSONObject().put("admin", member.id),
                    optimistic = { it.copy(admin = member.id) },
                )
            },
        )
    }
    if (addMember && info != null) {
        ListPickDialog(
            title = stringResource(R.string.room_settings_add_member),
            options = state.bots.filter { it.id !in info.members },
            onDismiss = { addMember = false },
            onSelect = { member ->
                addMember = false
                viewModel.updateRoom(
                    patch = JSONObject().put("add", JSONArray().put(member.id)),
                    optimistic = { it.copy(members = it.members + member.id) },
                )
            },
        )
    }
    if (permissionOpen && info != null) {
        ListPickDialog(
            title = stringResource(R.string.bot_settings_permission),
            options = state.permissions.map { RoomMemberOption(it.ref, it.name) },
            onDismiss = { permissionOpen = false },
            onSelect = { option ->
                permissionOpen = false
                viewModel.updateRoom(
                    patch = JSONObject().put("permission", option.id),
                    optimistic = { it.copy(permission = option.id) },
                )
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.room_settings_delete)) },
            text = { Text(stringResource(R.string.room_settings_delete_confirm, info?.name ?: row.name)) },
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
}

@Composable
private fun ListPickDialog(
    title: String,
    options: List<RoomMemberOption>,
    onDismiss: () -> Unit,
    onSelect: (RoomMemberOption) -> Unit,
) {
    // 和其它选择项一致：整行可点，选中即回填（底部面板）。
    SettingsSheet(title = title, onDismiss = onDismiss) {
        if (options.isEmpty()) {
            Text(
                text = stringResource(R.string.room_settings_no_options),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textTertiary,
            )
        } else {
            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(option) }
                        .padding(vertical = Dshm.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = false, onClick = null)
                    Text(
                        text = option.name,
                        style = Dshm.typography.rowTitle,
                        modifier = Modifier.padding(start = Dshm.spacing.sm),
                    )
                }
            }
        }
    }
}

/** 新建群聊：至少两名 Bot，先选管理员，再选其他成员。 */
@Composable
fun NewGroupDialog(
    bots: List<RoomMemberOption>,
    onDismiss: () -> Unit,
    onCreate: suspend (String, String, List<String>) -> Result<String>,
    onCreated: (String) -> Unit,
) {
    var admin by rememberSaveable { mutableStateOf<String?>(null) }
    var members by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var name by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.home_new_group)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.room_settings_choose_admin), style = Dshm.typography.rowTitle)
                bots.forEach { bot ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                admin = bot.id
                                members = members - bot.id
                            }
                            .padding(vertical = Dshm.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = admin == bot.id, onClick = null)
                        Text(bot.name, modifier = Modifier.padding(start = Dshm.spacing.sm))
                    }
                }
                if (admin != null) {
                    Spacer(Modifier.height(Dshm.spacing.md))
                    Text(stringResource(R.string.room_settings_choose_members), style = Dshm.typography.rowTitle)
                    bots.filter { it.id != admin }.forEach { bot ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    members = if (bot.id in members) members - bot.id else members + bot.id
                                }
                                .padding(vertical = Dshm.spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = bot.id in members,
                                onCheckedChange = null,
                            )
                            Text(bot.name, modifier = Modifier.padding(start = Dshm.spacing.sm))
                        }
                    }
                    Spacer(Modifier.height(Dshm.spacing.md))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.bot_settings_name)) },
                        placeholder = { Text(stringResource(R.string.room_settings_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { message ->
                    Spacer(Modifier.height(Dshm.spacing.sm))
                    Text(message, color = Dshm.colors.danger, style = Dshm.typography.rowPreview)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && admin != null && members.isNotEmpty(),
                onClick = {
                    val pickedAdmin = admin ?: return@TextButton
                    busy = true
                    error = null
                    scope.launch {
                        val result = onCreate(name, pickedAdmin, members.toList())
                        busy = false
                        result.onSuccess { id -> onCreated(id) }
                            .onFailure { failure -> error = failure.message ?: "创建群聊失败" }
                    }
                },
            ) { Text(stringResource(R.string.room_settings_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
