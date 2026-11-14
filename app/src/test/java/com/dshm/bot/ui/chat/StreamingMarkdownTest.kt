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
    fun `段落里的 markdown 链接解析并可点`() {
        val colors = com.dshm.bot.core.design.DshmColors.Light
        val text = "[《半个男人》 Half Man](https://news.qq.com/rain/a/20261103A0EKF300) — 2026 Q2 最高分英剧"
        val annotated = inlineMarkdown(text, colors, androidx.compose.ui.text.TextStyle.Default)
        assertTrue("未解析成链接", annotated.getLinkAnnotations(0, annotated.length).isNotEmpty())
    }

    @Test
    fun `列表项流式输出时也保留行内链接`() {
        val colors = com.dshm.bot.core.design.DshmColors.Light
        val text = "[《踮起脚尖》 Tip Toe](https://news.qq.com/rain/a/20261103A0EKF300) — 9.0 分，5 集"
        // 模拟 stream 中列表项走的路径：只扫尾部增量。
        val annotated = inlineMarkdownIncremental(text, colors, androidx.compose.ui.text.TextStyle.Default, emptyList())
        assertTrue("列表项增量解析丢了链接", annotated.getLinkAnnotations(0, annotated.length).isNotEmpty())
    }

    @Test
    fun `标题与url被换行拆开也能解析成链接`() {
        val colors = com.dshm.bot.core.design.DshmColors.Light
        val text = "[《铁拳教育》]\n(https://news.qq.com/rain/a/20261103A0EKF300) — 2026 Q2 最高分韩剧"
        val annotated = inlineMarkdown(text, colors, androidx.compose.ui.text.TextStyle.Default)
        assertTrue("跨行链接未解析", annotated.getLinkAnnotations(0, annotated.length).isNotEmpty())
        // 只显示标题，URL 不应再作为正文露出来
        assertTrue("URL 仍裸露在正文里", !annotated.text.contains("news.qq.com"))
    }

    @Test
    fun `url 中间被换行打断也能拼回并解析`() {
        val colors = com.dshm.bot.core.design.DshmColors.Light
        val text = "[《半个男人》 Half Man](https://news.qq.com/rain/a\n/20261103A0EKF300) — 2026 Q2"
        val annotated = inlineMarkdown(text, colors, androidx.compose.ui.text.TextStyle.Default)
        assertTrue("换行 URL 未解析", annotated.getLinkAnnotations(0, annotated.length).isNotEmpty())
    }

    @Test
    fun `粗体里套链接也能解析`() {
        val colors = com.dshm.bot.core.design.DshmColors.Light
        val text = "**[《踮起脚尖》Tip Toe](https://news.qq.com/rain/a/20261103A0EKF300)** — **9.0 分**，5 集"
        val annotated = inlineMarkdown(text, colors, androidx.compose.ui.text.TextStyle.Default)
        assertTrue("粗体里的链接没解析", annotated.getLinkAnnotations(0, annotated.length).isNotEmpty())
        assertTrue("URL 仍裸露", !annotated.text.contains("news.qq.com"))
        assertTrue("粗体丢了", annotated.spanStyles.any { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold })
    }

    @Test
    fun `多段粗体链接连续出现都能解析`() {
        val colors = com.dshm.bot.core.design.DshmColors.Light
        val text = "**[《铁拳教育》](https://news.qq.com/a1)** — 2026 Q2 最高分韩剧\n" +
            "**[《柔美的细胞小将》第三季](https://www.sina.cn/news/detail/5315973569840042.html)** — 同季度最高分韩剧"
        val annotated = inlineMarkdown(text, colors, androidx.compose.ui.text.TextStyle.Default)
        val links = annotated.getLinkAnnotations(0, annotated.length)
        org.junit.Assert.assertEquals(2, links.size)
    }

    @Test
    fun `同一片段重复解析命中缓存`() {
        val raw = "第一段\n\n| A | B |\n| --- | --- |\n| 1 | 2 |"
        val first = MarkdownParseCache.blocks(raw)
        assertTrue(first.isNotEmpty())
        org.junit.Assert.assertSame(first, MarkdownParseCache.blocks(raw))
    }

    @Test
    fun `peek 只查缓存不现算`() {
        // MarkdownFragment 的初值走 peek：未命中必须给空表而不是在主线程现算一遍
        // （live tail 的 raw 每次 publish 都在变，老实现的 initialValue 逐帧重扫）。
        val raw = "peek-uniq-${System.nanoTime()}\n\n第二段"
        assertTrue(MarkdownParseCache.peek(raw).isEmpty())
        val parsed = MarkdownParseCache.blocks(raw)
        assertTrue(parsed.isNotEmpty())
        org.junit.Assert.assertSame(parsed, MarkdownParseCache.peek(raw))
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
