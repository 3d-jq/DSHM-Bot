package com.dshm.bot.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import coil.compose.SubcomposeAsyncImage
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.domain.model.AvatarGlyph
import com.dshm.bot.domain.model.BotInks

/**
 * Bot 头像：优先 DS Bot 自定义头像（data URL），其次 DS Bot 角色形状，最后才是
 * 颜色 + Lucide 占位图标。
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
    // DS Bot 自带的鲸鱼头像已经做成圆形白底，不再垫颜色（否则会多出一圈蓝边）。
    val dsIcon = avatarShape != null || glyph == AvatarGlyph.BOT
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (dsIcon) Color.Transparent else Color(BotInks.color(inkId, colors.isDark)),
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
            // DS Bot 自带的圆形头像：Bot 没有自定义照片/角色时直接用它。
            avatarShape != null || glyph == AvatarGlyph.BOT -> Image(
                painter = painterResource(R.drawable.ic_dsbot_avatar),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            else -> Icon(
                painter = painterResource(glyph.drawable()),
                contentDescription = null,
                tint = colors.onInk,
                modifier = Modifier.size(Dshm.spacing.avatarGlyph),
            )
        }
    }
}

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
