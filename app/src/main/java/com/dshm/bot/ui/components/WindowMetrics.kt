package com.dshm.bot.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp

/**
 * 当前窗口的可用高度（dp）。
 *
 * 替代 `LocalConfiguration.current.screenHeightDp`：后者读的是**屏幕**配置，
 * 折叠屏 / 分屏 / 自由窗口下会给出整个显示区的高度，而不是这个窗口的实际高度，
 * 弹层按它算高度就会溢出或留白。`LocalWindowInfo.containerSize` 是窗口尺寸，
 * 配合当前 density 转 dp。
 */
@Composable
internal fun rememberWindowHeightDp(): Dp = with(LocalDensity.current) {
    LocalWindowInfo.current.containerSize.height.toDp()
}