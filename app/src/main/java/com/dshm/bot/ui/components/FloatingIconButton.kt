package com.dshm.bot.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import com.dshm.bot.core.design.Dshm

/**
 * 悬浮圆钮：填充色与页面底色一致，四周阴影让它在页面上浮起来。
 * 主界面顶栏与对话页顶栏共用这一套样式，保证视觉一致。
 */
@Composable
fun FloatingIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(Dshm.spacing.iconButton)
            .shadow(
                elevation = Dshm.spacing.floatingElevation,
                shape = CircleShape,
                ambientColor = Dshm.colors.shadow,
                spotColor = Dshm.colors.shadow,
            )
            .clip(CircleShape)
            // 深色主题下 `surface` 跟页面底色几乎一样，圆钮会「没有形状」；
            // 用专门的悬浮填充色 + 一圈描边，两种主题都能看出这是一颗按钮。
            .background(Dshm.colors.floatingSurface)
            .border(Dshm.spacing.hairline, Dshm.colors.divider, CircleShape)
            .then(
                if (enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = if (enabled) Dshm.colors.iconTint else Dshm.colors.textTertiary,
            modifier = Modifier.size(Dshm.spacing.topBarIcon),
        )
    }
}
