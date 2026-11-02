package com.dshm.bot.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/** 字体令牌：字号、行高、字重都从这里取。界面只负责选语义样式。 */
@Immutable
data class DshmTypography(
    val rowTitle: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    val rowPreview: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    val rowTime: TextStyle = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Normal),
    val screenTitle: TextStyle = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),

    /** 设置页等整页标题：比普通顶栏更大，和 44dp 圆钮视觉平衡。 */
    val pageTitle: TextStyle = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    val sheetTitle: TextStyle = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    val sheetAction: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    val emptyTitle: TextStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    val emptyHint: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal),
    /** 输入框正文（bodyLarge 16sp、行高 1.45 倍，与 dsh-Go 一致）。
     *  trim = None 让行高完整生效，光标也按完整行高显示（不会比 dsh-Go 短）。 */
    val composerText: TextStyle = TextStyle(
        fontSize = 16.sp,
        lineHeight = 23.2.sp,
        fontWeight = FontWeight.Normal,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    ),
)

val LocalDshmTypography = staticCompositionLocalOf { DshmTypography() }
