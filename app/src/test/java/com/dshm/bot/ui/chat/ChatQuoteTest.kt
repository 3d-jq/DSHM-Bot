package com.dshm.bot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引用追问的纯函数，行为对齐 dsh-Go `app/lib/state/composer_quote.dart`：
 * 引用以 **Markdown 引用块**随消息发出（DSH 的 prompt 没有引用字段）。
 */
class ChatQuoteTest {

    @Test
    fun normalizeTidiesWhitespaceAndLineEnds() {
        val raw = "  第一行  \r\n\r\n\r\n第二行\t \n   "
        assertEquals("第一行\n\n第二行", normalizeQuote(raw))
    }

    @Test
    fun normalizeCapsAtTheLimitWithAnEllipsis() {
        val long = "字".repeat(MAX_QUOTE_LENGTH + 50)
        val out = normalizeQuote(long)
        assertTrue("超出要截断：${out.length}", out.length <= MAX_QUOTE_LENGTH + 1)
        assertTrue("截断要有省略号", out.endsWith("…"))
    }

    @Test
    fun normalizeKeepsEmojiWhole() {
        // 按码点截断，不能把代理对的 emoji 切成半个。
        val raw = "🙂".repeat(MAX_QUOTE_LENGTH + 10)
        val out = normalizeQuote(raw)
        assertTrue("不应出现孤立代理（半个 emoji）", !out.contains('\uFFFD'))
        assertEquals(MAX_QUOTE_LENGTH, out.trimEnd('…').codePointCount(0, out.trimEnd('…').length))
    }

    @Test
    fun withQuotePutsThePassageInFrontAsABlockquote() {
        val out = withQuote("第一行\n第二行", "你怎么看？")
        assertEquals("> 第一行\n> 第二行\n\n你怎么看？", out)
    }

    @Test
    fun aBlankLineInThePassageStaysInsideTheBlock() {
        // 空行写成裸 `>`，否则引用块会断开。
        val out = withQuote("上\n\n下", "然后呢")
        assertEquals("> 上\n>\n> 下\n\n然后呢", out)
    }

    @Test
    fun anEmptyQuoteLeavesTheTextAlone() {
        assertEquals("原文", withQuote("   ", "原文"))
    }

    @Test
    fun anEmptyMessageSendsOnlyTheBlockquote() {
        assertEquals("> 只有引用", withQuote("只有引用", "   "))
    }

    @Test
    fun quoteLineDropsMarkdownMarksForThePreview() {
        val preview = quoteLine("## 标题\n- **加粗** 和 `代码`\n> 嵌套")
        assertEquals("标题 加粗 和 代码 嵌套", preview)
    }

    /** 发出去的内容再读回来，开头就是引用块（我们的 MarkdownText 会把 `> ` 渲染成引用块）。 */
    @Test
    fun theSentTextOpensWithARenderableQuoteBlock() {
        val sent = withQuote("被引用的原话", "我的追问")
        assertTrue("应以引用块开头", sent.startsWith("> "))
        assertTrue("引用块后要有正文", sent.endsWith("我的追问"))
    }
}
