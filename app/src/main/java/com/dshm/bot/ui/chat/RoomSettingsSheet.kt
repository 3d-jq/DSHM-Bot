package com.dshm.bot.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.runtime.RuntimeEndpoint
import com.dshm.bot.domain.model.AvatarGlyph
import com.dshm.bot.ui.components.BotAvatar
import com.dshm.bot.ui.home.ChatRowUi
import com.dshm.bot.ui.settings.SettingsRow
import com.dshm.bot.ui.settings.SettingsRowDivider
import com.dshm.bot.ui.settings.SettingsSection

/** 群聊名字胶囊弹出的底部面板：只读群资料，不复用单 Bot 的模型/角色字段。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomSettingsSheet(
    row: ChatRowUi,
    endpoint: RuntimeEndpoint?,
    onDismiss: () -> Unit,
    onDetails: () -> Unit,
    onOpenMember: (String) -> Unit,
    /** 复制整段对话（网页版详情抽屉的 Share）。 */
    onCopyTranscript: () -> Unit = {},
    viewModel: RoomSettingsViewModel = viewModel(
        key = "room-sheet:${row.id}:${endpoint?.baseUrl.orEmpty()}",
        factory = RoomSettingsViewModel.factory(row.id, endpoint),
    ),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val info = state.info
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.86f

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
                .height(sheetHeight)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Dshm.spacing.lg)
                .padding(bottom = Dshm.spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SettingsSection {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Dshm.spacing.lg),
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
                }
            }
            Spacer(Modifier.height(Dshm.spacing.xxl))
            SettingsSection(title = stringResource(R.string.room_settings_profile)) {
                SettingsRow(
                    title = stringResource(R.string.room_settings_notice),
                    subtitle = info?.notice?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.room_settings_notice_empty),
                    enabled = false,
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.room_settings_admin),
                    value = info?.admin?.let { admin ->
                        state.bots.firstOrNull { it.id == admin }?.name ?: admin
                    } ?: stringResource(R.string.room_settings_admin_none),
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.room_settings_who_answers),
                    value = if (info?.mode == "everyone") {
                        stringResource(R.string.room_settings_mode_everyone)
                    } else {
                        stringResource(R.string.room_settings_mode_admin)
                    },
                )
                info?.permission?.let { permission ->
                    SettingsRowDivider()
                    SettingsRow(
                        title = stringResource(R.string.bot_settings_permission),
                        value = state.permissions.firstOrNull { it.ref == permission }?.name ?: permission,
                    )
                }
            }
            Spacer(Modifier.height(Dshm.spacing.xxl))
            SettingsSection(title = stringResource(R.string.room_settings_members)) {
                val memberRows = state.botRows.associateBy { it.id }
                info?.members?.forEachIndexed { index, id ->
                    if (index > 0) SettingsRowDivider()
                    val member = memberRows[id]
                    SettingsRow(
                        title = member?.name ?: id,
                        subtitle = if (id == info.admin) {
                            stringResource(R.string.room_settings_admin_badge)
                        } else {
                            member?.role
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
                        onClick = {
                            onDismiss()
                            onOpenMember(id)
                        },
                    )
                }
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
                        onClick = {
                            onDismiss()
                            onDetails()
                        },
                    )
                }
            }
            Spacer(Modifier.height(Dshm.spacing.xxl))
            SettingsSection(title = stringResource(R.string.bot_settings_usage)) {
                SettingsRow(
                    title = info?.name ?: row.name,
                    value = usageLabel(state.usageTokens, state.cacheHit),
                )
            }
            Spacer(Modifier.height(Dshm.spacing.xxl))
            SettingsSection(title = stringResource(R.string.bot_settings_page_title)) {
                SettingsRow(
                    title = stringResource(R.string.bot_settings_details),
                    onClick = {
                        onDismiss()
                        onDetails()
                    },
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.chat_copy_transcript),
                    onClick = onCopyTranscript,
                )
            }
        }
    }
}
