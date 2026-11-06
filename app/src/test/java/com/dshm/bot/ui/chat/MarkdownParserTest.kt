package com.dshm.bot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun `parses headings lists and code fences`() {
        val blocks = parseMarkdown(
            """
            ## 标题
            - 第一项
            - **第二项**
            1. 有序
            ```kotlin
            val x = 1
            ```
            """.trimIndent(),
        )
        assertTrue(blocks.any { it is MarkdownBlock.Heading && it.text == "标题" })
        assertEquals(2, blocks.filterIsInstance<MarkdownBlock.ListItem>().count { it.number == null })
        assertTrue(blocks.any { it is MarkdownBlock.ListItem && it.number == 1 })
        assertTrue(blocks.any { it is MarkdownBlock.Code && it.text.contains("val x") })
    }

    @Test
    fun `keeps plain paragraphs intact`() {
        val blocks = parseMarkdown("第一段\n仍然第一段\n\n第二段")
        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it is MarkdownBlock.Paragraph })
    }
}
