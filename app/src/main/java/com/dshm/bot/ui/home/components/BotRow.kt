package com.dshm.bot.ui.home.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.ui.components.BotAvatar
import com.dshm.bot.ui.chat.RoomAvatar
import com.dshm.bot.ui.home.ChatRowUi

/** 主界面列表行：头像、名字、时间、最后一句。点击进入 Bot 对话。 */
@Composable
fun BotRow(
    row: ChatRowUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dshm.spacing.rowMinHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.isGroup) {
            RoomAvatar(members = row.roomMembers)
        } else {
            BotAvatar(
                inkId = row.inkId,
                glyph = row.glyph,
                avatarImage = row.avatarImage,
                avatarShape = row.avatarShape,
            )
        }
        Spacer(Modifier.width(Dshm.spacing.md))
        // 文字列与头像中线对齐（行级 CenterVertically）；名字与预览之间留 4dp 间隙。
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.name,
                    style = Dshm.typography.rowTitle,
                    color = Dshm.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Dshm.spacing.sm))
                Text(
                    text = row.timeLabel,
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.textTertiary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(Dshm.spacing.xs))
            Text(
                text = row.preview,
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
