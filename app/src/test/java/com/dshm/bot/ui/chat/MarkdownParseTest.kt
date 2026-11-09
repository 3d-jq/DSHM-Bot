package com.dshm.bot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 解析器：代码块带语言、任务清单、普通/编号列表。 */
class MarkdownParseTest {

    @Test
    fun codeFenceKeepsLanguage() {
        val blocks = parseMarkdown("```kotlin\nval x = 1\n```")
        val code = blocks.filterIsInstance<MarkdownBlock.Code>().single()
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.text)
    }

    @Test
    fun codeFenceWithoutLanguage() {
        val code = parseMarkdown("```\nplain\n```")
            .filterIsInstance<MarkdownBlock.Code>().single()
        assertEquals("", code.language)
        assertEquals("plain", code.text)
    }

    @Test
    fun taskListItemsCarryCheckState() {
        val items = parseMarkdown("- [ ] todo\n- [x] done\n- plain")
            .filterIsInstance<MarkdownBlock.ListItem>()
        assertEquals(3, items.size)
        assertEquals(false, items[0].checked)
        assertEquals(true, items[1].checked)
        assertEquals(null, items[2].checked)
        assertEquals("todo", items[0].text)
        assertEquals("done", items[1].text)
        assertEquals("plain", items[2].text)
    }

    @Test
    fun headingAndParagraphStaySeparate() {
        val blocks = parseMarkdown("# 标题\n正文")
        assertTrue(blocks[0] is MarkdownBlock.Heading)
        assertTrue(blocks[1] is MarkdownBlock.Paragraph)
    }
}
