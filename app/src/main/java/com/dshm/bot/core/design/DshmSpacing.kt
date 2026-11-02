package com.dshm.bot.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 间距令牌：所有 padding / 尺寸都从这里取，界面里不写裸 dp 数字。 */
@Immutable
data class DshmSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 32.dp,
    val topBarHeight: Dp = 60.dp,
    val avatar: Dp = 44.dp,
    val avatarGlyph: Dp = 20.dp,
    val icon: Dp = 24.dp,
    val topBarIcon: Dp = 20.dp,
    val iconButton: Dp = 44.dp,
    val rowMinHeight: Dp = 64.dp,
    val listBottomPadding: Dp = 24.dp,
    /** 顶栏悬浮按钮的阴影高度。 */
    val floatingElevation: Dp = 10.dp,
)

val LocalDshmSpacing = staticCompositionLocalOf { DshmSpacing() }
