package com.dshm.bot.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dshm.bot.R
import com.dshm.bot.domain.model.PetSize
import com.dshm.bot.domain.model.PetStyle
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private data class PetFrame(val index: Int, val durationMs: Int)

private data class PetAnimation(
    @DrawableRes val sheet: Int,
    val frameW: Int,
    val frameH: Int,
    val cols: Int,
    val frames: List<PetFrame>,
)

private val deepyIdle = PetAnimation(
    sheet = R.drawable.pet_deepy_idle,
    frameW = 180,
    frameH = 120,
    cols = 21,
    frames = (0..41).map { PetFrame(it, 50) },
)

private val whaleGirlIdle = PetAnimation(
    sheet = R.drawable.pet_girl_idle,
    frameW = 52,
    frameH = 49,
    cols = 13,
    frames = listOf(
        PetFrame(0, 700), PetFrame(1, 280), PetFrame(2, 280), PetFrame(3, 240),
        PetFrame(4, 360), PetFrame(5, 60), PetFrame(6, 90), PetFrame(7, 60),
        PetFrame(0, 650), PetFrame(8, 280), PetFrame(9, 280), PetFrame(10, 240),
        PetFrame(11, 420), PetFrame(12, 60), PetFrame(6, 80), PetFrame(7, 60),
        PetFrame(0, 140), PetFrame(7, 60), PetFrame(6, 80), PetFrame(7, 60),
        PetFrame(0, 800),
    ),
)

private fun PetStyle.animation(): PetAnimation = when (this) {
    PetStyle.DEEPY_WHALE -> deepyIdle
    PetStyle.WHALE_GIRL -> whaleGirlIdle
}

/** dsh-Go 宠物包里的 idle 精灵图；按原帧序列播放，像素风不做平滑过滤。 */
@Composable
fun PetSprite(
    style: PetStyle,
    width: Dp,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val animation = remember(style) { style.animation() }
    val sheet = ImageBitmap.imageResource(animation.sheet)
    var frameIndex by remember(style) { mutableIntStateOf(animation.frames.first().index) }
    LaunchedEffect(style, reduceMotion) {
        if (reduceMotion) {
            frameIndex = animation.frames.first().index
            return@LaunchedEffect
        }
        var index = 0
        while (true) {
            frameIndex = animation.frames[index].index
            delay(animation.frames[index].durationMs.toLong())
            index = (index + 1) % animation.frames.size
        }
    }
    val aspect = animation.frameW.toFloat() / animation.frameH
    Canvas(modifier.size(width = width, height = width / aspect)) {
        val col = frameIndex % animation.cols
        val row = frameIndex / animation.cols
        drawImage(
            image = sheet,
            srcOffset = IntOffset(col * animation.frameW, row * animation.frameH),
            srcSize = IntSize(animation.frameW, animation.frameH),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.None,
        )
    }
}

/** App 内宠物悬浮层（dsh-Go 的系统级悬浮窗还没有对应原生桥接）。 */
@Composable
fun PetOverlay(
    style: PetStyle,
    size: PetSize,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val width = when (size) {
        PetSize.SMALL -> 56.dp
        PetSize.MEDIUM -> 84.dp
        PetSize.LARGE -> 112.dp
    }
    PetSprite(style = style, width = width, reduceMotion = reduceMotion, modifier = modifier)
}
