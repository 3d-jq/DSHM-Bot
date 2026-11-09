package com.dshm.bot.data.remote

import com.dshm.bot.domain.model.AvatarGlyph
import com.dshm.bot.domain.model.BotInks
import com.dshm.bot.domain.model.BotShapeDefaults
import com.dshm.bot.domain.model.BotSummary
import com.dshm.bot.ui.home.RoomMemberUi
import com.dshm.bot.ui.home.TeamRoomUi
import org.json.JSONObject
import java.time.Instant

/**
 * DS Bot `/api/bot` 的 `state` → 主界面行。
 *
 * `value` 的形状（ds-bot/src/host/channel.js `view()`）：
 * - `bots: [{id,name,role,color,pinned,createdAt,...}]`
 * - `rooms: [{id,name,preview,pinned,createdAt,...}]`
 * 未读需要客户端根据 notices 统计，这里先置 0。
 */
object DsBotParser {

    /**
     * `state.bots` → 团队成员的显示身份（Bot id 就是它的会话 id）。
     *
     * 事件行的「来自谁」用它来认人：DS Bot 的 relay 事件只给 `senderSessionId`，
     * 不带头像和颜色。
     */
    fun teamMembers(state: JSONObject): List<RoomMemberUi> {
        val array = state.optJSONArray("bots") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            if (id.isEmpty()) return@mapNotNull null
            val avatar = item.optJSONObject("avatar")
            RoomMemberUi(
                id = id,
                name = item.optString("name").ifEmpty { id },
                inkId = inkOf(item, id),
                avatarImage = avatar?.anyOrNull("image") as? String,
                avatarShape = (avatar?.anyOrNull("shape") as? String)
                    ?: BotShapeDefaults.shapeFor(id),
                role = item.optString("role").takeIf { it.isNotBlank() },
            )
        }
    }

    /** `state.rooms` → 群聊的显示身份（成员头像堆叠用）。 */
    fun teamRooms(state: JSONObject): List<TeamRoomUi> {
        val array = state.optJSONArray("rooms") ?: return emptyList()
        val looks = teamMembers(state).associateBy { it.id }
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            if (id.isEmpty()) return@mapNotNull null
            val members = item.optJSONArray("members")?.let { list ->
                (0 until list.length()).mapNotNull { at -> looks[list.optString(at)] }
            }.orEmpty()
            TeamRoomUi(id = id, name = item.optString("name").ifEmpty { id }, members = members)
        }
    }

    fun parseState(state: JSONObject): List<BotSummary> {
        val out = mutableListOf<BotSummary>()
        val now = System.currentTimeMillis()
        val botLooks = teamMembers(state).associateBy { it.id }
        val live = liveStatusOf(state)

        state.optJSONArray("bots")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (item.optBoolean("hidden", false)) continue
                val id = item.optString("id")
                if (id.isEmpty()) continue
                val avatar = item.optJSONObject("avatar")
                out.add(
                    BotSummary(
                        id = id,
                        name = item.optString("name").ifEmpty { "Bot" },
                        preview = item.optString("role").trim(),
                        lastActiveAt = stamp(item.optLong("createdAt", 0L), now),
                        inkId = inkOf(item, id),
                        glyph = AvatarGlyph.BOT,
                        pinned = item.optBoolean("pinned", false),
                        avatarImage = avatar?.anyOrNull("image") as? String,
                        // 没有自定义形状的 Bot 由 id 决定默认角色（DS Bot 同一套规则）。
                        avatarShape = (avatar?.anyOrNull("shape") as? String)
                            ?: BotShapeDefaults.shapeFor(id),
                        activity = live.bots[id],
                    ),
                )
            }
        }

        state.optJSONArray("rooms")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (item.optBoolean("hidden", false)) continue
                val id = item.optString("id")
                if (id.isEmpty()) continue
                out.add(
                    BotSummary(
                        id = id,
                        name = item.optString("name").ifEmpty { "Group" },
                        preview = item.optString("preview"),
                        lastActiveAt = stamp(item.optLong("createdAt", 0L), now),
                        glyph = AvatarGlyph.GROUP,
                        pinned = item.optBoolean("pinned", false),
                        isGroup = true,
                        roomMembers = item.optJSONArray("members")?.let { members ->
                            (0 until members.length()).mapNotNull { index ->
                                botLooks[members.optString(index)]
                            }
                        }.orEmpty(),
                        roomAdmin = item.optString("admin").takeIf { it.isNotEmpty() },
                        activity = live.rooms[id],
                    ),
                )
            }
        }
        return out
    }

    /**
     * `state.work` → 每个 Bot / 群聊的实时状态。
     *
     * 对齐 DS Bot 的 `busyMembers` / 侧栏状态点：
     * - `waiting`：`work.approvals` 里有它（等你批准）；
     * - `running`：`work.bots[id]` 或 `work.rooms[某群]` 里有它（在干活）。
     */
    private fun liveStatusOf(state: JSONObject): LiveStatus {
        val work = state.optJSONObject("work") ?: return LiveStatus()
        val bots = mutableMapOf<String, String>()
        val rooms = mutableMapOf<String, String>()
        work.optJSONArray("approvals")?.let { approvals ->
            for (index in 0 until approvals.length()) {
                val entry = approvals.optJSONObject(index) ?: continue
                val botId = entry.optString("botId")
                if (botId.isNotEmpty()) bots[botId] = "waiting"
                val roomId = entry.optString("roomId")
                if (roomId.isNotEmpty()) rooms[roomId] = "waiting"
            }
        }
        work.optJSONObject("bots")?.let { running ->
            running.keys().forEach { id ->
                if (bots[id] == null) bots[id] = "running"
            }
        }
        work.optJSONObject("rooms")?.let { byRoom ->
            byRoom.keys().forEach { roomId ->
                byRoom.optJSONArray(roomId)?.let { entries ->
                    for (index in 0 until entries.length()) {
                        val botId = entries.optJSONObject(index)?.optString("botId").orEmpty()
                        if (botId.isNotEmpty() && bots[botId] == null) bots[botId] = "running"
                    }
                }
                if (rooms[roomId] == null && (byRoom.optJSONArray(roomId)?.length() ?: 0) > 0) {
                    rooms[roomId] = "running"
                }
            }
        }
        return LiveStatus(bots = bots, rooms = rooms)
    }

    private data class LiveStatus(
        val bots: Map<String, String> = emptyMap(),
        val rooms: Map<String, String> = emptyMap(),
    )

    private fun stamp(value: Long, now: Long): Instant =
        Instant.ofEpochMilli(if (value > 0L) value else now)

    private fun colorOf(value: String): String? =
        value.takeIf { BotInks.isColor(it) }

    /**
     * Bot 的颜色：没手选（或值非法）时按 id 取默认色。
     *
     * 对齐 DS Bot `colorOf`：`isColor(bot.color) ? bot.color : defaultColor(bot.id)`。
     * 少了这一步，没选过颜色的 Bot（state 里 `color` 是空串）会一律画成灰色。
     */
    private fun inkOf(item: JSONObject, id: String): String =
        colorOf(item.optString("color")) ?: BotInks.defaultColorId(id)
}
