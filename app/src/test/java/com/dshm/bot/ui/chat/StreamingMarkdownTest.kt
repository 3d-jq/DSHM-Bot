package com.dshm.bot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownTest {
    @Test
    fun `分块按空行与围栏切开`() {
        val fragments = splitStreamingFragments("第一段\n\n第二段\n```js\na\n```\n\n第三段")
        assertEquals(listOf("第一段", "第二段", "```js\na\n```", "第三段"), fragments)
    }

    @Test
    fun `节流随长度单调不减且短回答够密`() {
        assertTrue(BotChatViewModel.streamingThrottleMs(100) <= 40L)
        assertTrue(BotChatViewModel.streamingThrottleMs(1_000) <= 60L)
        val samples = listOf(0, 499, 500, 1_999, 2_000, 31_999, 32_000, 63_999, 64_000, 200_000)
        val values = samples.map { BotChatViewModel.streamingThrottleMs(it) }
        values.zipWithNext().forEach { (a, b) -> assertTrue(a <= b) }
    }

    @Test
    fun `裸链接会渲染成可点链接`() {
        val colors = com.dshm.bot.core.design.DshmColors.Light
        val text = "看 https://example.com/a?b=1 这个"
        val annotated = inlineMarkdown(text, colors, androidx.compose.ui.text.TextStyle.Default)
        assertTrue(annotated.text.contains("https://example.com/a?b=1"))
        assertTrue(annotated.getLinkAnnotations(0, annotated.length).isNotEmpty())
    }

    @Test
    fun `同一片段重复解析命中缓存`() {
        val raw = "第一段\n\n| A | B |\n| --- | --- |\n| 1 | 2 |"
        val first = MarkdownParseCache.blocks(raw)
        assertTrue(first.isNotEmpty())
        org.junit.Assert.assertSame(first, MarkdownParseCache.blocks(raw))
    }

    @Test
    fun `逐词淡入只覆盖新追加后缀`() {
        val fade = StreamingFadeController()
        fade.ingest("你好 世界")
        assertTrue(fade.active)
        fade.ingest("你好 世界 新的词")
        val colors = com.dshm.bot.core.design.DshmColors.Light
        val faded = fade.overlay(
            androidx.compose.ui.text.AnnotatedString("你好 世界 新的词"),
            colors.textPrimary,
        )
        assertEquals("你好 世界 新的词", faded.text)
        assertTrue(faded.spanStyles.any { it.start >= 6 })
    }
}
