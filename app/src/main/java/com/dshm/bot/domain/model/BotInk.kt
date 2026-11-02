package com.dshm.bot.domain.model

/**
 * DS Bot 的 Bot 颜色表与 `defaultColor` 算法。
 *
 * 移植自 DS Bot（Apache-2.0）的 `src/client/inks.js` 与 `src/client/characters.js`，
 * 与 dsh-Go 的 `packages/dsh_client/lib/src/dsbot_api.dart` 保持同一结果：
 * 同一个 Bot id 在任何设备上得到同一个颜色。算法使用 32 位无符号整数语义，
 * 所以这里统一用 [Long] + 掩码模拟，避免 Kotlin [Int] 溢出差异。
 */
data class BotInk(val id: String, val lightArgb: Long, val darkArgb: Long)

object BotInks {
    private const val MASK: Long = 0xFFFFFFFFL
    private const val FNV_OFFSET: Long = 2166136261L
    private const val FNV_PRIME: Long = 16777619L

    private val hexPattern = Regex("^#[0-9a-fA-F]{6}$")

    /** 旧版颜色 id 的迁移表（DS Bot 保留兼容）。 */
    private val legacyInks = mapOf(
        "kimi" to "charcoal",
        "zai" to "graphite",
        "minimax" to "rose",
        "stepfun" to "sky",
        "mimo" to "tangerine",
        "qwen" to "cobalt",
    )

    /** 没有手选颜色时按 id 分配的 8 个默认色，顺序与 DS Bot 一致。 */
    private val defaultOrder = listOf("red", "orange", "yellow", "green", "cyan", "blue", "violet", "magenta")

    /** 新建 Bot 时可选颜色，顺序与 DS Bot 的选择器一致。 */
    val pickerOrder: List<String> = listOf(
        "red", "orange", "yellow", "green", "cyan", "blue", "violet", "magenta", "gray", "deepseek",
    )

    private val inks: Map<String, BotInk> = listOf(
        BotInk("deepseek", 0xFF4D6BFE, 0xFF6A84FF),
        BotInk("charcoal", 0xFF141416, 0xFFF1F2F4),
        BotInk("graphite", 0xFF2D2D2D, 0xFFD6D6DA),
        BotInk("rose", 0xFFEC2F6B, 0xFFFF5A7A),
        BotInk("sky", 0xFF0AA8D6, 0xFF2EE6E0),
        BotInk("tangerine", 0xFFFF6900, 0xFFFF8A2E),
        BotInk("cobalt", 0xFF082DFF, 0xFF5C78FF),
        BotInk("black", 0xFF000000, 0xFFFFFFFF),
        BotInk("brown", 0xFFA27952, 0xFF855C36),
        BotInk("red", 0xFFFF3E51, 0xFFE02135),
        BotInk("orange", 0xFFFF781C, 0xFFFF6700),
        BotInk("yellow", 0xFFFFAF38, 0xFFFF9800),
        BotInk("green", 0xFF00C972, 0xFF009957),
        BotInk("cyan", 0xFF1CC3B0, 0xFF00A592),
        BotInk("blue", 0xFF2A92FE, 0xFF0E74E0),
        BotInk("violet", 0xFFA97EFE, 0xFF804EE0),
        BotInk("magenta", 0xFFFF5EB1, 0xFFE02A88),
        BotInk("gray", 0xFF959595, 0xFF777777),
        BotInk("aurora", 0xFF00B8D4, 0xFF2EE6E0),
    ).associateBy { it.id }

    /** 旧颜色 id 迁移到当前 id。 */
    fun currentColor(value: String): String = legacyInks[value] ?: value

    /** 是否是合法颜色（`#rrggbb` 或颜色 id）。 */
    fun isColor(value: String?): Boolean {
        if (value == null) return false
        return hexPattern.matches(value) || inks.containsKey(currentColor(value))
    }

    /**
     * 颜色解析：`#rrggbb` 原样返回，颜色 id 走色表，未知值回落到 gray。
     * 返回值是 0xAARRGGBB。
     */
    fun color(value: String?, dark: Boolean): Long {
        if (value != null && hexPattern.matches(value)) {
            return 0xFF000000L or value.substring(1).toLong(16)
        }
        val ink = inks[currentColor(value.orEmpty())] ?: inks.getValue("gray")
        return if (dark) ink.darkArgb else ink.lightArgb
    }

    /** FNV-1a，按 UTF-16 code unit 计算（与 DS Bot 的 JS 实现一致）。 */
    fun fnv1a(text: String): Long {
        var value = FNV_OFFSET
        for (unit in text) {
            value = imul(value xor unit.code.toLong(), FNV_PRIME)
        }
        return value
    }

    /** 没有手选颜色时，Bot 按 id 固定分到的颜色 id（DS Bot `defaultColor`）。 */
    fun defaultColorId(id: String): String {
        val t = u32(fnv1a(id) + 1831565813L)
        var n = imul(t xor (t ushr 15), 1L or t)
        n = u32(u32(n + imul(n xor (n ushr 7), 61L or n)) xor n)
        val unit = u32(n xor (n ushr 14)).toDouble() / 4294967296.0
        return defaultOrder[(unit * defaultOrder.size).toInt().coerceIn(0, defaultOrder.lastIndex)]
    }

    private fun u32(value: Long): Long = value and MASK

    /** 32 位乘法（JS `Math.imul`）：保留低 32 位。 */
    private fun imul(a: Long, b: Long): Long = (a * b) and MASK
}
