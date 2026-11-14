package com.dshm.bot.ui.home.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.ui.components.BotAvatar
import com.dshm.bot.ui.components.ShimmerText
import com.dshm.bot.ui.chat.RoomAvatar
import com.dshm.bot.ui.home.ChatRowUi

/** 主界面列表行：头像、名字、时间、最后一句。点击进入 Bot 对话。 */
@Composable
fun BotRow(
    row: ChatRowUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 长按：把这一行在窗口里的位置交出去，菜单由页面统一画（弹窗的点击落点不可靠）。 */
    onLongPress: (androidx.compose.ui.geometry.Rect) -> Unit = {},
) {
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dshm.spacing.rowMinHeight)
            .onGloballyPositioned { coordinates -> bounds = coordinates.boundsInWindow() }
            .combinedClickable(
                onClick = onClick,
                onLongClick = { onLongPress(bounds) },
            )
            .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 状态不再用头像圆环：改成名字上的扫光（干活中=主色、等你批准=警告色）。
        val shimmer = shimmerTintFor(row.activity, Dshm.colors.accent, Dshm.colors.warning)
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
                if (shimmer != null) {
                    ShimmerText(
                        text = row.name,
                        style = Dshm.typography.rowTitle,
                        baseColor = Dshm.colors.textPrimary,
                        highlightColor = shimmer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        text = row.name,
                        style = Dshm.typography.rowTitle,
                        color = Dshm.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.pinned) {
                    Spacer(Modifier.width(Dshm.spacing.xxs))
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_pin),
                        contentDescription = null,
                        tint = Dshm.colors.textTertiary,
                        modifier = Modifier.width(Dshm.spacing.md),
                    )
                }
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

/**
 * 行状态的扫光色：干活中 = 主色，等你批准 = 警告色，其它状态不扫。
 *
 * 抽成纯函数是为了能在 JVM 单测里锁住「哪个状态用哪个色」；
 * 头像圆环已随同一轮改动移除（2026-11-14 用户要求改成名字扫光）。
 */
internal fun shimmerTintFor(activity: String?, accent: Color, warning: Color): Color? = when (activity) {
    "running" -> accent
    "waiting" -> warning
    else -> null
}
