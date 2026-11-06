package com.dshm.bot.data.remote

import org.json.JSONObject

/**
 * DS Bot 的 `/api/bot` 通道里我们用到的几个端点。
 *
 * 端点与约束对齐 `DS-Bot/ds-bot/src/host/`：
 * - `schedules`：定时任务清单（`available=false` 表示宿主没开 Automation tasks）
 * - `schedule-delete`：删一个任务
 * - `state`：团队状态（Bots / 群聊，用来做定时任务的目标选择）
 */
class DsBotApi(private val client: DshClient) {

    /** 定时任务清单。 */
    suspend fun schedules(): ScheduleList = parseScheduleList(client.bot("schedules"))

    /** 删一个任务；返回删完之后的清单。 */
    suspend fun deleteSchedule(sessionId: String, id: String): ScheduleList =
        parseScheduleList(
            client.bot(
                "schedule-delete",
                JSONObject().put("sessionId", sessionId).put("id", id),
            ),
        )

    /**
     * 新建一个任务。`target` 是 Bot id 或群聊 id；
     * 返回创建完之后的清单。
     */
    suspend fun createSchedule(
        target: String,
        title: String,
        prompt: String,
        timing: ScheduleTiming,
    ): ScheduleList = parseScheduleList(
        client.bot(
            "schedule-create",
            JSONObject()
                .put("target", target)
                .put("title", title)
                .put("prompt", prompt)
                .put("when", scheduleTimingSelector(timing)),
        ),
    )

    /** 团队里的 Bot 与群聊（定时任务的目标）。 */
    suspend fun targets(): List<ScheduleTarget> {
        val state = client.bot("state")
        val out = mutableListOf<ScheduleTarget>()
        state.optJSONArray("bots")?.let { array ->
            for (index in 0 until array.length()) {
                val bot = array.optJSONObject(index) ?: continue
                val id = bot.optString("id")
                if (id.isEmpty()) continue
                out += ScheduleTarget(
                    id = id,
                    name = bot.optString("name").ifEmpty { id },
                    kind = ScheduleTargetKind.BOT,
                )
            }
        }
        state.optJSONArray("rooms")?.let { array ->
            for (index in 0 until array.length()) {
                val room = array.optJSONObject(index) ?: continue
                val id = room.optString("id")
                if (id.isEmpty()) continue
                out += ScheduleTarget(
                    id = id,
                    name = room.optString("name").ifEmpty { id },
                    kind = ScheduleTargetKind.ROOM,
                )
            }
        }
        return out
    }
}

/** 定时任务可以挂在谁身上。 */
enum class ScheduleTargetKind { BOT, ROOM }

/** 一个可选的目标（Bot 或群聊）。 */
data class ScheduleTarget(
    val id: String,
    val name: String,
    val kind: ScheduleTargetKind,
)
