package com.dshm.bot.ui.settings

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * `usage` 的聚合：端点给的是「每小时 × 归属 × 模型」的行，界面按范围自己合计。
 *
 * 行里的时间形如 `2026-11-08 14`（en-CA + 小时），所以按字符串前缀就能筛天。
 */
data class UsageTotals(val tokens: Long, val calls: Long, val cacheHit: Float?)

private val HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd HH")

/** 从 `now` 往回 [days] 天的起点（1 = 今天）；解析不了就不筛。 */
internal fun usageCutoff(now: String, days: Int): String? = runCatching {
    LocalDate.parse(usageDay(now) ?: return null)
        .minusDays((days - 1).toLong())
        .atStartOfDay()
        .format(HOUR)
}.getOrNull()

/** `now` 可能是 `2026-11-08 14` 也可能带分钟/秒，统一取日期部分。 */
private fun usageDay(now: String): String? =
    Regex("""(\d{4}-\d{2}-\d{2})""").find(now)?.groupValues?.get(1)

/** 一段时间（[cutoff] 之后）的合计。 */
internal fun usageTotals(rows: List<TeamUsageRow>, cutoff: String?): UsageTotals {
    var tokens = 0L
    var calls = 0L
    var input = 0L
    var read = 0L
    for (row in rows) {
        if (cutoff != null && row.hour < cutoff) continue
        tokens += row.tokens
        calls += row.calls
        input += row.input
        read += row.cacheRead
    }
    return UsageTotals(tokens, calls, if (input + read > 0L) read.toFloat() / (input + read) else null)
}

/** 按天汇总 tokens，日期升序。 */
internal fun usageByDay(rows: List<TeamUsageRow>, cutoff: String?): List<Pair<String, Long>> {
    val days = sortedMapOf<String, Long>()
    for (row in rows) {
        if (cutoff != null && row.hour < cutoff) continue
        val day = row.hour.take(10)
        days[day] = (days[day] ?: 0L) + row.tokens
    }
    return days.entries.map { it.key to it.value }
}

/** 按归属汇总（键是 `usage.owners` 的下标），按 tokens 降序。 */
internal fun usageByOwner(rows: List<TeamUsageRow>, cutoff: String?): List<Pair<Int, UsageTotals>> =
    groupTotals(rows, cutoff) { it.owner }

/** 按模型汇总（键是 `usage.models` 的下标），按 tokens 降序。 */
internal fun usageByModel(rows: List<TeamUsageRow>, cutoff: String?): List<Pair<Int, UsageTotals>> =
    groupTotals(rows, cutoff) { it.model }

private fun groupTotals(
    rows: List<TeamUsageRow>,
    cutoff: String?,
    keyOf: (TeamUsageRow) -> Int,
): List<Pair<Int, UsageTotals>> {
    val tokens = mutableMapOf<Int, Long>()
    val calls = mutableMapOf<Int, Long>()
    val input = mutableMapOf<Int, Long>()
    val read = mutableMapOf<Int, Long>()
    for (row in rows) {
        if (cutoff != null && row.hour < cutoff) continue
        val key = keyOf(row)
        tokens[key] = (tokens[key] ?: 0L) + row.tokens
        calls[key] = (calls[key] ?: 0L) + row.calls
        input[key] = (input[key] ?: 0L) + row.input
        read[key] = (read[key] ?: 0L) + row.cacheRead
    }
    return tokens.entries
        .filter { it.value > 0L }
        .sortedByDescending { it.value }
        .map { (key, value) ->
            val total = input[key].orZero() + read[key].orZero()
            key to UsageTotals(
                tokens = value,
                calls = calls[key] ?: 0L,
                cacheHit = if (total > 0L) read[key].orZero().toFloat() / total else null,
            )
        }
}

private fun Long?.orZero(): Long = this ?: 0L
