package com.dshm.bot.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.TextStyle
import com.dshm.bot.core.design.DshmColors
import com.dshm.bot.ui.home.RoomMemberUi

/**
 * 一个 markdown 片段 = 一个独立 composable（DeepSearch `MarkdownBlockBody`）。
 *
 * `produceState` 保留上一帧 blocks，后台解析落地才替换；冻结片段命中
 * `MarkdownParseCache` 后完全不重组。这样一条长回复里只有 live tail 会
 * 随 token 重画，前面的段落/表格都是 Compose skip。
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
    val blocks by produceState(
        initialValue = MarkdownParseCache.blocks(raw),
        key1 = raw,
        key2 = live,
    ) {
        if (!live) {
            value = MarkdownParseCache.blocks(raw)
            return@produceState
        }
        val parseStart = System.nanoTime()
        val computed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            MarkdownParseCache.blocks(raw)
        }
        StreamRenderLog.slowParse(
            layer = "fragment",
            chars = raw.length,
            parseMs = (System.nanoTime() - parseStart) / 1_000_000L,
            thread = "DefaultDispatcher",
        )
        value = computed
    }
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
