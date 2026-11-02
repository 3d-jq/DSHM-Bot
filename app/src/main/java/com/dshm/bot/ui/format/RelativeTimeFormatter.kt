package com.dshm.bot.ui.format

import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 列表时间戳：今天显示 `21:53`，昨天显示 `昨天`，最近一周显示 `周一`，
 * 今年内显示 `10/21`，更早显示 `2025/10/21`。规则来自主界面设计稿。
 *
 * 时间/时区/语言都从构造函数注入，测试可以用固定时钟覆盖边界。
 */
class RelativeTimeFormatter(
    private val zoneId: ZoneId,
    private val clock: Clock,
    locale: Locale = Locale.getDefault(),
) {
    private val labels = TimeLabels(locale)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val dateFormat = DateTimeFormatter.ofPattern("M/d", locale)
    private val fullDateFormat = DateTimeFormatter.ofPattern("yyyy/M/d", locale)

    fun format(instant: Instant): String {
        val now = clock.instant()
        val today = now.atZone(zoneId).toLocalDate()
        val target = instant.atZone(zoneId)
        val daysAgo = ChronoUnit.DAYS.between(target.toLocalDate(), today)
        return when {
            daysAgo == 0L -> timeFormat.format(target)
            daysAgo == 1L -> labels.yesterday()
            daysAgo in 2L..6L -> labels.weekday(target.dayOfWeek)
            target.year == today.year -> dateFormat.format(target)
            else -> fullDateFormat.format(target)
        }
    }

    private class TimeLabels(private val locale: Locale) {
        private val chinese = locale.language == Locale.CHINESE.language

        fun yesterday(): String = if (chinese) "昨天" else "Yesterday"

        fun weekday(day: DayOfWeek): String = if (chinese) {
            "周" + CHINESE_WEEKDAYS[day.value - 1]
        } else {
            day.getDisplayName(java.time.format.TextStyle.SHORT, locale)
        }

        private companion object {
            /** ISO 顺序：周一到周日。 */
            val CHINESE_WEEKDAYS = listOf("一", "二", "三", "四", "五", "六", "日")
        }
    }
}
