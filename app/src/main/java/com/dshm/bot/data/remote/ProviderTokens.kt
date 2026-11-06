package com.dshm.bot.data.remote

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 模型容量（token 数）的显示与解析，对齐 dsh-Go
 * `app/lib/state/providers_state.dart` 的 `formatTokens` / `parseTokenCount`。
 *
 * 容量在界面上按「模型厂商的报价习惯」缩写（`262144` → `256K`），
 * 用户也可以直接敲 `128K` / `1.5M` / `131072`。
 */

/** `262144` → `256K`；不是整数单位就原样返回。 */
fun formatTokens(value: Int): String {
    if (value >= 1_000_000 && value % 1_000_000 == 0) return "${value / 1_000_000}M"
    if (value >= 1_048_576 && value % 1_048_576 == 0) return "${value / 1_048_576}M"
    if (value >= 1024 && value % 1024 == 0) return "${value / 1024}K"
    if (value >= 1000 && value % 1000 == 0) return "${value / 1000}K"
    return value.toString()
}

/**
 * 解析用户敲的 token 数：`131072` / `128K` / `1.5M` / `200k`。
 * K 是 1000、M 是 1000000（模型容量的报价习惯），分隔符和空格忽略。
 * 空、不是数字、或者不是一个正整数时返回 null。
 */
fun parseTokenCount(raw: String): Int? {
    val text = raw.replace(Regex("[\\s,_]"), "")
    val match = Regex("^(\\d+(?:\\.\\d+)?)([kKmM])?$").matchEntire(text) ?: return null
    val scale = when (match.groupValues[2].lowercase()) {
        "k" -> 1_000
        "m" -> 1_000_000
        else -> 1
    }
    val scaled = match.groupValues[1].toDouble() * scale
    val rounded = scaled.roundToInt()
    if (abs(scaled - rounded) > 1e-6 || rounded <= 0) return null
    return rounded
}

/** 输入框里的文本是不是合法的 token 数（空是合法的，表示回到渠道默认值）。 */
fun isValidTokenCount(raw: String): Boolean =
    raw.isBlank() || parseTokenCount(raw) != null
