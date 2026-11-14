package com.dshm.bot.ui.launch

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import kotlinx.coroutines.delay

/**
 * 冷启动的启动画面（对齐 dsh-Go 的 `LaunchOverlay` + `LaunchMotion`）：
 *
 * ```
 * 0 ms        图标在系统启动画面的位置
 * 0–640       落定：缩到 0.8，上移给字标让位
 * 200–640     字标「DSHM Bot」淡入并自下升起
 * 560–1360    一道细光自左向右扫过图标一次
 * 引擎就绪 / 1360 ms  整层 300 ms 淡出（不额外等动画）
 * ```
 *
 * 轻点屏幕随时跳过；系统关了动画时只留静止图标与字标。每个进程只播一次
 * （Activity 重建、转屏不重播）。
 */
@Composable
fun LaunchOverlay(
    engineReady: Boolean,
    reduceMotion: Boolean,
    onFinished: () -> Unit = {},
) {
    var visible by remember { mutableStateOf(!LaunchSession.consumed) }
    if (!visible) return

    val ready by rememberUpdatedState(engineReady)
    var elapsedMs by remember { mutableFloatStateOf(0f) }
    var skipped by remember { mutableStateOf(false) }
    val fade = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        LaunchSession.consumed = true
        val start = withFrameMillis { it }
        while (true) {
            withFrameMillis { now -> elapsedMs = (now - start).toFloat() }
            if (reduceMotion) break
            if (skipped) break
            if (elapsedMs >= TOTAL_MS) break
            // 引擎已经就绪就提前收，但至少留 [MIN_HOLD_MS] 让动画站得住。
            if (ready && elapsedMs >= MIN_HOLD_MS) break
        }
        if (reduceMotion) delay(180)
        fade.animateTo(0f, tween(300, easing = LinearEasing))
        visible = false
        onFinished()
    }

    val ms = if (reduceMotion) TOTAL_MS.toFloat() else elapsedMs
    val settle = if (reduceMotion) 1f else SettleCurve.transform(progress(ms, 0, 640))
    val wordT = if (reduceMotion) 1f else WordCurve.transform(progress(ms, 200, 640))
    val sweep = if (reduceMotion) 0f else progress(ms, 560, 1360)
    // 系统启动画面交到我们手里时图标大小并不完全一样：前 150ms 淡入，接缝不至于像硬切。
    val enter = progress(ms, 0, 150)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value * enter }
            .background(Dshm.colors.background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { skipped = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(ICON_SIZE)
                .graphicsLayer {
                    // 起始尺寸 = Android 12 系统启动画面里那颗图标的尺寸（288dp 画布），
                    // 交接时看不出换图；随后收到 0.62，给字标让位。
                    val scale = 1f - (1f - SETTLED_SCALE) * settle
                    scaleX = scale
                    scaleY = scale
                    translationY = -RISE.toPx() * settle
                }
                // 图标圆角沿用启动画面里那颗图的形状（dsh-Go LaunchMotion 同款），
                // 无对应令牌，保留字面量。
                .clip(RoundedCornerShape(30.dp)),
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            if (sweep > 0f && sweep < 1f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            drawContent()
                            val band = size.width * 0.45f
                            val x = -band + (size.width + band * 2f) * sweep
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.White.copy(alpha = 0.45f),
                                        Color.Transparent,
                                    ),
                                    start = Offset(x, 0f),
                                    end = Offset(x + band, size.height),
                                ),
                            )
                        },
                )
            }
        }
        Text(
            text = stringResourceAppName(),
            style = Dshm.typography.pageTitle.copy(
                // 启动画面字标的专用字号（对齐 dsh-Go LaunchMotion），无对应令牌，
                // 保留字面量。
                fontSize = 28.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = Dshm.colors.textPrimary,
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    alpha = wordT
                    translationY = WORDMARK_Y.toPx() + (1f - wordT) * 10.dp.toPx()
                },
        )
    }
}

/** 进程级：启动画面只播一次。 */
private object LaunchSession {
    var consumed = false
}

@Composable
private fun stringResourceAppName(): String =
    androidx.compose.ui.res.stringResource(R.string.app_name)

private fun progress(ms: Float, start: Int, end: Int): Float =
    ((ms - start) / (end - start)).coerceIn(0f, 1f)

private val SettleCurve = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
private val WordCurve = CubicBezierEasing(0f, 0f, 0.2f, 1f)

private const val TOTAL_MS = 1_360
private const val MIN_HOLD_MS = 650f

/** 图标画布：Android 12 系统启动画面是 288dp，里头图标占 276dp（dsh-Go 同款）。 */
private val ICON_SIZE = 276.dp

private const val SETTLED_SCALE = 0.62f

/** 图标上移给字标让位的距离：(gap 12 + 字标 36) / 2。 */
private val RISE = 24.dp

/** 字标在屏幕中心下方多远：-rise + 半个落定后的图标（276×0.62/2）+ gap + 半个字标。 */
private val WORDMARK_Y = 91.6.dp
