package com.dshm.bot.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定时任务的解析与时间规则（对齐 DS-Bot `src/host/schedules.js`）。
 */
class ScheduledTasksTest {

    private fun listJson(available: Boolean = true) = JSONObject()
        .put("available", available)
        .put(
            "tasks",
            org.json.JSONArray()
                .put(
                    JSONObject()
                        .put("id", "sch-1")
                        .put("sessionId", "w2")
                        .put("kind", "daily")
                        .put("title", "晨间简报")
                        .put("prompt", "总结昨天")
                        .put("status", "active")
                        .put("time", "09:00:00")
                        .put("timeZone", "Asia/Shanghai")
                        .put("botId", "w1"),
                )
                .put(
                    JSONObject()
                        .put("id", "sch-2")
                        .put("sessionId", "g1")
                        .put("kind", "weekly")
                        .put("title", "周报")
                        .put("prompt", "写周报")
                        .put("status", "inactive")
                        .put("time", "18:30:00")
                        .put("weekdays", org.json.JSONArray().put(1).put(5))
                        .put("roomId", "g1"),
                ),
        )

    @Test
    fun tasksAreParsedWithTheirOwner() {
        val list = parseScheduleList(listJson())
        assertTrue(list.available)
        assertEquals(2, list.tasks.size)
        val first = list.tasks.first()
        assertEquals("sch-1", first.id)
        assertEquals("daily", first.kind)
        assertTrue(first.active)
        // Bot 与群聊互斥。
        assertEquals("w1", first.botId)
        assertNull(first.roomId)
        val second = list.tasks[1]
        assertFalse(second.active)
        assertEquals("g1", second.roomId)
        assertNull(second.botId)
        assertEquals(listOf(1, 5), second.weekdays)
    }

    @Test
    fun unavailableMeansTheHostTurnedAutomationTasksOff() {
        // 宿主没开插件时：available=false 且没有任务。
        val raw = listJson(available = false)
        raw.put("tasks", org.json.JSONArray())
        val list = parseScheduleList(raw)
        assertFalse(list.available)
        assertTrue(list.tasks.isEmpty())
    }

    @Test
    fun timingLabelsReadInChinese() {
        val list = parseScheduleList(listJson())
        assertEquals("每天 09:00", scheduleTimingLabel(list.tasks[0]))
        assertEquals("每周一、五 18:30", scheduleTimingLabel(list.tasks[1]))
    }

    @Test
    fun everyNMinutesReadsAsHoursWhenItIsWhole() {
        val task = ScheduledTask(
            id = "s", sessionId = "x", kind = "every", title = "t", prompt = "p", status = "active",
            scheduledAt = null, afterSeconds = null, everySeconds = 7200, time = null, timeZone = null,
            weekdays = emptyList(), expression = null, botId = "w", roomId = null,
        )
        assertEquals("每 2 小时", scheduleTimingLabel(task))
    }

    @Test
    fun timingsAreValidatedLikeTheHostDoes() {
        assertNull(validateScheduleTiming(ScheduleTiming.Daily("09:00", "UTC")))
        assertEquals("时间要写成 HH:mm", validateScheduleTiming(ScheduleTiming.Daily("9:00", "UTC")))
        assertEquals("时间要写成 HH:mm", validateScheduleTiming(ScheduleTiming.Daily("25:00", "UTC")))
        assertEquals("至少选一天", validateScheduleTiming(ScheduleTiming.Weekly("09:00", "UTC", emptyList())))
        assertNull(validateScheduleTiming(ScheduleTiming.Weekly("09:00", "UTC", listOf(1))))
        assertEquals(
            "重复间隔要在 1 分钟到 7 天之间",
            validateScheduleTiming(ScheduleTiming.Every(0)),
        )
        assertNull(validateScheduleTiming(ScheduleTiming.Every(30)))
    }

    @Test
    fun selectorsMatchTheHostsShape() {
        val daily = scheduleTimingSelector(ScheduleTiming.Daily("09:00", "Asia/Shanghai"))
        assertEquals("daily", daily.getString("kind"))
        assertEquals("09:00", daily.getString("time"))
        assertEquals("Asia/Shanghai", daily.getString("timeZone"))

        val weekly = scheduleTimingSelector(ScheduleTiming.Weekly("18:30", "UTC", listOf(1, 5)))
        assertEquals("weekly", weekly.getString("kind"))
        assertEquals(2, weekly.getJSONArray("weekdays").length())

        val every = scheduleTimingSelector(ScheduleTiming.Every(30))
        assertEquals("every", every.getString("kind"))
        assertEquals(30, every.getInt("minutes"))
    }

    @Test
    fun oneOffTimingUsesTheHostsIsoShape() {
        val iso = scheduleAtIso("2099-01-02 03:04")
        assertNotNull(iso)
        val selector = scheduleTimingSelector(ScheduleTiming.At(iso!!))
        assertEquals("at", selector.getString("kind"))
        assertEquals(iso, selector.getString("at"))
        // 同一个时区往返不丢时间。
        assertEquals("2099-01-02 03:04", scheduleAtLabel(iso))
        assertNull(validateScheduleTiming(ScheduleTiming.At(iso)))
    }

    @Test
    fun oneOffTimingRejectsGarbageAndPastTimes() {
        assertEquals(
            "时间要写成 2026-11-09 09:00 这种",
            validateScheduleTiming(ScheduleTiming.At("nope")),
        )
        assertEquals(
            "时间要在将来",
            validateScheduleTiming(ScheduleTiming.At("2000-01-01T00:00:00Z")),
        )
        assertNull(scheduleAtIso("2026/11/09 09:00"))
    }
}
