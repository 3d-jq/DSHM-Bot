package com.dshm.bot.data.remote

import org.json.JSONObject
import com.dshm.bot.ui.home.RoomMemberUi

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
        val bots = DsBotParser.teamMembers(state).map { member ->
            ScheduleTarget(
                id = member.id,
                name = member.name,
                kind = ScheduleTargetKind.BOT,
                bot = member,
            )
        }
        val rooms = DsBotParser.teamRooms(state).map { room ->
            ScheduleTarget(
                id = room.id,
                name = room.name,
                kind = ScheduleTargetKind.ROOM,
                members = room.members,
            )
        }
        return bots + rooms
    }
}

/** 定时任务可以挂在谁身上。 */
enum class ScheduleTargetKind { BOT, ROOM }

/** 一个可选的目标（Bot 或群聊）。 */
data class ScheduleTarget(
    val id: String,
    val name: String,
    val kind: ScheduleTargetKind,
    /** Bot 目标：它的显示身份（头像 / 颜色）；群聊目标为 null。 */
    val bot: RoomMemberUi? = null,
    /** 群聊目标：成员头像堆叠。 */
    val members: List<RoomMemberUi> = emptyList(),
)
