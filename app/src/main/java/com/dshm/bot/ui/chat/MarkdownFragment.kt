package com.dshm.bot.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val state = remember { mutableStateOf(MarkdownParseCache.peek(raw)) }
    LaunchedEffect(raw, live) {
        // 冻结块且初值已经命中缓存：连后台都不用再算。
        if (!live && state.value.isNotEmpty()) return@LaunchedEffect
        val parseStart = System.nanoTime()
        val computed = withContext(kotlinx.coroutines.Dispatchers.Default) {
            MarkdownParseCache.blocks(raw)
        }
        StreamRenderLog.slowParse(
            layer = "fragment",
            chars = raw.length,
            parseMs = (System.nanoTime() - parseStart) / 1_000_000L,
            thread = "DefaultDispatcher",
        )
        state.value = computed
    }
    val blocks = state.value
    blocks.forEachIndexed { index, block ->
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
