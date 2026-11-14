package com.dshm.bot.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.domain.model.AvatarGlyph
import com.dshm.bot.ui.components.BotAvatar
import com.dshm.bot.ui.home.RoomMemberUi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput

private val FACE = 20.dp
private val FACE_OVERLAP = 14.dp

/**
 * 群聊输入框上方的成员 dock（网页版 `MemberDock`，`client.js:15591`）。
 *
 * 谁在忙、谁卡在等你批准，都收成这一颗常驻的小胶囊：左边是成员头像堆叠，
 * 右边一句摘要。点开是群详情（网页版 `open: { details: roomId }`）。
 *
 * 跟待办卡不冲突：正在这个群里发言的人不出现（对话里已经看得到他），
 * 被中转上限暂停的群也不会有人处于 `work`。
 */
@Composable
fun MemberDockCard(
    members: List<RoomMemberUi>,
    status: Map<String, RoomMemberStatus>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 单独停下某个成员在群里的工作（`stop-member-work`）。
     *
     * 入口选了「**长按忙成员的头像**」：dock 本身就是群成员面板（谁在忙一眼可见），
     * 头像是最直接的对象；长按不抢点击（点仍打开群详情），且只对 `work` 状态的成员
     * 弹菜单，避免误停正在等你批准的成员。
     */
    onStopMember: (RoomMemberUi) -> Unit = {},
) {
    val busy = remember(members, status) {
        members.mapNotNull { member ->
            val state = status[member.id] ?: return@mapNotNull null
            if (state.state == "idle") return@mapNotNull null
            // 网页版 `dockMembers`：在本群、而且正在说话的人不画进 dock。
            if (state.state == "work" && state.here && state.speaking) return@mapNotNull null
            member to state
        }.sortedBy { if (it.second.state == "ask") 0 else 1 }
    }
    if (busy.isEmpty()) return

    val asks = busy.filter { it.second.state == "ask" }
    val workers = busy.filter { it.second.state == "work" }
    val waiting = when (asks.size) {
        0 -> ""
        1 -> stringResource(R.string.chat_member_dock_waiting_one, asks[0].first.name)
        2 -> stringResource(
            R.string.chat_member_dock_waiting_two,
            asks[0].first.name,
            asks[1].first.name,
        )
        else -> stringResource(
            R.string.chat_member_dock_waiting_more,
            asks[0].first.name,
            asks[1].first.name,
            asks.size - 2,
        )
    }
    val doing = workers.firstOrNull()?.let { (member, state) ->
        val phrase = state.doing.orEmpty().ifEmpty { member.role.orEmpty() }
        val more = if (workers.size > 1) " +${workers.size - 1}" else ""
        stringResource(R.string.chat_member_dock_doing, member.name, phrase) + more
    }.orEmpty()

    val shape = RoundedCornerShape(Dshm.radius.pill)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = Dshm.spacing.xs),
        horizontalArrangement = Arrangement.Start,
    ) {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(Dshm.colors.surface)
                .border(Dshm.spacing.hairline, Dshm.colors.divider, shape)
                .clickable(onClick = onOpen)
                .padding(horizontal = Dshm.spacing.sm, vertical = Dshm.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.xs),
        ) {
            Row {
                // 长按菜单的锚点：哪个成员的头像被长按（只对 work 状态开菜单）。
                var menuFor by remember { mutableStateOf<String?>(null) }
                busy.take(4).forEachIndexed { index, (member, state) ->
                    val ask = state.state == "ask"
                    val working = state.state == "work"
                    Box {
                        Box(
                            modifier = Modifier
                                .offset(x = if (index == 0) 0.dp else FACE_OVERLAP)
                                .size(FACE)
                                .clip(CircleShape)
                                .background(Dshm.colors.surface)
                                .border(
                                    width = 1.5.dp,
                                    color = if (ask) Dshm.colors.warning else Dshm.colors.surface,
                                    shape = CircleShape,
                                )
                                // 只挂长按、不消费单击：点仍落到外层胶囊（打开群详情）。
                                .pointerInput(member.id, working) {
                                    if (!working) return@pointerInput
                                    detectTapGestures(onLongPress = { menuFor = member.id })
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            BotAvatar(
                                inkId = member.inkId,
                                glyph = AvatarGlyph.BOT,
                                avatarImage = member.avatarImage,
                                avatarShape = member.avatarShape,
                                size = FACE - 5.dp,
                            )
                        }
                        DropdownMenu(
                            expanded = menuFor == member.id,
                            onDismissRequest = { menuFor = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_member_stop, member.name)) },
                                onClick = {
                                    menuFor = null
                                    onStopMember(member)
                                },
                            )
                        }
                    }
                }
            }
            Text(
                text = listOf(waiting, doing).filter { it.isNotEmpty() }.joinToString(" · "),
                style = Dshm.typography.rowTime,
                color = if (asks.isNotEmpty()) Dshm.colors.warning else Dshm.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
