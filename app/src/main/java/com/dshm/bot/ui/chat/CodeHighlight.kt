package com.dshm.bot.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * 代码块高亮（对齐 DeepSearch `ui/markdown/SyntaxHighlighter.kt`）。
 *
 * 不是完整词法分析器：把注释 / 字符串 / 数字 / 关键字 / 类型各上一色，
 * 聊天里的代码片段已经够读。深浅色各一套调色板。
 */
internal data class CodePalette(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val type: Color,
    val plain: Color,
)

internal fun codePalette(isDark: Boolean): CodePalette = if (isDark) {
    CodePalette(
        keyword = Color(0xFF569CD6),
        string = Color(0xFFCE9178),
        comment = Color(0xFF6A9955),
        number = Color(0xFFB5CEA8),
        type = Color(0xFF4EC9B0),
        plain = Color(0xFFD4D4D4),
    )
} else {
    CodePalette(
        keyword = Color(0xFF0033B3),
        string = Color(0xFFA31515),
        comment = Color(0xFF008000),
        number = Color(0xFF098658),
        type = Color(0xFF267F99),
        plain = Color(0xFF1F1F1F),
    )
}

private val KEYWORDS = setOf(
    "if", "else", "for", "while", "return", "break", "continue", "switch", "case",
    "default", "try", "catch", "finally", "throw", "new", "delete", "typeof", "instanceof",
    "class", "interface", "enum", "struct", "func", "fun", "fn", "def", "async", "await",
    "import", "export", "from", "package", "module", "use", "pub", "private", "public",
    "protected", "static", "final", "const", "let", "var", "val", "mut",
    "true", "false", "null", "nil", "None", "self", "this", "super",
    "in", "is", "as", "not", "and", "or", "with", "yield", "lambda",
    "override", "abstract", "sealed", "data", "object", "companion",
    "suspend", "inline", "when", "where", "guard", "defer", "do",
)

private val TYPES = setOf(
    "int", "float", "double", "string", "bool", "boolean", "void", "char", "byte",
    "long", "short", "String", "Int", "Float", "Double", "Bool", "Boolean",
    "List", "Map", "Set", "Array", "HashMap", "ArrayList", "Optional",
)

private val TOKEN = Regex(
    """(//[^\n]*|\#[^\n]*|/\*[\s\S]*?\*/)|("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`)|(\b\d+\.?\d*\b)|(\b[a-zA-Z_]\w*\b)|([^\s\w]+|\s+)""",
)

/** 给一段代码上色；语言为空时按纯文本返回（仍可着色常见标记）。 */
internal fun highlightCode(code: String, language: String, palette: CodePalette): AnnotatedString =
    buildAnnotatedString {
        for (match in TOKEN.findAll(code)) {
            val comment = match.groups[1]?.value
            val string = match.groups[2]?.value
            val number = match.groups[3]?.value
            val word = match.groups[4]?.value
            val other = match.groups[5]?.value
            val color = when {
                comment != null -> palette.comment
                string != null -> palette.string
                number != null -> palette.number
                word != null -> when {
                    word in KEYWORDS -> palette.keyword
                    word in TYPES -> palette.type
                    word.first().isUpperCase() -> palette.type
                    else -> palette.plain
                }
                else -> palette.plain
            }
            pushStyle(SpanStyle(color = color))
            append(comment ?: string ?: number ?: word ?: other ?: "")
            pop()
        }
    }
