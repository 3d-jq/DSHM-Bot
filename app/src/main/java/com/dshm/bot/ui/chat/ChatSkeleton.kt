package com.dshm.bot.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dshm.bot.core.design.Dshm

/**
 * 对话首屏骨架：几条左右交替的「气泡」，带缓慢呼吸的透明度。
 *
 * 打开对话要等 `chat` / `room-chat` 回来，中间一颗转圈会显得空；先给出对话的形状。
 */
@Composable
fun ChatSkeleton(
    modifier: Modifier = Modifier,
    isGroup: Boolean = false,
    /**
     * 顶栏（悬浮名字胶囊那块）的高度：内容区是 edge-to-edge 的，消息列表靠
     * `contentPadding.top = topPadding + md` 让开它，骨架屏必须用同一个值。
     */
    topPadding: Dp = 0.dp,
) {
    val transition = rememberInfiniteTransition(label = "chat-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.62f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "chat-skeleton-alpha",
    )
    val ink = Dshm.colors.textPrimary
    val bubble = ink.copy(alpha = alpha * 0.16f)
    val line = ink.copy(alpha = alpha * 0.20f)

    // 左（Bot）/ 右（用户）交替，宽度也错开，看起来像一段真实对话。
    val rows = listOf(
        true to 0.64f,
        false to 0.46f,
        true to 0.74f,
        true to 0.56f,
        false to 0.52f,
        true to 0.68f,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = Dshm.spacing.lg,
                end = Dshm.spacing.lg,
                top = topPadding + Dshm.spacing.md,
                bottom = Dshm.spacing.lg,
            ),
        verticalArrangement = Arrangement.spacedBy(Dshm.spacing.turnGap),
    ) {
        rows.forEachIndexed { index, (fromBot, widthFraction) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (fromBot) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.Top,
            ) {
                if (fromBot && isGroup) {
                    Box(
                        modifier = Modifier
                            .size(Dshm.spacing.lg + Dshm.spacing.xxs)
                            .clip(CircleShape)
                            .background(bubble),
                    )
                    Spacer(Modifier.width(Dshm.spacing.sm))
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth(widthFraction)
                        .clip(RoundedCornerShape(Dshm.radius.lg))
                        .background(bubble)
                        .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xs),
                ) {
                    SkeletonLine(line, 1f)
                    SkeletonLine(line, if (index % 2 == 0) 0.88f else 0.72f)
                    if (index % 2 == 0) SkeletonLine(line, 0.58f)
                }
            }
        }
    }
}

/** 气泡里的一条假文字行。 */
@Composable
private fun SkeletonLine(color: Color, widthFraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(10.dp)
            .clip(RoundedCornerShape(Dshm.radius.xs))
            .background(color),
    )
}
