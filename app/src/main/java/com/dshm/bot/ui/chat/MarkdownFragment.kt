package com.dshm.bot.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import com.dshm.bot.core.design.DshmColors
import com.dshm.bot.ui.home.RoomMemberUi
import kotlinx.coroutines.withContext

/**
 * 一个 markdown 片段 = 一个独立 composable（DeepSearch `MarkdownBlockBody`）。
 *
 * 解析一律在后台（Dispatchers.Default）做，主线程只消费结果：
 * - 冻结块：初值直接吃 [MarkdownParseCache.peek]（命中就同帧画出，未命中先空着、
 *   后台解析落地补上 —— 只发生一次，之后都在缓存里）；
 * - live tail：`raw` 每次 publish 都在变，state 不随 key 重置，**保留上一帧的内容**，
 *   新解析落地才替换 —— 以前 `produceState` 的 initialValue 会随 key 变化在主线程
 *   现算一遍，live tail 等于逐帧重扫，这里彻底去掉。
 */
@Composable
internal fun MarkdownFragment(
    raw: String,
    fragmentIndex: Int,
    live: Boolean,
    colors: DshmColors,
    base: TextStyle,
    mentionMembers: List<RoomMemberUi>,
    fillWidth: Boolean,
    fade: StreamingFadeController?,
) {
    // `null` = **还没解析过**（冷未命中）；空表 = 解析过、结果确实为空。
    // 二者必须分开：前者要拿纯文本占位，后者才该什么都不画。
    val state = remember {
        mutableStateOf(MarkdownParseCache.peek(raw).takeIf { it.isNotEmpty() })
    }
    // 这份 blocks 对应的是哪段原文。**必须记**：流式与落定现在是同一个 item，
    // 落定后 raw 变了但 `remember` 不会重建 —— 只看「state 非空」会把流式尾巴的
    // 旧解析一直留在屏幕上（最后几个字永远不出现）。
    var parsedRaw by remember { mutableStateOf(if (state.value != null) raw else null) }
    LaunchedEffect(raw, live) {
        // 冻结块且这段话的结果已经有了（缓存命中 / 本 item 上次就解析过同一段）：
        // 连后台都不用再算。
        if (!live && state.value != null && parsedRaw == raw) return@LaunchedEffect
        val parseStart = System.nanoTime()
        val computed = withContext(kotlinx.coroutines.Dispatchers.Default) {
            MarkdownParseCache.blocks(raw)
        }
        val parseMs = (System.nanoTime() - parseStart) / 1_000_000L
        StreamRenderLog.slowParse(
            layer = "fragment",
            chars = raw.length,
            parseMs = parseMs,
            thread = "DefaultDispatcher",
        )
        // 自适应节流的代价信号：解析越贵，下一次刷新间隔越宽（官方同款公式）。
        if (live) StreamRenderCost.observeRenderCost(parseMs)
        state.value = computed
        parsedRaw = raw
    }
    val blocks = state.value
    if (blocks == null) {
        // 冷未命中：先用**有界纯文本**占位（DeepSearch `[T-android-longtext-anr]`）。
        //
        // ⚠️ 这里绝不能画 0 高。落定瞬间整条回复是**全新的 LazyColumn item**
        // （key 从 `streaming` 换成 `bot:<seq>`），组合期缓存若未命中就什么都不画 →
        // 气泡先塌成一条 padding → **上面那条用户气泡掉下来、解析落地后又弹回去**
        // （真机：大模型输出完了用户蓝气泡闪一下；长短回复都会）。
        // 未命中的常见来源：流式最后一次 publish 被节流拖后（文本是前缀）、
        // 或尾部空白与落定文本不同 —— 最后一片对不上，缓存就 miss。
        //
        // 用一次廉价的 Text 布局顶住高度（不跑 markdown/正则），解析落地后换成正式排版。
        if (raw.isNotBlank()) {
            ShardText(
                text = AnnotatedString(raw.take(COLD_PARSE_PREVIEW_CHARS)),
                style = base,
                fillWidth = fillWidth,
            )
        }
    } else {
        blocks.forEachIndexed { index, block ->
            // 块间距按类型给（对齐官方 markdown.css）：标题上留 32、段落 16、列表项 6。
            // 第一个片段的第一块不加（气泡顶部已有 padding）；其余块都加上间距。
            val topSpace = if (index == 0 && fragmentIndex == 0) 0.dp else markdownTopSpace(block)
            Column(modifier = Modifier.padding(top = topSpace)) {
                RenderMarkdownBlock(
                    block = block,
                    baseId = "f${fragmentIndex}b$index",
                    live = live,
                    colors = colors,
                    base = base,
                    mentionMembers = mentionMembers,
                    fillWidth = fillWidth,
                    fade = fade,
                )
            }
        }
    }
}

/**
 * 冷未命中时纯文本占位的字符上限（对齐 DeepSearch `COLD_PARSE_PREVIEW_CHARS`）。
 *
 * 只影响「整片都没缓存」的情形（首次进对话、滚动回来）；落定边界上通常只有
 * 最后一片 miss（前面的片流式时已经解析进缓存），所以尾巴上的占位很短。
 */
private const val COLD_PARSE_PREVIEW_CHARS = 4_000
