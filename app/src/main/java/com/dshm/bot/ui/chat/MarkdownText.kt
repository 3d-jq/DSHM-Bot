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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.unit.Dp
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
    // 聊天列表已经不参与 MinisTextKit 选区：走极简 Text，
    // 不挂 onTextLayout / onGloballyPositioned / drawBehind / shard 构建。
    if (controller == null) {
        Text(
            text = text,
            style = style,
            softWrap = softWrap,
            modifier = (if (fillWidth) Modifier.fillMaxWidth() else Modifier).then(modifier),
        )
        return
    }
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
    /** 流式输出中：按块冻结 + 后台解析，最后一块才是 live tail。 */
    streaming: Boolean = false,
) {
    val colors = Dshm.colors
    val base = Dshm.typography.composerText.copy(
        fontSize = Dshm.typography.composerText.fontSize * fontScale,
        lineHeight = Dshm.typography.composerText.lineHeight * fontScale,
        color = colors.textPrimary,
    )

    val allocator = remember(messageId) { ShardSubIndexAllocator() }
    // DeepSearch `MarkdownBlockBody`：冻结块解析一次并进进程级缓存，
    // live tail 在 Dispatchers.Default 上解析后再回主线程画，
    // 避免长表格 / 长段落每次 publish 都在主线程重扫。
    // 按块分片（DeepSearch `splitMarkdownIntoBlockTexts`）：每片是独立
    // composable、自己 produceState，未变的前缀 Compose 直接 skip，
    // 只有 live tail 那一小片会随 token 重画。
    val fragments = splitStreamingFragments(markdown)
    val liveFade = if (streaming) rememberStreamingFade() else null
    if (liveFade != null) StreamingFadeDriver(liveFade)
    CompositionLocalProvider(
        LocalMarkdownMessageId provides messageId,
        LocalShardSubIndex provides allocator,
    ) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xs),
    ) {
        fragments.forEachIndexed { fragmentIndex, fragment ->
            val live = streaming && fragmentIndex == fragments.lastIndex
            key(fragmentIndex) {
                MarkdownFragment(
                    raw = fragment,
                    fragmentIndex = fragmentIndex,
                    live = live,
                    colors = colors,
                    base = base,
                    mentionMembers = mentionMembers,
                    fillWidth = fillWidth,
                    fade = if (live) liveFade else null,
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
internal fun CodeBlockView(
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
 * 外层 `horizontalScroll`：内容放不下时按固定列宽横向拖；放得下时列等分气泡宽度。
 * 画完整网格（外框 + 横向分隔线 + 列间竖线），列宽固定才能保证竖线不被打乱。
 */
@Composable
internal fun MarkdownTable(
    header: List<String>,
    alignments: List<MarkdownTables.Alignment>,
    rows: List<List<String>>,
    colors: DshmColors,
    base: TextStyle,
    shardBase: String = "table",
) {
    if (header.isEmpty()) return
    // 4dp 圆角、0.5dp 细线。先算固定列宽下的总宽，由 [BoxWithConstraints] 决定是
    // 「铺满气泡、列等分」还是「保持固定列宽、外层横向滚动」。
    val borderColor = colors.textPrimary.copy(alpha = 0.2f)
    val headerFill = colors.surfaceMuted
    val shape = RoundedCornerShape(4.dp)
    val stroke = 0.5.dp
    val columns = header.size
    val dividerCount = (columns - 1).coerceAtLeast(0)
    val fixedWidth = Dshm.spacing.tableCellWidth * columns.toFloat() +
        stroke * dividerCount.toFloat()

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val available = if (maxWidth == Dp.Infinity) fixedWidth else maxWidth
        val fits = fixedWidth <= available
        val tableWidth = if (fits) available else fixedWidth
        val cellWidth = if (fits) {
            (available - stroke * dividerCount.toFloat()) / columns.toFloat()
        } else {
            Dshm.spacing.tableCellWidth
        }
        val hScrollKey = "$shardBase:${header.joinToString("|")}"
        Box(
            modifier = Modifier
                .horizontalScroll(TableHScrollStates.stateFor(hScrollKey))
                .border(stroke, borderColor, shape)
                .clip(shape),
        ) {
            // 竖线画在行背景上，不用 `IntrinsicSize.Min` + `fillMaxHeight()`：
            // 那个组合会让每行都做一次固有测量，表格长的时候滚动明显掉帧。
            val verticalLines: Modifier = Modifier.drawBehind {
                val lineWidth = stroke.toPx()
                for (column in 1 until columns) {
                    val x = cellWidth.toPx() * column + stroke.toPx() * (column - 1)
                    drawLine(
                        color = borderColor,
                        start = androidx.compose.ui.geometry.Offset(x, 0f),
                        end = androidx.compose.ui.geometry.Offset(x, size.height),
                        strokeWidth = lineWidth,
                    )
                }
            }
            Column(Modifier.width(tableWidth)) {
                // 表头行
                Row(
                    modifier = verticalLines
                        .background(headerFill),
                ) {
                    header.forEachIndexed { column, cell ->
                        TableCell(
                            text = cell,
                            alignment = alignments.getOrElse(column) { MarkdownTables.Alignment.LEFT },
                            colors = colors,
                            base = base,
                            bold = true,
                            modifier = Modifier.width(cellWidth),
                            shardId = "$shardBase:h$column",
                        )
                    }
                }
                HorizontalDivider(thickness = stroke, color = borderColor)
                // 数据行
                rows.forEachIndexed { rowIndex, row ->
                    Row(modifier = verticalLines) {
                        header.indices.forEach { column ->
                            TableCell(
                                text = row.getOrNull(column).orEmpty(),
                                alignment = alignments.getOrElse(column) { MarkdownTables.Alignment.LEFT },
                                colors = colors,
                                base = base,
                                bold = false,
                                modifier = Modifier.width(cellWidth),
                                shardId = "$shardBase:r$rowIndex$column",
                            )
                        }
                    }
                    HorizontalDivider(thickness = stroke, color = borderColor)
                }
            }
        }
    }
}

/** 表格的一格：固定列宽，按对齐方式排内容。 */
@Composable
internal fun TableCell(
    text: String,
    alignment: MarkdownTables.Alignment,
    colors: DshmColors,
    base: TextStyle,
    bold: Boolean,
    modifier: Modifier = Modifier,
    shardId: String = "",
) {
    Box(
        // 固定列宽：整体交给外层横向滚动，格子本身不再换行挤窄。
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
internal fun ListLine(
    text: String,
    number: Int?,
    checked: Boolean? = null,
    colors: DshmColors,
    base: TextStyle,
    mentionMembers: List<com.dshm.bot.ui.home.RoomMemberUi> = emptyList(),
    shardId: String = "",
    fillWidth: Boolean = true,
    streaming: Boolean = false,
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
                text = if (streaming) AnnotatedString(text) else inlineMarkdown(text, colors, base, mentionMembers),
                style = base,
                shardId = shardId,
                fillWidth = fillWidth,
            )
        }
    }
}

/** 行内 Markdown → AnnotatedString；`@名字` 会按成员颜色着色。 */
/**
 * 行内样式的解析结果缓存。
 *
 * 流式输出时整条气泡每几十毫秒重画一次：前面的段落文本一个字都没变，
 * 但 `inlineMarkdown` 每次都会重新扫一遍、重新建 AnnotatedString。
 * 按「主题 + 字号 + 提及列表 + 原文」缓存后，只有正在长的那一段会重新扫。
 */
private data class InlineKey(
    val colors: Int,
    val base: Int,
    val mentions: Int,
    val text: String,
)

private val inlineCache = object : LinkedHashMap<InlineKey, AnnotatedString>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<InlineKey, AnnotatedString>?): Boolean =
        size > 96
}

internal fun inlineMarkdown(
    text: String,
    colors: DshmColors,
    base: TextStyle,
    mentionMembers: List<com.dshm.bot.ui.home.RoomMemberUi> = emptyList(),
): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")
    val key = InlineKey(colors.hashCode(), base.hashCode(), mentionMembers.hashCode(), text)
    synchronized(inlineCache) { inlineCache[key] }?.let { return it }
    val built = buildInlineMarkdown(text, colors, base, mentionMembers)
    synchronized(inlineCache) { inlineCache[key] = built }
    return built
}

/**
 * live tail 的增量行内解析（DeepSearch `inlineIncremental`）：
 * 已闭合的前缀走 LRU 缓存，只重新扫末尾没闭合的一小段。
 */
internal fun inlineMarkdownIncremental(
    text: String,
    colors: DshmColors,
    base: TextStyle,
    mentionMembers: List<com.dshm.bot.ui.home.RoomMemberUi>,
): AnnotatedString {
    val exact = InlineKey(colors.hashCode(), base.hashCode(), mentionMembers.hashCode(), text)
    synchronized(inlineCache) { inlineCache[exact] }?.let { return it }
    if (text.length < 1_500) return inlineMarkdown(text, colors, base, mentionMembers)
    val split = safeInlineSplitOffset(text)
    if (split <= 0) return inlineMarkdown(text, colors, base, mentionMembers)
    val prefix = inlineMarkdown(text.substring(0, split), colors, base, mentionMembers)
    val suffix = buildInlineMarkdown(text.substring(split), colors, base, mentionMembers)
    return androidx.compose.ui.text.buildAnnotatedString {
        append(prefix)
        append(suffix)
    }
}

/** 找一个不会切开 `**` / `~~` / 反引号 / `*`/`_` / `[...](...)` 的换行边界。 */
private fun safeInlineSplitOffset(text: String): Int {
    var bold = false
    var strike = false
    var code = false
    var italic = false
    var linkDepth = 0
    for (index in text.indices) {
        when (val char = text[index]) {
            '`' -> code = !code
            '*' -> if (text.startsWith("**", index)) {
                bold = !bold
            } else if (!bold) {
                italic = !italic
            }
            '~' -> if (text.startsWith("~~", index)) strike = !strike
            '[' -> linkDepth += 1
            ']' -> if (linkDepth > 0) linkDepth -= 1
            '\n' -> if (!bold && !strike && !code && !italic && linkDepth == 0) return index + 1
        }
    }
    return 0
}

private fun buildInlineMarkdown(
    text: String,
    colors: DshmColors,
    base: TextStyle,
    mentionMembers: List<com.dshm.bot.ui.home.RoomMemberUi>,
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

            isBareUrlAt(text, index) -> {
                val end = bareUrlEnd(text, index)
                val url = text.substring(index, end)
                val link = runCatching { LinkAnnotation.Url(url) }.getOrNull()
                if (link != null) {
                    withLink(link) {
                        withStyle(
                            SpanStyle(
                                color = colors.accent,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ) { append(url) }
                    }
                } else {
                    withStyle(
                        SpanStyle(
                            color = colors.accent,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ) { append(url) }
                }
                index = end
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

/** 只认带 scheme 的裸链接，避免把 `example.com` 这种普通文字误判成链接。 */
private fun isBareUrlAt(text: String, index: Int): Boolean =
    text.startsWith("http://", index) || text.startsWith("https://", index)

/** URL 结束：空白、中文标点、右括号 / 方括号 / 尖括号都算终止。 */
private fun bareUrlEnd(text: String, start: Int): Int {
    var end = start
    while (end < text.length) {
        val char = text[end]
        if (char.isWhitespace() || char in "，。；！？、）》」』])>\"'" || char == '（') break
        end += 1
    }
    while (end > start && text[end - 1] in ".,;:!?") end -= 1
    return end
}

