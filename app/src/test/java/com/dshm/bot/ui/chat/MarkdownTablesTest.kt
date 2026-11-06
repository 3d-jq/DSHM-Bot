package com.dshm.bot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 表格解析（移植自 DeepSearch 的表格解析）。
 */
class MarkdownTablesTest {

    @Test
    fun aTableIsParsedIntoHeaderAndRows() {
        val lines = listOf(
            "| 类型 | 地址 |",
            "| --- | --- |",
            "| IPv4 | 172.30.252.239 |",
            "| IPv6 | 240e:576f::1 |",
        )
        val parsed = MarkdownTables.parse(lines, 0)
        assertTrue(parsed != null)
        val table = parsed!!.first
        assertEquals(listOf("类型", "地址"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("IPv4", "172.30.252.239"), table.rows[0])
        // 消费到第 4 行。
        assertEquals(4, parsed.second)
    }

    @Test
    fun alignmentsComeFromTheSeparatorRow() {
        val lines = listOf(
            "| 左 | 中 | 右 |",
            "| :--- | :---: | ---: |",
            "| a | b | c |",
        )
        val table = MarkdownTables.parse(lines, 0)!!.first
        assertEquals(
            listOf(
                MarkdownTables.Alignment.LEFT,
                MarkdownTables.Alignment.CENTER,
                MarkdownTables.Alignment.RIGHT,
            ),
            table.alignments,
        )
    }

    @Test
    fun rowsArePaddedToTheHeaderWidth() {
        val lines = listOf(
            "| a | b | c |",
            "| --- | --- | --- |",
            "| 1 |",
        )
        val table = MarkdownTables.parse(lines, 0)!!.first
        assertEquals(3, table.rows[0].size)
        assertEquals("", table.rows[0][1])
        assertEquals("", table.rows[0][2])
    }

    @Test
    fun aLineWithoutASeparatorIsNotATable() {
        val lines = listOf("| a | b |", "| c | d |")
        assertNull(MarkdownTables.parse(lines, 0))
    }

    @Test
    fun aPipeInsideALinkUrlIsNotASeparator() {
        // 链接 URL 里的裸竖线不能把这一行切开。
        val cells = MarkdownTables.splitRow("| [图](minis://a|b.png) | 文字 |")
        assertEquals(2, cells.size)
        assertEquals("[图](minis://a|b.png)", cells[0])
    }

    @Test
    fun aTableCrushedIntoOneLineIsParsed() {
        // 模型常不换行，整张表塞在一行：表头 | 分隔 | 数据。
        val line = "| 类型 | 地址 | --- | --- | IPv4 | 172.30.252.239 |"
        val table = MarkdownTables.parseInline(line)
        assertTrue(table != null)
        assertEquals(listOf("类型", "地址"), table!!.header)
        assertEquals(1, table.rows.size)
        assertEquals(listOf("IPv4", "172.30.252.239"), table.rows[0])
    }

    @Test
    fun aLineWithPipesButNoDividerIsNotATable() {
        assertNull(MarkdownTables.parseInline("| 普通 | 文字 |"))
    }

    @Test
    fun separatorDetection() {
        assertTrue(MarkdownTables.isSeparator("| --- | --- |"))
        assertTrue(MarkdownTables.isSeparator("|---|---|"))
        assertTrue(MarkdownTables.isSeparator("| :---: |"))
        assertTrue(!MarkdownTables.isSeparator("| 文字 |"))
        assertTrue(!MarkdownTables.isSeparator("普通行"))
    }
}
