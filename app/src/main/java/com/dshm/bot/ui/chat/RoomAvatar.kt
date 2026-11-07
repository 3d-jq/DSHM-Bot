package com.dshm.bot.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.domain.model.AvatarGlyph
import com.dshm.bot.ui.components.BotAvatar
import com.dshm.bot.ui.home.RoomMemberUi

/** DS Bot `RoomAvatar`：群头像实际是成员头像的堆叠，最多四张。 */
@Composable
fun RoomAvatar(
    members: List<RoomMemberUi>,
    modifier: Modifier = Modifier,
    size: Dp = Dshm.spacing.avatar,
) {
    val shown = members.take(4)
    val overflow = (members.size - shown.size).coerceAtLeast(0)
    Box(modifier = modifier.size(size)) {
        if (shown.isEmpty()) {
            BotAvatar(
                inkId = null,
                glyph = AvatarGlyph.GROUP,
                size = size,
            )
            return@Box
        }
        shown.forEachIndexed { index, member ->
            val (x, y, face) = roomSeat(shown.size, size, index)
            if (overflow > 0 && index == shown.lastIndex) {
                Box(
                    modifier = Modifier
                        .offset(x = x, y = y)
                        .size(face)
                        .clip(CircleShape)
                        .background(Dshm.colors.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "+${overflow + 1}",
                        style = Dshm.typography.rowTime,
                        color = Dshm.colors.textTertiary,
                    )
                }
            } else {
                BotAvatar(
                    inkId = member.inkId,
                    glyph = AvatarGlyph.BOT,
                    avatarImage = member.avatarImage,
                    avatarShape = member.avatarShape,
                    size = face,
                    modifier = Modifier.offset(x = x, y = y),
                )
            }
        }
    }
}

private data class RoomSeat(val x: Dp, val y: Dp, val face: Dp)

/** DS Bot `clusterSeats`：两成员用 2/3 大圆重叠，三四成员按三角 / 四宫格排。 */
private fun roomSeat(count: Int, frame: Dp, index: Int): RoomSeat = when (count) {
    1 -> RoomSeat(0.dp, 0.dp, frame)
    2 -> {
        val face = frame * 2f / 3f
        if (index == 0) RoomSeat(0.dp, 0.dp, face)
        else RoomSeat(frame / 3f, frame / 3f, face)
    }
    3 -> {
        val face = frame * 5f / 9f
        val rest = frame - face
        when (index) {
            0 -> RoomSeat(rest / 2f, 0.dp, face)
            1 -> RoomSeat(0.dp, rest, face)
            else -> RoomSeat(rest, rest, face)
        }
    }
    else -> {
        val face = frame / 2f
        when (index) {
            0 -> RoomSeat(0.dp, 0.dp, face)
            1 -> RoomSeat(face, 0.dp, face)
            2 -> RoomSeat(0.dp, face, face)
            else -> RoomSeat(face, face, face)
        }
    }
}
