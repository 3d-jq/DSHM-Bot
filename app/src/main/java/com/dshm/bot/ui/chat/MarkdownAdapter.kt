package com.dshm.bot.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.dshm.bot.core.design.Dshm
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownTypography

/**
 * Markdown 渲染器与我们的设计令牌之间的桥。
 *
 * 渲染交给 `com.mikepenz:multiplatform-markdown-renderer-m3`（底层是 Kotlin 官方的
 * `org.jetbrains:markdown`，GFM 规范，表格 / 对齐 / 删除线都支持），
 * 这里只负责把颜色和字号映射成我们自己的设计令牌。
 */
@Composable
internal fun rememberMarkdownColors(): MarkdownColors {
    val colors = Dshm.colors
    return remember(colors) {
        object : MarkdownColors {
            override val text: Color = colors.textPrimary
            override val codeText: Color = colors.textPrimary
            override val inlineCodeText: Color = colors.textPrimary
            override val linkText: Color = colors.accent
            override val codeBackground: Color = colors.surfaceMuted
            override val inlineCodeBackground: Color = colors.surfaceMuted
            override val dividerColor: Color = colors.divider
            override val tableText: Color = colors.textPrimary
            override val tableBackground: Color = colors.surfaceMuted
        }
    }
}

/**
 * 排版：正文用聊天字号（`composerText`，16sp），标题按层级放大，
 * 代码用等宽字体。
 */
@Composable
internal fun rememberMarkdownTypography(fontScale: Float): MarkdownTypography {
    val typography = Dshm.typography
    val palette = Dshm.colors
    val base = typography.composerText.copy(
        fontSize = typography.composerText.fontSize * fontScale,
        lineHeight = typography.composerText.lineHeight * fontScale,
        color = palette.textPrimary,
    )
    val accent = palette.accent
    val secondary = palette.textSecondary
    return remember(base, accent, secondary) {
        object : MarkdownTypography {
            override val text: TextStyle = base
            override val code: TextStyle = base.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = base.fontSize * 0.9f,
            )
            override val inlineCode: TextStyle = base.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = base.fontSize * 0.9f,
            )
            override val h1: TextStyle = base.copy(
                fontSize = base.fontSize * 1.4f,
                lineHeight = base.lineHeight * 1.4f,
                fontWeight = FontWeight.Bold,
            )
            override val h2: TextStyle = base.copy(
                fontSize = base.fontSize * 1.28f,
                lineHeight = base.lineHeight * 1.28f,
                fontWeight = FontWeight.Bold,
            )
            override val h3: TextStyle = base.copy(
                fontSize = base.fontSize * 1.18f,
                lineHeight = base.lineHeight * 1.18f,
                fontWeight = FontWeight.SemiBold,
            )
            override val h4: TextStyle = base.copy(
                fontSize = base.fontSize * 1.1f,
                lineHeight = base.lineHeight * 1.1f,
                fontWeight = FontWeight.SemiBold,
            )
            override val h5: TextStyle = base.copy(fontWeight = FontWeight.SemiBold)
            override val h6: TextStyle = base.copy(color = secondary)
            override val quote: TextStyle = base.copy(color = secondary)
            override val paragraph: TextStyle = base
            override val ordered: TextStyle = base
            override val bullet: TextStyle = base
            override val list: TextStyle = base
            override val link: TextStyle = base.copy(color = accent)
            override val textLink: TextLinkStyles = TextLinkStyles(
                style = SpanStyle(color = accent),
            )
            override val table: TextStyle = base
        }
    }
}
