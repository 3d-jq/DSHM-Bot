package com.dshm.bot.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.abs

/**
 * 文字扫光：一道高光从左往右扫过文字，扫过的部分用 [highlightColor]，其余是 [baseColor]。
 *
 * 首页列表用它替代头像上的状态圆环（2026-11-14 用户要求）：干活中扫主色、
 * 等你批准扫警告色 —— 比静态圆环更能看出「它在动」。
 *
 * 实现：把 `TextStyle.color` 换成沿 X 轴移动的三段渐变（底色 → 高光 → 底色）。
 * 渐变带宽取文字宽度的 [BAND_RATIO]（有最小宽度，短名字也看得见），进度无限循环。
 * 文字宽度靠 `onTextLayout` 回填，所以高光刚好扫过名字本身，不会扫到空白区。
 */
@Composable
fun ShimmerText(
    text: String,
    style: TextStyle,
    baseColor: Color,
    highlightColor: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SWEEP_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-progress",
    )
    // 首次组合时还不知道宽度：先用 0（高光在左外侧），测量后下一帧就正常。
    var textWidth by remember { mutableFloatStateOf(0f) }
    val band = (textWidth * BAND_RATIO).coerceAtLeast(MIN_BAND_PX)
    // 高光中心从「左外侧」扫到「右外侧」：两端各留一个带宽，保证完整扫过。
    val center = -band + (textWidth + band * 2f) * progress
    val brush = Brush.horizontalGradient(
        colors = listOf(baseColor, highlightColor, baseColor),
        startX = center - band,
        endX = center + band,
    )
    BasicText(
        text = text,
        modifier = modifier,
        // copy 的 color / brush 是两个互斥重载：只传 brush（未指定 color 时 brush 生效）。
        style = style.copy(brush = brush),
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = { layout ->
            val measured = layout.size.width.toFloat()
            if (abs(measured - textWidth) > 0.5f) textWidth = measured
        },
    )
}

/** 一轮扫光的时长：慢一点才像「呼吸」，太快会显得躁。 */
private const val SWEEP_MS = 1_800

/** 高光带占文字宽度的比例。 */
private const val BAND_RATIO = 0.6f

/** 高光带最小宽度（px）：短名字也要能看见一道光扫过。 */
private const val MIN_BAND_PX = 48f
