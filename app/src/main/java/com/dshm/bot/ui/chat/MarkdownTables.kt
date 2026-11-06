package com.dshm.bot.ui.chat

/**
 * Markdown 表格解析。
 *
 * 移植自 DeepSearch（`port/src/android/app/src/main/java/com/openminis/app/ui/markdown/
 * MarkdownParser.kt`）的表格解析 —— 它处理得比朴素切分细致：
 * - 分隔行定出每列的**对齐方式**（`:---` 左 / `:---:` 中 / `---:` 右）；
 * - 单元格里的 Markdown 链接 URL 可能带裸竖线（`[f](x|y)`），
 *   这时要按括号深度走，不能一刀切。
 */
internal object MarkdownTables {

    /** 表格的列对齐方式。 */
    enum class Alignment { LEFT, CENTER, RIGHT }

    /** 是不是表格的分隔行（`| --- | :--: |`）。 */
    fun isSeparator(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.contains('-')) return false
        val cells = trimmed.split('|').filter { it.isNotBlank() }
        if (cells.isEmpty()) return false
        return cells.all { it.trim().matches(Regex("^:?-+:?$")) }
    }

    /**
     * 切一行表格的单元格。
     *
     * 只有当这一行含 Markdown 链接（`](`）时才走括号深度那套：
     * URL 里的裸竖线不能切；不含链接的行按普通竖线切，
     * 否则一个不配对的 `(` 之后的内容会被吞掉。
     */
    fun splitRow(line: String): List<String> {
        val trimmed = line.trim().removePrefix("|").removeSuffix("|")
        if (!trimmed.contains("](")) {
            return trimmed.split('|').map { it.trim() }
        }
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (char in trimmed) {
            when {
                char == '(' && depth == 0 -> depth++
                char == ')' && depth > 0 -> depth--
            }
            if (char == '|' && depth == 0) {
                cells += current.toString().trim()
                current.clear()
            } else {
                current.append(char)
            }
        }
        cells += current.toString().trim()
        return cells
    }

    /** 一行是不是表格行：含竖线且切得出格子。 */
    fun isRow(line: String): Boolean = line.contains('|') && splitRow(line).isNotEmpty()

    /**
     * 解析「挤在一行」的表格：`表头格 | 表头格 | --- | --- | 数据格 | 数据格`。
     *
     * 靠分隔行那一组（在中间）把表头和数据行分开；列数 = 分隔行的格数。
     */
    fun parseInline(line: String): MarkdownBlock.Table? {
        val cells = splitRow(line)
        if (cells.size < 3) return null
        var dividerStart = -1
        var dividerCount = 0
        for (index in cells.indices) {
            val cell = cells[index].trim()
            val isDividerCell = cell.isNotEmpty() && cell.matches(Regex("^:?-+:?$"))
            if (isDividerCell) {
                if (dividerStart < 0) dividerStart = index
                dividerCount += 1
            } else if (dividerStart >= 0) {
                break
            }
        }
        if (dividerStart < 0 || dividerCount == 0) return null
        val header = cells.take(dividerStart)
        val body = cells.drop(dividerStart + dividerCount)
        if (header.isEmpty() || body.isEmpty()) return null
        val width = dividerCount
        val alignments = List(width) { MarkdownTables.Alignment.LEFT }
        val rows = body.chunked(width).map { chunk ->
            List(width) { index -> chunk.getOrNull(index).orEmpty() }
        }
        return MarkdownBlock.Table(
            header = List(width) { index -> header.getOrNull(index).orEmpty() },
            alignments = alignments,
            rows = rows,
        )
    }

    /**
     * 解析规范表格：表头行 + 分隔行 + 若干数据行。
     *
     * 模型也常把整张表挤在一行（`| a | b | --- | --- | 1 | 2 |`）——
     * 那时靠分隔行在中间把表头和数据行分开（见 [parseInline]）。
     *
     * @return 表格和「消费到第几行」；不是表格就返回 null。
     */
    fun parse(
        lines: List<String>,
        startIndex: Int,
    ): Pair<MarkdownBlock.Table, Int>? {
        if (startIndex + 1 >= lines.size) return null
        val headerLine = lines[startIndex]
        val separatorLine = lines[startIndex + 1]
        if (!isSeparator(separatorLine)) {
            // 挤在一行的形态。
            val inline = parseInline(headerLine) ?: return null
            return inline to (startIndex + 1)
        }

        val headers = splitRow(headerLine)
        val separatorCells = splitRow(separatorLine)
        if (headers.isEmpty() || separatorCells.isEmpty()) return null

        val alignments = separatorCells.map { cell ->
            val trimmed = cell.trim()
            when {
                trimmed.startsWith(':') && trimmed.endsWith(':') -> Alignment.CENTER
                trimmed.endsWith(':') -> Alignment.RIGHT
                else -> Alignment.LEFT
            }
        }
        val width = headers.size

        var index = startIndex + 2
        val rows = mutableListOf<List<String>>()
        while (index < lines.size) {
            val row = lines[index]
            if (row.isBlank() || !row.contains('|')) break
            val cells = splitRow(row)
            rows += List(width) { column -> cells.getOrNull(column).orEmpty() }
            index += 1
        }
        return MarkdownBlock.Table(headers, alignments, rows) to index
    }
}
