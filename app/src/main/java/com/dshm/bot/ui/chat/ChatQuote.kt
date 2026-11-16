package com.dshm.bot.ui.chat

/**
 * 引用追问（对齐 dsh-Go `app/lib/state/composer_quote.dart`）。
 *
 * DSH 的 prompt 本身没有「引用」字段（官方输入框只发文本、附件和 `@` 引用），所以
 * 引用是以 **Markdown 引用块**的形式放在消息最前面的 —— 模型读到的就是「关于这段」。
 * 气泡里它会重新显示成引用块（我们的 [MarkdownText] 本来就把 `> ` 渲染成引用块）。
 */

/** 最多引用多少字符；超出以「…」截断。 */
internal const val MAX_QUOTE_LENGTH = 1200

private val TRAILING_SPACES = Regex("[ \\t]+\\n")
private val BLANK_RUN = Regex("\\n{3,}")

/**
 * 把要引用的原文整理干净：统一换行、去掉行尾空白、连续空行折成一个空行、首尾 trim，
 * 并截到 [MAX_QUOTE_LENGTH]（按**码点**算，避免把 emoji 切成半个）。
 */
internal fun normalizeQuote(raw: String): String {
    var text = raw
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(TRAILING_SPACES, "\n")
        .replace(BLANK_RUN, "\n\n")
        .trim()
    val points = text.codePoints().toArray()
    if (points.size > MAX_QUOTE_LENGTH) {
        text = String(points, 0, MAX_QUOTE_LENGTH).trimEnd() + "…"
    }
    return text
}

/**
 * [text] 带上 [quote] 之后真正发出去的内容：引用每一行前面加 `> `（空行写成裸 `>`
 * 让引用块不断开），空一行，再跟正文。正文为空时只发引用块。
 */
internal fun withQuote(quote: String, text: String): String {
    val passage = normalizeQuote(quote)
    if (passage.isEmpty()) return text
    val block = passage.split('\n').joinToString("\n") { line ->
        if (line.isEmpty()) ">" else "> $line"
    }
    val body = text.trim()
    return if (body.isEmpty()) block else "$block\n\n$body"
}

private val QUOTE_LINE = Regex("^ {0,3}> ?")
private val MARKDOWN_MARKS = Regex("^\\s{0,3}(#{1,6}|>+|[-*+]|\\d+[.)])\\s+", RegexOption.MULTILINE)
private val INLINE_MARKS = Regex("\\*\\*|__|`+|~~")
private val WHITESPACE = Regex("\\s+")

/**
 * 引用的一句话预览（给输入框上方的引用条用）：去掉 Markdown 标记（强调、行内代码、
 * 标题与列表前缀、嵌套 `>`），空白折成一个空格。
 */
internal fun quoteLine(quote: String): String = quote
    .replace(MARKDOWN_MARKS, "")
    .replace(INLINE_MARKS, "")
    .replace(WHITESPACE, " ")
    .trim()
