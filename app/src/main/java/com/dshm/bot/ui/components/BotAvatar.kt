package com.dshm.bot.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.domain.model.AvatarGlyph
import com.dshm.bot.domain.model.BotInks

/**
 * 头像占位：DS Bot 的 Bot 颜色 + 一个 Lucide 图标。
 * 真实头像（角色图 / 用户照片）接入后替换图标层即可，颜色算法保持不变。
 */
@Composable
fun BotAvatar(
    inkId: String?,
    glyph: AvatarGlyph,
    modifier: Modifier = Modifier,
    size: Dp = Dshm.spacing.avatar,
) {
    val colors = Dshm.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(BotInks.color(inkId, colors.isDark))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(glyph.drawable()),
            contentDescription = null,
            tint = colors.onInk,
            modifier = Modifier.size(Dshm.spacing.avatarGlyph),
        )
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
