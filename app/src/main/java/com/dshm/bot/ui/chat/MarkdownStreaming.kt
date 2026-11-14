package com.dshm.bot.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.HorizontalDivider
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.core.design.DshmColors
import com.dshm.bot.ui.home.RoomMemberUi

/**
 * 流式 markdown 的按块冻结（对齐 DeepSearch `splitMarkdownIntoBlockTexts`）。
 *
 * 边界只取「代码块外的空行」和围栏代码块；表格、标题、列表保持在前一个 fragment 里，
 * 这样已经写完的段落不会因为后面继续追加 token 而重新解析。
 * 拼接所有 fragment（用 "\n\n"）可以还原原文。
 */
internal fun splitStreamingFragments(content: String): List<String> {
    if (content.isEmpty()) return emptyList()
    val out = mutableListOf<String>()
    val current = StringBuilder()
    var inFence = false
    fun flush() {
        if (current.isNotEmpty()) {
            out.add(current.toString().trimEnd('\n'))
            current.clear()
        }
    }
    for (line in content.lines()) {
        val trimmed = line.trimStart()
        val isFence = trimmed.startsWith("```")
        if (isFence) {
            if (!inFence) {
                flush()
                current.append(line).append('\n')
                inFence = true
            } else {
                current.append(line).append('\n')
                inFence = false
                flush()
            }
            continue
        }
        if (inFence) {
            current.append(line).append('\n')
            continue
        }
        if (line.isBlank()) {
            flush()
            continue
        }
        current.append(line).append('\n')
    }
    flush()
    return out
}

/** 进程级块解析 LRU：冻结块滚动回来 / 同一段文本重画时不再重扫。 */
internal object MarkdownParseCache {
    private val blocks = object : LinkedHashMap<String, List<MarkdownBlock>>(32, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<MarkdownBlock>>?,
        ): Boolean = size > 128
    }

    fun blocks(raw: String): List<MarkdownBlock> = synchronized(blocks) {
        blocks.getOrPut(raw) { parseMarkdown(raw) }
    }

    /** 只查不解析：命中给结果，未命中给空表（绝不现算）—— 给组合期取初值用。 */
    fun peek(raw: String): List<MarkdownBlock> = synchronized(blocks) {
        blocks[raw].orEmpty()
    }
}

/** 单个 markdown 块；live tail 走增量行内解析，其余块走普通缓存。 */
@Composable
internal fun RenderMarkdownBlock(
    block: MarkdownBlock,
    baseId: String,
    live: Boolean,
    colors: DshmColors,
    base: TextStyle,
    mentionMembers: List<RoomMemberUi>,
    fillWidth: Boolean,
    fade: StreamingFadeController? = null,
) {
    fun inline(text: String): AnnotatedString =
        if (live) inlineMarkdownIncremental(text, colors, base, mentionMembers)
        else inlineMarkdown(text, colors, base, mentionMembers)

    fun withFade(text: AnnotatedString, raw: String): AnnotatedString {
        if (!live || fade == null) return text
        fade.ingest(raw)
        return fade.overlay(text, colors.textPrimary)
    }

    when (block) {
        is MarkdownBlock.Paragraph -> ShardText(
            text = withFade(inline(block.text), block.text),
            style = base,
            fillWidth = fillWidth,
        )

        is MarkdownBlock.Heading -> ShardText(
            text = withFade(inline(block.text), block.text),
            style = base.copy(
                fontSize = base.fontSize * if (block.level <= 2) 1.18f else 1.08f,
                lineHeight = base.lineHeight * if (block.level <= 2) 1.18f else 1.08f,
                fontWeight = FontWeight.Bold,
            ),
            fillWidth = fillWidth,
        )

        is MarkdownBlock.ListItem -> ListLine(
            text = block.text,
            number = block.number,
            checked = block.checked,
            colors = colors,
            base = base,
            mentionMembers = mentionMembers,
            fillWidth = fillWidth,
            streaming = live,
        )

        is MarkdownBlock.Quote -> ShardText(
            text = withFade(inline(block.text), block.text),
            style = base.copy(
                color = colors.textSecondary,
                fontStyle = FontStyle.Italic,
            ),
            fillWidth = fillWidth,
            modifier = Modifier
                .drawBehind {
                    drawRect(
                        color = colors.accent,
                        topLeft = androidx.compose.ui.geometry.Offset.Zero,
                        size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height),
                    )
                }
                .padding(start = Dshm.spacing.md),
        )

        is MarkdownBlock.Code -> CodeBlockView(
            code = block.text,
            language = block.language,
            base = base,
            colors = colors,
        )

        MarkdownBlock.Divider -> HorizontalDivider(
            thickness = Dshm.spacing.hairline,
            color = colors.divider,
        )

        is MarkdownBlock.Table -> MarkdownTable(
            header = block.header,
            alignments = block.alignments,
            rows = block.rows,
            colors = colors,
            base = base,
            shardBase = "$baseId:t",
        )
    }
}
