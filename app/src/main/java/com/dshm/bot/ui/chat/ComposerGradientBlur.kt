package com.dshm.bot.ui.chat

import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Dp

/**
 * 输入框底部的渐变模糊：照对话顶栏那层 `Brush.verticalGradient` 做 alpha 遮罩，
 * 但遮的是**消息内容的模糊副本**，不是一层白色。
 *
 * 顶部透明（内容清晰）、越往下模糊副本越浓、物理底边最浓 —— 小白条压在模糊内容上，
 * 不会出现「小白条下面没覆盖」或一层死白把字裁掉。
 *
 * 均匀模糊用旧的 `BlurEffect`（等价于 `Modifier.blur`，API 31+ 生效），
 * 不升级 Compose BOM，也不用今年的 `BlurRadiusSpec`。低版本退化为顶栏同款纯渐变。
 */
internal fun Modifier.composerGradientBlur(
    height: Dp,
    radius: Dp,
    background: Color,
): Modifier = drawWithCache {
    val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val source = obtainGraphicsLayer()
    val veil = obtainGraphicsLayer()
    val veilTop = (size.height - height.toPx()).coerceAtLeast(0f)
    val mask = Brush.verticalGradient(
        0f to Color.Transparent,
        0.3f to Color.White.copy(alpha = 0.72f),
        1f to Color.White,
        startY = veilTop,
        endY = size.height,
    )
    if (blurSupported) {
        source.renderEffect = BlurEffect(radius.toPx(), radius.toPx(), TileMode.Clamp)
        veil.compositingStrategy = CompositingStrategy.Offscreen
    }

    onDrawWithContent {
        drawContent()
        if (!blurSupported) {
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.3f to background.copy(alpha = 0.78f),
                    1f to background.copy(alpha = 0.92f),
                    startY = veilTop,
                    endY = size.height,
                ),
                topLeft = Offset(0f, veilTop),
                size = Size(size.width, size.height - veilTop),
            )
            return@onDrawWithContent
        }
        clipRect(top = veilTop) {
            source.record { this@onDrawWithContent.drawContent() }
            veil.record {
                drawLayer(source)
                drawRect(mask, blendMode = BlendMode.DstIn)
            }
            drawLayer(veil)
        }
    }
}
