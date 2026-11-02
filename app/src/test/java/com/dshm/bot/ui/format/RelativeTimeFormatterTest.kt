package com.dshm.bot.ui.format

import com.dshm.bot.TestTime
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeFormatterTest {

    private val formatter = RelativeTimeFormatter(TestTime.zone, TestTime.clock, TestTime.locale)

    @Test
    fun todayShowsTime() {
        // 本地 21:00（UTC 13:00）。
        assertEquals("21:00", formatter.format(Instant.parse("2026-11-01T13:00:00Z")))
    }

    @Test
    fun midnightTodayShowsTime() {
        // 本地 00:05。
        assertEquals("00:05", formatter.format(Instant.parse("2026-10-31T16:05:00Z")))
    }

    @Test
    fun yesterdayShowsLabel() {
        // 本地 10-31 23:00。
        assertEquals("昨天", formatter.format(Instant.parse("2026-10-31T15:00:00Z")))
    }

    @Test
    fun withinWeekShowsWeekday() {
        // 本地 10-30 周五。
        assertEquals("周五", formatter.format(Instant.parse("2026-10-30T02:00:00Z")))
    }

    @Test
    fun sixDaysAgoStillWeekday() {
        // 本地 10-26 周一，距离 11-01 正好 6 天。
        assertEquals("周一", formatter.format(Instant.parse("2026-10-26T02:00:00Z")))
    }

    @Test
    fun exactlySevenDaysAgoShowsDate() {
        // 7 天前不再显示星期。
        assertEquals("10/25", formatter.format(Instant.parse("2026-10-25T02:00:00Z")))
    }

    @Test
    fun earlierThisYearShowsMonthAndDay() {
        assertEquals("1/5", formatter.format(Instant.parse("2026-01-05T02:00:00Z")))
    }

    @Test
    fun previousYearShowsFullDate() {
        assertEquals("2025/12/31", formatter.format(Instant.parse("2025-12-31T02:00:00Z")))
    }

    @Test
    fun futureSameDayShowsTime() {
        assertEquals("22:30", formatter.format(Instant.parse("2026-11-01T14:30:00Z")))
    }

    @Test
    fun futureDayShowsDate() {
        assertEquals("11/2", formatter.format(Instant.parse("2026-11-02T02:00:00Z")))
    }

    @Test
    fun englishLocaleUsesEnglishLabels() {
        val english = RelativeTimeFormatter(TestTime.zone, TestTime.clock, Locale.ENGLISH)

        assertEquals("Yesterday", english.format(Instant.parse("2026-10-31T15:00:00Z")))
        assertEquals("Fri", english.format(Instant.parse("2026-10-30T02:00:00Z")))
        assertEquals("10/25", english.format(Instant.parse("2026-10-25T02:00:00Z")))
    }
}
