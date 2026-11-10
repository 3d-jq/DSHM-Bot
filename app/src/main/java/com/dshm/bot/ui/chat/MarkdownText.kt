package com.dshm.bot.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.core.design.DshmColors
import com.dshm.bot.R
import kotlinx.coroutines.delay

/** Markdown 块级节点（够聊天回复用：标题、段落、列表、引用、代码块、分隔线）。 */
internal sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class ListItem(
        val text: String,
        val number: Int? = null,
        /** `- [ ]` / `- [x]` 的勾选态；普通条目为 null。 */
        val checked: Boolean? = null,
    ) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val text: String, val language: String = "") : MarkdownBlock
    data object Divider : MarkdownBlock
    /** 表格：第一行是表头，其余是数据行；每列有对齐方式。 */
    data class Table(
        val header: List<String>,
        val alignments: List<MarkdownTables.Alignment>,
        val rows: List<List<String>>,
    ) : MarkdownBlock
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
private val TASK = Regex("^\\s*[-*+]\\s+\\[([ xX])]\\s+(.*)$")
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
                val language = trimmed.removePrefix("```").trim().substringBefore(' ')
                index += 1
                val code = StringBuilder()
                while (index < lines.size && !lines[index].trim().startsWith("```")) {
                    code.appendLine(lines[index])
                    index += 1
                }
                if (index < lines.size) index += 1
                blocks += MarkdownBlock.Code(code.toString().trimEnd(), language)
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

            TASK.matches(line) -> {
                flushParagraph()
                val match = TASK.find(line)!!
                blocks += MarkdownBlock.ListItem(
                    text = match.groupValues[2],
                    checked = match.groupValues[1].equals("x", ignoreCase = true),
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

            // 表格：表头行 + 分隔行 + 数据行。
            MarkdownTables.isRow(line) -> {
                val parsed = MarkdownTables.parse(lines, index)
                if (parsed != null) {
                    flushParagraph()
                    blocks += parsed.first
                    index = parsed.second
                } else {
                    if (paragraph.isNotEmpty()) paragraph.append('\n')
                    paragraph.append(line)
                    index += 1
                }
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

/** 当前正在渲染的消息 id（选区按消息 + 片段定位）。 */
internal val LocalMarkdownMessageId = compositionLocalOf { "" }

/**
 * 同一条 markdown 里每个 `ShardText` 的稳定子序号。
 *
 * 照 DeepSearch `ShardSubIndexAllocator`：一条消息里可能有多个 Text 节点，
 * 只用 `块号:类型` 会撞 id，注册表里后画的把先画的覆盖掉 —— 表现为「有的段落选不中」。
 */
internal class ShardSubIndexAllocator {
    private var counter = 0
    fun next(): Int = counter++
}

internal val LocalShardSubIndex = compositionLocalOf<ShardSubIndexAllocator?> { null }

/**
 * 一段参与选择的文字。
 *
 * 严格照 DeepSearch `StreamingMarkdownText.kt` 的 `MdText`：
 * - 基础 id 是完整 `TextShardId(messageId, 块id)`，子序号由 [LocalShardSubIndex]
 *   在 `remember(baseId)` 里分配一次，拼成 `块id#序号`；
 * - `register`/`unregister` 走 [RegisterSelectionShard]；
 * - `drawBehind` **无条件**挂，选区状态在 draw 阶段读（不重组 Text）；
 * - Text 的 `onGloballyPositioned` 只更新一个普通数组，不写 snapshot state。
 */
@Composable
internal fun ShardText(
    text: AnnotatedString,
    style: TextStyle,
    shardId: String,
    modifier: Modifier = Modifier,
    softWrap: Boolean = true,
    /** true = 长按整段选中（表格单元格用）；false = 按词选中。 */
    isAtomicUnit: Boolean = false,
    /**
     * Text 是否占满可用宽度。
     *
     * DeepSearch 的 `MdText` 由调用方把 `weight(1f)` / `width(120.dp)` 直接挂到
     * Text 上，命中矩形因此覆盖整行/整格。我们之前把 weight 挂在外层 Box，
     * Text 只包住字面宽度 —— 点在 `.md` / `.docx` 后面的空白处就命中不到。
     * 代码块要 `softWrap=false` + 横向滚动，不能撑满，故单独关掉。
     */
    fillWidth: Boolean = true,
) {
    val messageId = LocalMarkdownMessageId.current
    val controller = LocalMinisSelectionController.current
    val allocator = LocalShardSubIndex.current
    // 基础 id 同 `LocalShardId`；remember key 只用 baseId，和 DeepSearch 一致
    // （用具体 shardId 当 key 会在块内容变化时重新分配序号，注册表抖动）。
    val baseId = remember(messageId, shardId) { TextShardId(messageId, shardId) }
    val subIndex = remember(baseId) { allocator?.next() ?: 0 }
    // `shardOrderKey` 取最后一个冒号后的整数当文档顺序（DeepSearch 的
    // `mdblock:<parent>:<index>` 约定）。用 `#index` 会解析失败，退回按
    // 窗口 y 比较；反序列表里 y 顺序与文档顺序相反，同消息的其它片段
    // 会被判"不在选区内" → 不画高亮（但复制仍可用，正是用户看到的现象）。
    val id = remember(baseId, subIndex, allocator) {
        if (allocator == null) baseId.copy(shardId = "$shardId:$subIndex")
        else baseId.copy(shardId = "$shardId:$subIndex")
    }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // `inlineMarkdown` 每次重组都新建 AnnotatedString，用纯文本当 key 避免 shard 重建。
    val plainText = text.text
    val shard = remember(id, layoutResult, plainText, isAtomicUnit) {
        val result = layoutResult
        if (result == null) null else buildTextShard(
            id = id,
            plainText = plainText,
            layoutResult = result,
            coordinatesProvider = { coordinates[0] },
            isAtomicUnit = isAtomicUnit,
        )
    }
    RegisterSelectionShard(shard)
    val selectionState = controller?.selection
    val highlight = Dshm.colors.accent.copy(alpha = 0.28f)
    Text(
        text = text,
        style = style,
        softWrap = softWrap,
        modifier = (if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .then(modifier)
            .onGloballyPositioned { coordinates[0] = it }
            .drawBehind {
                val result = layoutResult ?: return@drawBehind
                val selection = selectionState?.value ?: return@drawBehind
                drawSelectionForShard(
                    shardId = id,
                    result = result,
                    selection = selection,
                    controller = controller,
                    color = highlight,
                )
            },
        onTextLayout = { layoutResult = it },
    )
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
    mentionMembers: List<com.dshm.bot.ui.home.RoomMemberUi> = emptyList(),
    /** 这条消息的 id：选区按 (消息, 片段) 记位置，滚动回来还能续上。 */
    messageId: String = "",
    /** false：短消息按内容宽度排版，不把气泡撑满（Bot 气泡用）。 */
    fillWidth: Boolean = true,
) {
    val colors = Dshm.colors
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    val base = Dshm.typography.composerText.copy(
        fontSize = Dshm.typography.composerText.fontSize * fontScale,
        lineHeight = Dshm.typography.composerText.lineHeight * fontScale,
        color = colors.textPrimary,
    )

    val allocator = remember(messageId) { ShardSubIndexAllocator() }
    CompositionLocalProvider(
        LocalMarkdownMessageId provides messageId,
        LocalShardSubIndex provides allocator,
    ) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xs),
    ) {
        blocks.forEachIndexed { index, block ->
            val baseId = "b$index"
            when (block) {
                is MarkdownBlock.Paragraph -> ShardText(
                    text = inlineMarkdown(block.text, colors, base, mentionMembers),
                    style = base,
                    shardId = "$baseId:p",
                    fillWidth = fillWidth,
                )

                is MarkdownBlock.Heading -> ShardText(
                    text = inlineMarkdown(block.text, colors, base, mentionMembers),
                    style = base.copy(
                        fontSize = base.fontSize * if (block.level <= 2) 1.18f else 1.08f,
                        lineHeight = base.lineHeight * if (block.level <= 2) 1.18f else 1.08f,
                        fontWeight = FontWeight.Bold,
                    ),
                    shardId = "$baseId:h",
                    fillWidth = fillWidth,
                )

                is MarkdownBlock.ListItem -> ListLine(
                    text = block.text,
                    number = block.number,
                    checked = block.checked,
                    colors = colors,
                    base = base,
                    mentionMembers = mentionMembers,
                    shardId = "$baseId:l",
                    fillWidth = fillWidth,
                )

                is MarkdownBlock.Quote -> ShardText(
                    text = inlineMarkdown(block.text, colors, base, mentionMembers),
                    style = base.copy(
                        color = colors.textSecondary,
                        fontStyle = FontStyle.Italic,
                    ),
                    shardId = "$baseId:q",
                    fillWidth = fillWidth,
                    // 左边一条强调色竖线：引用块不靠缩进硬猜，一眼能认出来。
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
                    shardId = "$baseId:c",
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
    }
    }
}

/**
 * 代码块：语言标签 + 一键复制 + 语法高亮 + 横竖都能滚。
 *
 * 对齐 DeepSearch 的 `CodeBlockView`；高亮用 [highlightCode]（轻量词法着色）。
 */
@Composable
private fun CodeBlockView(
    code: String,
    language: String,
    base: TextStyle,
    colors: DshmColors,
    shardId: String,
) {
    val context = LocalContext.current
    var copied by remember(code) { mutableStateOf(false) }
    val palette = remember(colors.isDark) { codePalette(colors.isDark) }
    val highlighted = remember(code, palette) { highlightCode(code, language, palette) }
    val lineColor = colors.textSecondary.copy(alpha = 0.22f)
    val shape = RoundedCornerShape(8.dp)

    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(1_400)
        copied = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceMuted)
            .border(1.dp, lineColor, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Dshm.spacing.sm, end = Dshm.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language.ifEmpty { "code" },
                style = Dshm.typography.rowTime,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    manager?.setPrimaryClip(ClipData.newPlainText("code", code))
                    copied = true
                },
                modifier = Modifier.height(30.dp),
            ) {
                Icon(
                    painter = painterResource(
                        if (copied) R.drawable.ic_lucide_check else R.drawable.ic_lucide_copy,
                    ),
                    contentDescription = stringResource(R.string.chat_selection_copy),
                    tint = if (copied) colors.accent else colors.textSecondary,
                    modifier = Modifier.height(16.dp),
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = lineColor)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Dshm.spacing.sm, vertical = Dshm.spacing.xs),
        ) {
            ShardText(
                text = highlighted,
                style = base.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = base.fontSize * 0.9f,
                    lineHeight = base.lineHeight * 0.95f,
                ),
                shardId = shardId,
                softWrap = false,
                fillWidth = false,
            )
        }
    }
}

/**
 * 表格：带边框、**横向可滚动**。
 *
 * 规格照 DeepSearch 的 `MarkdownText.kt` 的 `TableView`：
 * 整块表格外面 0.5dp 边框 + 4dp 圆角，表头有底色、每行一条分隔线，
 * 列固定 120dp（内容长短不影响列宽，表格因此不会因某格太长而歪掉）。
 */
@Composable
private fun MarkdownTable(
    header: List<String>,
    alignments: List<MarkdownTables.Alignment>,
    rows: List<List<String>>,
    colors: DshmColors,
    base: TextStyle,
    shardBase: String = "table",
) {
    if (header.isEmpty()) return
    // 照 DeepSearch `TableView`：只画外框 + 每行横线，**不画竖线**（竖线会把
    // 宽度不同的单元格切得参差），4dp 圆角、0.5dp 边框。
    val borderColor = colors.textPrimary.copy(alpha = 0.2f)
    val headerFill = colors.surfaceMuted
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, borderColor, shape)
            .clip(shape),
    ) {
        Column {
            // 表头行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerFill)
                    .height(IntrinsicSize.Min),
            ) {
                header.forEachIndexed { column, cell ->
                    TableCell(
                        text = cell,
                        alignment = alignments.getOrElse(column) { MarkdownTables.Alignment.LEFT },
                        colors = colors,
                        base = base,
                        bold = true,
                        modifier = Modifier.weight(1f),
                        shardId = "$shardBase:h$column",
                    )
                    if (column < header.lastIndex) {
                        VerticalDivider(
                            thickness = 0.5.dp,
                            color = borderColor,
                            modifier = Modifier.fillMaxHeight(),
                        )
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = borderColor)
            // 数据行
            rows.forEachIndexed { rowIndex, row ->
                Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    header.indices.forEach { column ->
                        TableCell(
                            text = row.getOrNull(column).orEmpty(),
                            alignment = alignments.getOrElse(column) { MarkdownTables.Alignment.LEFT },
                            colors = colors,
                            base = base,
                            bold = false,
                            modifier = Modifier.weight(1f),
                            shardId = "$shardBase:r$rowIndex$column",
                        )
                        if (column < header.lastIndex) {
                            VerticalDivider(
                                thickness = 0.5.dp,
                                color = borderColor,
                                modifier = Modifier.fillMaxHeight(),
                            )
                        }
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = borderColor)
            }
        }
    }
}

/** 表格的一格：固定 120dp 宽，按对齐方式排内容。 */
@Composable
private fun TableCell(
    text: String,
    alignment: MarkdownTables.Alignment,
    colors: DshmColors,
    base: TextStyle,
    bold: Boolean,
    modifier: Modifier = Modifier,
    shardId: String = "",
) {
    Box(
        // 列宽均分气泡宽度：手机上不会出现「第三列被切掉、要横向拖」的情况。
        modifier = modifier.padding(horizontal = Dshm.spacing.sm, vertical = 6.dp),
        contentAlignment = when (alignment) {
            MarkdownTables.Alignment.CENTER -> Alignment.Center
            MarkdownTables.Alignment.RIGHT -> Alignment.CenterEnd
            MarkdownTables.Alignment.LEFT -> Alignment.CenterStart
        },
    ) {
        ShardText(
            text = inlineMarkdown(text, colors, base),
            style = base.copy(
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                color = colors.textPrimary,
            ),
            shardId = shardId,
            isAtomicUnit = true,
        )
    }
}

@Composable
private fun ListLine(
    text: String,
    number: Int?,
    checked: Boolean? = null,
    colors: DshmColors,
    base: TextStyle,
    mentionMembers: List<com.dshm.bot.ui.home.RoomMemberUi> = emptyList(),
    shardId: String = "",
    fillWidth: Boolean = true,
) {
    Row(
        modifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier.wrapContentWidth(Alignment.Start),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = when {
                checked == true -> "☑"
                checked == false -> "☐"
                number != null -> "$number."
                else -> "•"
            },
            style = base.copy(
                color = if (checked != null) colors.accent else colors.textSecondary,
                fontWeight = if (number != null) FontWeight.Medium else FontWeight.Normal,
            ),
            modifier = Modifier.width(Dshm.spacing.lg),
        )
        Spacer(Modifier.width(Dshm.spacing.xxs))
        // 注意：weight 放在外层 Box 上，Text 本身保持普通测量 ——
        // 直接把 weight 挂在 Text 上时，它在 Compose 的选择注册里会漏掉（长按选不中）。
        Box(modifier = Modifier.weight(1f)) {
            ShardText(
                text = inlineMarkdown(text, colors, base, mentionMembers),
                style = base,
                shardId = shardId,
                fillWidth = fillWidth,
            )
        }
    }
}

/** 行内 Markdown → AnnotatedString；`@名字` 会按成员颜色着色。 */
internal fun inlineMarkdown(
    text: String,
    colors: DshmColors,
    base: TextStyle,
    mentionMembers: List<com.dshm.bot.ui.home.RoomMemberUi> = emptyList(),
): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        when {
            text[index] == '@' -> {
                val nameStart = index + 1
                var nameEnd = nameStart
                while (nameEnd < text.length && !text[nameEnd].isWhitespace() &&
                    text[nameEnd] != ',' && text[nameEnd] != '。' && text[nameEnd] != '，' &&
                    text[nameEnd] != '.' && text[nameEnd] != '？' && text[nameEnd] != '!' &&
                    text[nameEnd] != '；'
                ) nameEnd += 1
                val name = text.substring(nameStart, nameEnd)
                val member = mentionMembers.firstOrNull { it.name == name }
                if (member != null) {
                    withStyle(
                        SpanStyle(color = Color(com.dshm.bot.domain.model.BotInks.color(member.inkId, colors.isDark))),
                    ) {
                        append(text.substring(index, nameEnd))
                    }
                    index = nameEnd
                } else {
                    append(text[index])
                    index += 1
                }
            }

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
                            // 不能用 SpanStyle(background)：它会画在文字层，
                            // 盖住 drawBehind 里的选区高亮（能复制却看不到高亮）。
                            // 对齐 DeepSearch：底色改由 drawBehind 按 annotation 画。
                            color = colors.textPrimary,
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
                    val url = text.substring(close + 2, urlEnd).trim()
                    // 可点：Compose 的 LinkAnnotation 交给系统打开浏览器。
                    val link = runCatching { LinkAnnotation.Url(url) }.getOrNull()
                    if (link != null) {
                        withLink(link) {
                            withStyle(
                                SpanStyle(
                                    color = colors.accent,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ) {
                                append(text.substring(index + 1, close))
                            }
                        }
                    } else {
                        withStyle(
                            SpanStyle(
                                color = colors.accent,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ) {
                            append(text.substring(index + 1, close))
                        }
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

