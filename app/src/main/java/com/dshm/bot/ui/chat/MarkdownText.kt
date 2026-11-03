package com.dshm.bot.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.core.design.DshmColors

/** Markdown 块级节点（够聊天回复用：标题、段落、列表、引用、代码块、分隔线）。 */
internal sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class ListItem(val text: String, val number: Int? = null) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
    data object Divider : MarkdownBlock
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
private val NUMBERED = Regex("^\\s*(\\d+)[.)]\\s+(.*)$")

/** 把模型回复切成块；解析失败或未知语法按段落保留，绝不吞文本。 */
internal fun parseMarkdown(source: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = source.replace("\r\n", "\n").split('\n')
    val paragraph = StringBuilder()

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        if (text.isNotEmpty()) blocks += MarkdownBlock.Paragraph(text)
        paragraph.setLength(0)
    }

    var index = 0
    while (index < lines.size) {
        val raw = lines[index]
        val line = raw.trimEnd()
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                flushParagraph()
                index += 1
                val code = StringBuilder()
                while (index < lines.size && !lines[index].trim().startsWith("```")) {
                    code.appendLine(lines[index])
                    index += 1
                }
                if (index < lines.size) index += 1
                blocks += MarkdownBlock.Code(code.toString().trimEnd())
            }

            trimmed.isEmpty() -> {
                flushParagraph()
                index += 1
            }

            HEADING.matches(trimmed) -> {
                flushParagraph()
                val match = HEADING.find(trimmed)!!
                blocks += MarkdownBlock.Heading(
                    level = match.groupValues[1].length,
                    text = match.groupValues[2],
                )
                index += 1
            }

            BULLET.matches(line) -> {
                flushParagraph()
                blocks += MarkdownBlock.ListItem(BULLET.find(line)!!.groupValues[1])
                index += 1
            }

            NUMBERED.matches(line) -> {
                flushParagraph()
                val match = NUMBERED.find(line)!!
                blocks += MarkdownBlock.ListItem(
                    text = match.groupValues[2],
                    number = match.groupValues[1].toIntOrNull(),
                )
                index += 1
            }

            trimmed.startsWith(">") -> {
                flushParagraph()
                blocks += MarkdownBlock.Quote(trimmed.removePrefix(">").trimStart())
                index += 1
            }

            trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                flushParagraph()
                blocks += MarkdownBlock.Divider
                index += 1
            }

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
                index += 1
            }
        }
    }
    flushParagraph()
    return blocks
}

/**
 * 轻量 Markdown 渲染：块级由 [MarkdownText] 排版，行内支持 **粗体**、*斜体*、`行内代码`、
 * ~~删除线~~ 和 [链接](url)。聊天回复里常见的标题/列表/代码块都能正常显示。
 */
@Composable
fun MarkdownText(
    markdown: String,
    fontScale: Float,
    modifier: Modifier = Modifier,
) {
    val colors = Dshm.colors
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    val base = Dshm.typography.composerText.copy(
        fontSize = Dshm.typography.composerText.fontSize * fontScale,
        lineHeight = Dshm.typography.composerText.lineHeight * fontScale,
        color = colors.textPrimary,
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xs),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> Text(
                    text = inlineMarkdown(block.text, colors, base),
                    style = base,
                )

                is MarkdownBlock.Heading -> Text(
                    text = inlineMarkdown(block.text, colors, base),
                    style = base.copy(
                        fontSize = base.fontSize * if (block.level <= 2) 1.18f else 1.08f,
                        lineHeight = base.lineHeight * if (block.level <= 2) 1.18f else 1.08f,
                        fontWeight = FontWeight.Bold,
                    ),
                )

                is MarkdownBlock.ListItem -> ListLine(
                    text = block.text,
                    number = block.number,
                    colors = colors,
                    base = base,
                )

                is MarkdownBlock.Quote -> Text(
                    text = inlineMarkdown(block.text, colors, base),
                    style = base.copy(
                        color = colors.textSecondary,
                        fontStyle = FontStyle.Italic,
                    ),
                    modifier = Modifier.padding(start = Dshm.spacing.sm),
                )

                is MarkdownBlock.Code -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Dshm.radius.sm))
                        .background(colors.surfaceMuted),
                ) {
                    Text(
                        text = block.text,
                        style = base.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = base.fontSize * 0.9f,
                            lineHeight = base.lineHeight * 0.9f,
                            color = colors.textPrimary,
                        ),
                        softWrap = false,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(Dshm.spacing.sm),
                    )
                }

                MarkdownBlock.Divider -> HorizontalDivider(
                    thickness = Dshm.spacing.hairline,
                    color = colors.divider,
                )
            }
        }
    }
}

@Composable
private fun ListLine(
    text: String,
    number: Int?,
    colors: DshmColors,
    base: TextStyle,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = number?.let { "$it." } ?: "•",
            style = base.copy(
                color = colors.textSecondary,
                fontWeight = if (number != null) FontWeight.Medium else FontWeight.Normal,
            ),
            modifier = Modifier.width(Dshm.spacing.lg),
        )
        Spacer(Modifier.width(Dshm.spacing.xxs))
        Text(
            text = inlineMarkdown(text, colors, base),
            style = base,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 行内 Markdown → AnnotatedString。 */
internal fun inlineMarkdown(
    text: String,
    colors: DshmColors,
    base: TextStyle,
): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        when {
            text.startsWith("**", index) -> {
                val end = text.indexOf("**", index + 2)
                if (end > index) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(index + 2, end))
                    }
                    index = end + 2
                } else {
                    append(text[index])
                    index += 1
                }
            }

            text.startsWith("~~", index) -> {
                val end = text.indexOf("~~", index + 2)
                if (end > index) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(text.substring(index + 2, end))
                    }
                    index = end + 2
                } else {
                    append(text[index])
                    index += 1
                }
            }

            text[index] == '`' -> {
                val end = text.indexOf('`', index + 1)
                if (end > index) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = colors.surfaceMuted,
                            fontSize = base.fontSize * 0.92f,
                        ),
                    ) {
                        append(text.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    append(text[index])
                    index += 1
                }
            }

            text[index] == '[' -> {
                val close = text.indexOf(']', index + 1)
                val urlEnd = if (close > index && close + 1 < text.length && text[close + 1] == '(') {
                    text.indexOf(')', close + 2)
                } else {
                    -1
                }
                if (close > index && urlEnd > close) {
                    withStyle(
                        SpanStyle(
                            color = colors.accent,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ) {
                        append(text.substring(index + 1, close))
                    }
                    index = urlEnd + 1
                } else {
                    append(text[index])
                    index += 1
                }
            }

            text[index] == '*' || text[index] == '_' -> {
                val marker = text[index]
                val end = text.indexOf(marker, index + 1)
                if (end > index + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    append(text[index])
                    index += 1
                }
            }

            else -> {
                append(text[index])
                index += 1
            }
        }
    }
}
