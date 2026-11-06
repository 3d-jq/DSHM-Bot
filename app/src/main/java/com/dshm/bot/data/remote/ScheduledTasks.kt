package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「定时任务」：DS Bot 的 `/api/bot` 端点 `schedules` 返回的任务。
 *
 * 字段与约束对齐 `DS-Bot/ds-bot/src/host/schedules.js`：
 * `botId` 与 `roomId` 互斥（任务挂在某个 Bot 或某个群聊上）。
 */
data class ScheduledTask(
    val id: String,
    val sessionId: String,
    /** `after` / `at` / `every` / `daily` / `weekly` / `cron`。 */
    val kind: String,
    val title: String,
    val prompt: String,
    /** `active` / `inactive`。 */
    val status: String,
    val scheduledAt: String?,
    val afterSeconds: Int?,
    val everySeconds: Int?,
    /** `HH:mm:ss`。 */
    val time: String?,
    val timeZone: String?,
    /** ISO 周几，1=周一 … 7=周日。 */
    val weekdays: List<Int>,
    val expression: String?,
    /** 挂在哪个 Bot（与 [roomId] 互斥）。 */
    val botId: String?,
    /** 挂在哪个群聊（与 [botId] 互斥）。 */
    val roomId: String?,
) {
    /** 任务是不是还活着。 */
    val active: Boolean get() = status == "active"
}

/** 定时任务清单：`available=false` 表示宿主没开 Automation tasks 插件。 */
data class ScheduleList(
    val available: Boolean,
    val tasks: List<ScheduledTask>,
)

/** 解析 `schedules` 的返回。 */
fun parseScheduleList(raw: JSONObject?): ScheduleList {
    if (raw == null) return ScheduleList(available = false, tasks = emptyList())
    val tasks = mutableListOf<ScheduledTask>()
    raw.optJSONArray("tasks")?.let { array ->
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id")
            if (id.isEmpty()) continue
            tasks += ScheduledTask(
                id = id,
                sessionId = item.optString("sessionId"),
                kind = item.optString("kind"),
                title = item.optString("title"),
                prompt = item.optString("prompt"),
                status = item.optString("status"),
                scheduledAt = item.optString("scheduledAt").takeIf { it.isNotEmpty() },
                afterSeconds = if (item.has("afterSeconds") && !item.isNull("afterSeconds")) {
                    item.optInt("afterSeconds")
                } else {
                    null
                },
                everySeconds = if (item.has("everySeconds") && !item.isNull("everySeconds")) {
                    item.optInt("everySeconds")
                } else {
                    null
                },
                time = item.optString("time").takeIf { it.isNotEmpty() },
                timeZone = item.optString("timeZone").takeIf { it.isNotEmpty() },
                weekdays = buildList {
                    item.optJSONArray("weekdays")?.let { days ->
                        for (i in 0 until days.length()) add(days.optInt(i))
                    }
                },
                expression = item.optString("expression").takeIf { it.isNotEmpty() },
                botId = item.optString("botId").takeIf { it.isNotEmpty() },
                roomId = item.optString("roomId").takeIf { it.isNotEmpty() },
            )
        }
    }
    return ScheduleList(
        available = raw.optBoolean("available", false),
        tasks = tasks,
    )
}

/**
 * 新建任务时的时间选择器（对应 `schedules.js` 的 `selectorOf`）。
 * 只支持三种不需要日历的规则；`at` 与 `cron` 需要选时间/写表达式，本项目不做。
 */
sealed interface ScheduleTiming {
    /** 每天几点。 */
    data class Daily(val time: String, val timeZone: String) : ScheduleTiming

    /** 每周几、几点。 */
    data class Weekly(val time: String, val timeZone: String, val weekdays: List<Int>) : ScheduleTiming

    /** 每隔几分钟。 */
    data class Every(val minutes: Int) : ScheduleTiming
}

/** 时间规则的校验结果：null 表示没问题，否则是给用户看的错误。 */
fun validateScheduleTiming(timing: ScheduleTiming): String? = when (timing) {
    is ScheduleTiming.Daily -> if (TIME_PATTERN.matches(timing.time)) null else "时间要写成 HH:mm"
    is ScheduleTiming.Weekly -> {
        if (!TIME_PATTERN.matches(timing.time)) {
            "时间要写成 HH:mm"
        } else if (timing.weekdays.isEmpty()) {
            "至少选一天"
        } else {
            null
        }
    }
    is ScheduleTiming.Every -> if (timing.minutes in 1..10080) null else "重复间隔要在 1 分钟到 7 天之间"
}

/** 转成 `schedule-create` 的 `when` 对象。 */
fun scheduleTimingSelector(timing: ScheduleTiming): JSONObject = when (timing) {
    is ScheduleTiming.Daily -> JSONObject()
        .put("kind", "daily")
        .put("time", timing.time)
        .put("timeZone", timing.timeZone)
    is ScheduleTiming.Weekly -> JSONObject()
        .put("kind", "weekly")
        .put("time", timing.time)
        .put("timeZone", timing.timeZone)
        .put("weekdays", JSONArray(timing.weekdays))
    is ScheduleTiming.Every -> JSONObject()
        .put("kind", "every")
        .put("minutes", timing.minutes)
}

/**
 * 时间规则的中文描述：给列表行当副标题。
 */
fun scheduleTimingLabel(task: ScheduledTask): String = when (task.kind) {
    "daily" -> "每天 ${task.time?.take(5) ?: "--:--"}"
    "weekly" -> {
        val days = task.weekdays.map { WEEKDAY_NAMES[it] ?: "" }.filter { it.isNotEmpty() }
        val names = if (days.isEmpty()) "" else "每周${days.joinToString("、")} "
        "$names${task.time?.take(5) ?: "--:--"}"
    }
    "every" -> {
        val seconds = task.everySeconds ?: 0
        if (seconds % 3600 == 0 && seconds >= 3600) {
            "每 ${seconds / 3600} 小时"
        } else if (seconds % 60 == 0) {
            "每 ${seconds / 60} 分钟"
        } else {
            "每 $seconds 秒"
        }
    }
    "after" -> {
        val seconds = task.afterSeconds ?: 0
        "每轮会话结束后 ${seconds / 60} 分钟"
    }
    "at" -> task.scheduledAt?.replace("T", " ")?.take(16) ?: "指定时间"
    "cron" -> task.expression ?: "cron"
    else -> task.kind
}

/** ISO 周几的中文名（1=周一 … 7=周日）。 */
private val WEEKDAY_NAMES = mapOf(
    1 to "一",
    2 to "二",
    3 to "三",
    4 to "四",
    5 to "五",
    6 to "六",
    7 to "日",
)

private val TIME_PATTERN = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
