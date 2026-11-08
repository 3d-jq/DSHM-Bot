package com.dshm.bot.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** `usage` 的合计/分组：范围、按天、按归属、按模型。 */
class TeamUsageTest {

    private val rows = listOf(
        TeamUsageRow("2026-11-08 10", owner = 0, model = 0, input = 100, cacheRead = 900, cacheWrite = 0, output = 50, calls = 2),
        TeamUsageRow("2026-11-08 11", owner = 1, model = 1, input = 10, cacheRead = 0, cacheWrite = 0, output = 5, calls = 1),
        // 七天之外，应当被筛掉。
        TeamUsageRow("2026-11-01 09", owner = 0, model = 0, input = 1000, cacheRead = 0, cacheWrite = 0, output = 0, calls = 9),
    )

    @Test
    fun buildsTheCutoffFromNow() {
        assertEquals("2026-11-02 00", usageCutoff("2026-11-08 20", 7))
        assertEquals("2026-11-08 00", usageCutoff("2026-11-08 20", 1))
        assertEquals(null, usageCutoff("", 7))
    }

    @Test
    fun totalsSkipRowsBeforeTheCutoff() {
        val totals = usageTotals(rows, usageCutoff("2026-11-08 20", 7))
        assertEquals(1_065L, totals.tokens)
        assertEquals(3L, totals.calls)
        // 100+10 prompt 里 900 是缓存命中。
        assertEquals(900f / 1_010f, totals.cacheHit!!, 0.0001f)
    }

    @Test
    fun groupsByOwnerAndModelBySize() {
        val cutoff = usageCutoff("2026-11-08 20", 7)
        val owners = usageByOwner(rows, cutoff)
        assertEquals(listOf(0, 1), owners.map { it.first })
        assertEquals(1_050L, owners[0].second.tokens)
        assertEquals(15L, owners[1].second.tokens)

        val models = usageByModel(rows, cutoff)
        assertEquals(listOf(0, 1), models.map { it.first })
    }

    @Test
    fun sumsADayInOrder() {
        val days = usageByDay(rows, usageCutoff("2026-11-08 20", 7))
        assertEquals(listOf("2026-11-08" to 1_065L), days)
    }
}
