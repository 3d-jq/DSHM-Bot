package com.dshm.bot.ui.chat

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import org.json.JSONObject

/**
 * 视图蓝图里的公式：`{"=": "price * qty"}`。
 *
 * DS Bot 的 `src/shared/formula.js` 是一套很小的表达式语言：只能读名字、调固定几个函数，
 * 没有属性访问、赋值和循环，所以输入的每个变化都能就地重算，Bot 写一次计算就不用再调模型。
 * 这里按同一套语法、函数表和边界行为移植，好让同一个蓝图两边算出一个数。
 */
internal class ChatFormulaException(message: String) : Exception(message)

/**
 * 编译好的公式。解析失败返回 `null`（蓝图由 DS Bot 校验过，正常不会失败）。
 */
internal class ChatFormula private constructor(private val tree: Node) {

    /** 算一次；名字从 [lookup] 取（未知名字 / 算不出来时返回错误）。 */
    fun evaluate(lookup: (String) -> Any?): FormulaOutcome = try {
        FormulaOutcome.Value(run(tree, lookup))
    } catch (error: ChatFormulaException) {
        FormulaOutcome.Failure(error.message.orEmpty())
    } catch (error: StackOverflowError) {
        FormulaOutcome.Failure("公式嵌套太深，算不出来")
    }

    companion object {
        /** 语法树里的名字（公式读了哪些输入 / `let`）。 */
        fun isName(value: String): Boolean =
            value.isNotEmpty() && (value[0].isLetter() || value[0] == '_') &&
                value.drop(1).all { it.isLetterOrDigit() || it == '_' }

        fun compile(source: Any?): ChatFormula? {
            if (source !is String) return null
            return try {
                ChatFormula(parse(source))
            } catch (_: ChatFormulaException) {
                null
            } catch (_: StackOverflowError) {
                null
            }
        }

        /** 公式读了哪些名字（DS Bot 用它校验「公式只读自己声明过的东西」）。 */
        fun namesIn(formula: ChatFormula): Set<String> = namesIn(formula.tree, mutableSetOf())
    }
}

internal sealed interface FormulaOutcome {
    data class Value(val value: Any?) : FormulaOutcome
    data class Failure(val message: String) : FormulaOutcome
}

// ---------------------------------------------------------------------------
// 名字作用域：输入值 + 视图声明的 let（let 按需求值，互相引用就成了环）

/**
 * 一张视图的取值域。
 *
 * 输入值直接命中；其余名字去 [lets] 里找公式，算完缓存。环或不存在的名字按 DS Bot
 * 一样让读到它的公式失败。
 */
internal class ChatViewScope(
    inputs: Map<String, Any?>,
    lets: Map<String, ChatFormula>,
) {
    private val inputs = inputs.toMap()
    private val lets = lets.toMap()
    private val done = mutableMapOf<String, Any?>()
    private val busy = mutableSetOf<String>()

    /** 读一个名字；不存在或成环时抛 [ChatFormulaException]。 */
    fun lookup(name: String): Any? {
        if (inputs.containsKey(name)) return inputs[name]
        if (done.containsKey(name)) return done[name]
        val formula = lets[name] ?: failScope("unknown name \"$name\"")
        if (!busy.add(name)) failScope("\"$name\" refers to itself")
        try {
            val outcome = formula.evaluate(::lookup)
            val value = when (outcome) {
                is FormulaOutcome.Value -> outcome.value
                is FormulaOutcome.Failure -> throw ChatFormulaException(outcome.message)
            }
            done[name] = value
            return value
        } finally {
            busy.remove(name)
        }
    }

    /** 算出 `{"=": …}` 或直接给出字面量；公式有问题时给 [fallback]。 */
    fun resolve(value: Any?, fallback: Any? = null): Any? {
        val formula = asFormula(value) ?: return value
        val outcome = formula.evaluate(::lookup)
        return when (outcome) {
            is FormulaOutcome.Value -> outcome.value
            is FormulaOutcome.Failure -> fallback
        }
    }

    /** 算出一个数字（百分比、图表值这些要数的地方）。 */
    fun resolveNumber(value: Any?, fallback: Double = 0.0): Double {
        val resolved = resolve(value, null) ?: return fallback
        return num(resolved, fallback)
    }

    companion object {
        /** 蓝图里的 `{"=": "…"}` 是公式，别的都是字面量。 */
        fun asFormula(value: Any?): ChatFormula? {
            val json = value as? JSONObject ?: return null
            if (!json.has("=")) return null
            return ChatFormula.compile(json.opt("="))
        }

        /** 一个容器（`stack` / `row`）自带的 `let` 名字表。 */
        fun letsOf(node: JSONObject): Map<String, ChatFormula> {
            val raw = node.optJSONObject("let") ?: return emptyMap()
            val out = mutableMapOf<String, ChatFormula>()
            raw.keys().forEach { name ->
                ChatFormula.compile(raw.opt(name))?.let { out[name] = it }
            }
            return out
        }
    }
}

private fun failScope(message: String): Nothing = throw ChatFormulaException(message)

// ---------------------------------------------------------------------------
// 语法树与解析

private sealed interface Node
private data class LiteralNode(val value: Any?) : Node
private data class NameNode(val name: String) : Node
private data class UnaryNode(val op: String, val value: Node) : Node
private data class BinaryNode(val op: String, val left: Node, val right: Node) : Node
private data class IfNode(val test: Node, val yes: Node, val no: Node) : Node
private data class CallNode(val name: String, val args: List<Node>) : Node

private const val MAX_NESTING = 64

private val IDENT = Regex("^[A-Za-z_][A-Za-z0-9_]*")
private val NUMBER = Regex("^(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?")
private val OPERATORS = listOf(
    "==", "!=", "<=", ">=", "&&", "||", "+", "-", "*", "/", "%", "<", ">", "!", "?", ":", "(", ")", ",",
)
private val BINARY_LEVELS = listOf(
    listOf("||"),
    listOf("&&"),
    listOf("==", "!="),
    listOf("<", "<=", ">", ">="),
    listOf("+", "-"),
    listOf("*", "/", "%"),
)
private val CONSTANTS = mapOf("true" to true, "false" to false)

private enum class TokenKind { Number, Name, Text, Operator }

private data class Token(val kind: TokenKind, val text: String, val number: Double = 0.0, val at: Int = 0)

private fun fail(message: String): Nothing = throw ChatFormulaException(message)

private fun tokenize(source: String): List<Token> {
    val tokens = mutableListOf<Token>()
    var at = 0
    while (at < source.length) {
        val char = source[at]
        if (char.isWhitespace()) {
            at += 1
            continue
        }
        val rest = source.substring(at)
        NUMBER.find(rest)?.takeIf { it.range.first == 0 }?.let { match ->
            tokens += Token(TokenKind.Number, match.value, match.value.toDoubleOrNull() ?: fail("bad number"), at)
            at += match.value.length
            return@let
        } ?: run {
            IDENT.find(rest)?.takeIf { it.range.first == 0 }?.let { match ->
                tokens += Token(TokenKind.Name, match.value, at = at)
                at += match.value.length
                return@run
            }
            if (char == '"' || char == '\'') {
                var end = at + 1
                val text = StringBuilder()
                while (end < source.length && source[end] != char) {
                    if (source[end] == '\\' && end + 1 < source.length) {
                        text.append(source[end + 1])
                        end += 2
                        continue
                    }
                    text.append(source[end])
                    end += 1
                }
                if (end >= source.length) fail("unclosed string at ${at + 1}")
                tokens += Token(TokenKind.Text, text.toString(), at = at)
                at = end + 1
                return@run
            }
            val operator = OPERATORS.firstOrNull { rest.startsWith(it) }
                ?: fail("unexpected \"$char\" at ${at + 1}")
            tokens += Token(TokenKind.Operator, operator, at = at)
            at += operator.length
        }
    }
    return tokens
}

private class Parser(private val tokens: List<Token>) {
    private var index = 0
    private var depth = 0

    private fun peek(): Token? = tokens.getOrNull(index)
    private fun isOp(value: String) = peek()?.let { it.kind == TokenKind.Operator && it.text == value } == true

    private fun expect(value: String) {
        val token = peek()
        if (token == null) fail("expected \"$value\" at the end")
        if (token.kind != TokenKind.Operator || token.text != value) fail("expected \"$value\" at ${token.at + 1}")
        index += 1
    }

    private fun <T> nest(read: () -> T): T {
        depth += 1
        if (depth > MAX_NESTING) fail("nested more than $MAX_NESTING levels")
        val node = read()
        depth -= 1
        return node
    }

    fun parse(): Node {
        if (tokens.isEmpty()) fail("the formula is empty")
        val tree = ternary()
        peek()?.let { fail("unexpected \"${it.text}\" at ${it.at + 1}") }
        return tree
    }

    private fun ternary(): Node = nest {
        val test = binary(0)
        if (!isOp("?")) return@nest test
        index += 1
        val yes = ternary()
        expect(":")
        IfNode(test, yes, ternary())
    }

    private fun binary(level: Int): Node {
        if (level == BINARY_LEVELS.size) return unary()
        var left = binary(level + 1)
        while (peek()?.let { it.kind == TokenKind.Operator && it.text in BINARY_LEVELS[level] } == true) {
            val op = peek()!!.text
            index += 1
            left = BinaryNode(op, left, binary(level + 1))
        }
        return left
    }

    private fun unary(): Node {
        val token = peek()
        if (token != null && token.kind == TokenKind.Operator && (token.text == "-" || token.text == "!" || token.text == "+")) {
            index += 1
            return nest { UnaryNode(token.text, unary()) }
        }
        return primary()
    }

    private fun primary(): Node {
        val token = peek() ?: fail("the formula ends too early")
        index += 1
        when (token.kind) {
            TokenKind.Number -> return LiteralNode(token.number)
            TokenKind.Text -> return LiteralNode(token.text)
            else -> Unit
        }
        if (token.kind == TokenKind.Operator && token.text == "(") {
            val inner = ternary()
            expect(")")
            return inner
        }
        if (token.kind != TokenKind.Name) fail("unexpected \"${token.text}\" at ${token.at + 1}")
        if (!isOp("(")) {
            return if (CONSTANTS.containsKey(token.text)) LiteralNode(CONSTANTS[token.text]) else NameNode(token.text)
        }
        val fn = FUNCTIONS[token.text]
            ?: fail("unknown function \"${token.text}\"; allowed: ${FUNCTIONS.keys.joinToString(", ")}")
        index += 1
        val args = mutableListOf<Node>()
        if (!isOp(")")) {
            args += ternary()
            while (isOp(",")) {
                index += 1
                args += ternary()
            }
        }
        expect(")")
        if (args.size < fn.min || args.size > fn.max) {
            val wanted = when {
                fn.min == fn.max -> "${fn.min}"
                fn.max == Int.MAX_VALUE -> "at least ${fn.min}"
                else -> "${fn.min} or ${fn.max}"
            }
            fail("${token.text}() takes $wanted argument${if (fn.max == 1) "" else "s"}; got ${args.size}")
        }
        return CallNode(token.text, args)
    }
}

private fun parse(source: String): Node = Parser(tokenize(source)).parse()

private fun namesIn(node: Node, found: MutableSet<String>): Set<String> {
    when (node) {
        is NameNode -> found.add(node.name)
        is UnaryNode -> namesIn(node.value, found)
        is BinaryNode -> {
            namesIn(node.left, found)
            namesIn(node.right, found)
        }

        is IfNode -> {
            namesIn(node.test, found)
            namesIn(node.yes, found)
            namesIn(node.no, found)
        }

        is CallNode -> node.args.forEach { namesIn(it, found) }
        is LiteralNode -> Unit
    }
    return found
}

// ---------------------------------------------------------------------------
// 求值

private fun run(node: Node, lookup: (String) -> Any?): Any? = when (node) {
    is LiteralNode -> node.value
    is NameNode -> lookup(node.name)
    is UnaryNode -> {
        val value = run(node.value, lookup)
        when (node.op) {
            "!" -> !truthy(value)
            "-" -> -num(value)
            else -> num(value)
        }
    }

    is IfNode -> if (truthy(run(node.test, lookup))) run(node.yes, lookup) else run(node.no, lookup)
    is CallNode -> funOf(node.name).run(node.args.map { run(it, lookup) })
    is BinaryNode -> binaryOf(node, lookup)
}

private fun binaryOf(node: BinaryNode, lookup: (String) -> Any?): Any? {
    if (node.op == "&&") {
        return if (truthy(run(node.left, lookup))) run(node.right, lookup) else false
    }
    if (node.op == "||") {
        val left = run(node.left, lookup)
        return if (truthy(left)) left else run(node.right, lookup)
    }
    val left = run(node.left, lookup)
    val right = run(node.right, lookup)
    return when (node.op) {
        "+" -> if (left is String || right is String) "${show(left)}${show(right)}" else num(left) + num(right)
        "-" -> num(left) - num(right)
        "*" -> num(left) * num(right)
        "/" -> num(left) / num(right)
        "%" -> num(left) % num(right)
        "==" -> equal(left, right)
        "!=" -> !equal(left, right)
        "<" -> num(left) < num(right)
        "<=" -> num(left) <= num(right)
        ">" -> num(left) > num(right)
        else -> num(left) >= num(right)
    }
}

private fun truthy(value: Any?): Boolean = when (value) {
    is String -> value.isNotEmpty()
    null -> false
    is Boolean -> value
    is Number -> value.toDouble() != 0.0
    else -> true
}

private fun equal(left: Any?, right: Any?): Boolean {
    if (left is Number && right is Number) return left.toDouble() == right.toDouble()
    return left == right
}

private fun num(value: Any?): Double = when (value) {
    is Double -> value
    is Int -> value.toDouble()
    is Long -> value.toDouble()
    is Boolean -> if (value) 1.0 else 0.0
    is String -> {
        val trimmed = value.trim()
        val parsed = trimmed.toDoubleOrNull()
        if (trimmed.isNotEmpty() && parsed != null) parsed else fail("${quote(value)} is not a number")
    }

    else -> fail("${quote(value)} is not a number")
}

private fun num(value: Any?, fallback: Double): Double = try {
    num(value)
} catch (_: ChatFormulaException) {
    fallback
}

private fun quote(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"$value\""
    else -> value.toString()
}

/** 值当文字看：数字去掉浮点噪声（`0.1 + 0.2` 显示 `0.3`）。 */
internal fun show(value: Any?): String = when (value) {
    null -> ""
    is Double -> if (value.isFinite()) {
        BigDecimal(value).round(MathContext(12)).stripTrailingZeros().toPlainString()
    } else {
        value.toString()
    }

    else -> value.toString()
}

// ---------------------------------------------------------------------------
// 函数表（和 DS Bot `FORMULA_FUNCTIONS` 同名同参数个数）

private class Fn(val min: Int, val max: Int, val run: (List<Any?>) -> Any?)

private val FUNCTIONS: Map<String, Fn> = mapOf(
    "min" to Fn(1, Int.MAX_VALUE) { values -> values.minOf { num(it) } },
    "max" to Fn(1, Int.MAX_VALUE) { values -> values.maxOf { num(it) } },
    "abs" to Fn(1, 1) { values -> Math.abs(num(values[0])) },
    "floor" to Fn(1, 1) { values -> Math.floor(num(values[0])) },
    "ceil" to Fn(1, 1) { values -> Math.ceil(num(values[0])) },
    "sqrt" to Fn(1, 1) { values -> Math.sqrt(num(values[0])) },
    "pow" to Fn(2, 2) { values -> Math.pow(num(values[0]), num(values[1])) },
    "round" to Fn(1, 2) { values -> roundTo(num(values[0]), digitsOf(values.getOrNull(1))) },
    "clamp" to Fn(3, 3) { values ->
        Math.min(Math.max(num(values[0]), num(values[1])), num(values[2]))
    },

    "fixed" to Fn(1, 2) { values ->
        val digits = digitsOf(values.getOrNull(1))
        toFixed(roundTo(num(values[0]), digits), digits)
    },

    "format" to Fn(1, 2) { values -> grouped(num(values[0]), digitsOf(values.getOrNull(1))) },
    "percent" to Fn(1, 2) { values ->
        "${grouped(num(values[0]) * 100, digitsOf(values.getOrNull(1)))}%"
    },
)

private fun funOf(name: String): Fn = FUNCTIONS[name] ?: fail("unknown function \"$name\"")

private fun digitsOf(value: Any?): Int {
    val digits = num(value ?: 0.0)
    if (digits != Math.floor(digits) || digits < 0 || digits > 10) {
        fail("digits must be a whole number from 0 to 10")
    }
    return digits.toInt()
}

private fun roundTo(value: Double, digits: Int): Double {
    val scale = Math.pow(10.0, digits.toDouble())
    return Math.round(value * scale) / scale
}

private fun toFixed(value: Double, digits: Int): String =
    BigDecimal(value).setScale(digits, RoundingMode.HALF_UP).toPlainString()

private fun grouped(value: Double, digits: Int): String {
    if (!value.isFinite()) return value.toString()
    val text = BigDecimal(roundTo(Math.abs(value), digits)).setScale(digits, RoundingMode.HALF_UP).toPlainString()
    val dot = text.indexOf('.')
    val whole = if (dot < 0) text else text.substring(0, dot)
    val part = if (dot < 0) "" else text.substring(dot + 1)
    val groupedWhole = whole.reversed().chunked(3).joinToString(",").reversed()
    val zero = whole.all { it == '0' } && part.all { it == '0' }
    val sign = if (value < 0 && !zero) "-" else ""
    return if (part.isEmpty()) "$sign$groupedWhole" else "$sign$groupedWhole.$part"
}
