package com.dshm.bot.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import coil.compose.SubcomposeAsyncImage
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.domain.model.AvatarGlyph
import com.dshm.bot.domain.model.BotInks

/**
 * Bot 头像：优先自定义照片，其次 DS Bot 的角色形状（whale / star / moon / …），
 * 群聊用群组图标。形状和颜色都由 Bot id 决定，和 DS Bot 客户端一致。
 */
@Composable
fun BotAvatar(
    inkId: String?,
    glyph: AvatarGlyph,
    modifier: Modifier = Modifier,
    size: Dp = Dshm.spacing.avatar,
    avatarImage: String? = null,
    avatarShape: String? = null,
) {
    val colors = Dshm.colors
    val shape = avatarShape
    // 默认头像（没指定形状的 Bot）就是鲸鱼：走矢量绘制，**不用**那张自带白底的 PNG ——
    // PNG 的白圆在深色主题下不会跟着变，头像会一直是白的。
    val key = shape?.takeIf { it.isNotEmpty() }
        ?: if (glyph == AvatarGlyph.BOT) "whale" else null
    val drawing = key != null && BotShapeSpecs.all.containsKey(key)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                when {
                    avatarImage != null -> Color(BotInks.color(inkId, colors.isDark))
                    // 所有角色头像统一白底圆圈，和 DS Bot 的白底鲸鱼头像同一套观感。
                    drawing -> colors.surface
                    else -> Color(BotInks.color(inkId, colors.isDark))
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            avatarImage != null -> SubcomposeAsyncImage(
                model = avatarImage,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                loading = {},
                error = {
                    Icon(
                        painter = painterResource(glyph.drawable()),
                        contentDescription = null,
                        tint = colors.onInk,
                        modifier = Modifier.size(Dshm.spacing.avatarGlyph),
                    )
                },
            )

            drawing -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(size * 0.08f),
                contentAlignment = Alignment.Center,
            ) {
                ShapeAvatar(
                    shape = key ?: BotShapeSpecs.FALLBACK,
                    inkId = inkId,
                )
            }

            else -> Icon(
                painter = painterResource(glyph.drawable()),
                contentDescription = null,
                tint = colors.onInk,
                modifier = Modifier.size(Dshm.spacing.avatarGlyph),
            )
        }
    }
}

/** 把 DS Bot 的 100×100 SVG 角色画到 Compose Canvas 上（身体、阴影、高光、眼睛）。 */
@Composable
private fun ShapeAvatar(shape: String, inkId: String?) {
    val spec = BotShapeSpecs.all[shape] ?: BotShapeSpecs.all[BotShapeSpecs.FALLBACK] ?: return
    val body = remember(spec.body) { spec.body.path() }
    val shade = remember(spec.shade) { spec.shade?.path() }
    val light = remember(spec.light) { spec.light?.path() }
    val paints = remember(spec.paints) {
        spec.paints.map { DrawnPaint(it.path.path(), it.color.toColor(), it.alpha) }
    }
    val ink = Color(BotInks.color(inkId, Dshm.colors.isDark))

    Canvas(modifier = Modifier.fillMaxSize()) {
        val factor = this.size.minDimension / 100f
        scale(factor, factor, pivot = Offset.Zero) {
            drawPath(body, ink)
            paints.forEach { paint ->
                drawPath(paint.path, paint.color.copy(alpha = paint.alpha))
            }
            shade?.let { drawPath(it, Color.Black.copy(alpha = 0.14f)) }
            light?.let { drawPath(it, Color.White.copy(alpha = 0.16f)) }
            spec.eyes.forEach { eye ->
                if (eye.size < 5) return@forEach
                rotate(degrees = eye[4], pivot = Offset(eye[0], eye[1])) {
                    drawRoundRect(
                        color = Color.Black,
                        topLeft = Offset(eye[0] - eye[2] / 2f, eye[1] - eye[3] / 2f),
                        size = Size(eye[2], eye[3]),
                        cornerRadius = CornerRadius(eye[2] / 2f),
                    )
                }
            }
        }
    }
}

private fun String.path(): Path = PathParser().apply { parsePathString(this@path) }.toPath()

private data class DrawnPaint(val path: Path, val color: Color, val alpha: Float)

private fun String.toColor(): Color = runCatching {
    Color(android.graphics.Color.parseColor(this))
}.getOrDefault(Color.Black)

@DrawableRes
private fun AvatarGlyph.drawable(): Int = when (this) {
    AvatarGlyph.BOT -> R.drawable.ic_lucide_bot
    AvatarGlyph.WORK -> R.drawable.ic_lucide_briefcase
    AvatarGlyph.BOOK -> R.drawable.ic_lucide_book_open
    AvatarGlyph.CODE -> R.drawable.ic_lucide_code
    AvatarGlyph.SEARCH -> R.drawable.ic_lucide_search
    AvatarGlyph.GROUP -> R.drawable.ic_lucide_users
    AvatarGlyph.SPARK -> R.drawable.ic_lucide_sparkles
}
