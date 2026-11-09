package com.dshm.bot.ui.chat

import org.json.JSONArray
import org.json.JSONObject
import com.dshm.bot.domain.model.BotShapeDefaults
import com.dshm.bot.domain.model.BotInks
import com.dshm.bot.ui.home.RoomMemberUi

data class RoomMemberOption(val id: String, val name: String)

/**
 * `room-progress` 的结果 → 「有 round 在跑吗」+「当前发言人」。
 *
 * 形状对齐 `ds-bot/src/host/channel.js`：`[{callId, replies, speaking}]`；
 * 空数组就是权威的「这一轮没在跑」。形状不对时返回 null（别拿它当「没在跑」）。
 */
fun parseGroupProgress(value: Any?): Pair<Boolean, String?>? {
    val rounds = value as? JSONArray ?: return null
    val speaking = (0 until rounds.length())
        .mapNotNull { rounds.optJSONObject(it) }
        .lastOrNull()
        ?.optString("speaking")
        ?.takeIf { it.isNotEmpty() }
    return (rounds.length() > 0) to speaking
}

data class RoomBotOption(
    val id: String,
    val name: String,
    val role: String?,
    val inkId: String?,
    val avatarImage: String?,
    val avatarShape: String?,
)

data class RoomInfo(
    val id: String,
    val name: String,
    val named: Boolean,
    val notice: String,
    val mode: String,
    val admin: String?,
    val members: List<String>,
    val workDir: String?,
    val permission: String?,
    val relayLimit: Int?,
    val relayLimitDefault: Int?,
    val pinned: Boolean,
    val hidden: Boolean,
)

data class RoomSettingsUi(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val info: RoomInfo? = null,
    val bots: List<RoomMemberOption> = emptyList(),
    val botRows: List<RoomBotOption> = emptyList(),
    val permissions: List<BotPermissionOption> = emptyList(),
    val memory: List<BotMemoryEntry> = emptyList(),
    val memoryEnabled: Boolean = true,
    val memorySizes: Map<String, String> = emptyMap(),
    val memorySummary: String? = null,
    val memorySummarizing: Boolean = false,
    val memoryFresh: Boolean = true,
    val usageTokens: Long = 0L,
    val cacheHit: Float? = null,
    /** 每个成员的实时状态（`state.work`）：等批准 / 正在干活 / 空闲。 */
    val memberStatus: Map<String, RoomMemberStatus> = emptyMap(),
    /** `folders` 接口已知的目录 + 这个 DSH 提供哪种选择方式（与 Bot 详情同一套）。 */
    val folders: List<BotFolder> = emptyList(),
    val teamFolder: String? = null,
    val folderPicker: String? = null,
)

/**
 * 群成员的实时状态，对齐 DS Bot `memberWork`（`client.js:15521`）：
 * 有属于它的审批就是 `ask`（等你批准）；否则看 `work.rooms[roomId]` 里它在群里的活，
 * 再退回 `work.bots[id]`（在它自己的对话里干活）；都没有就是空闲（界面退回显示角色）。
 */
data class RoomMemberStatus(
    /** `ask` / `work` / `idle`。 */
    val state: String,
    /** 正在干什么（DS Bot 的 `workPhrase(doing)`）。 */
    val doing: String? = null,
    /** 活是**这个群**里的（`work.rooms[roomId]`），而不是它自己会话里的。 */
    val here: Boolean = false,
    /** 正在这个群里发言（网页版 dock 会把这条藏掉，因为对话里已经看得到）。 */
    val speaking: Boolean = false,
)

/** DS Bot `state.rooms[]` + `state.permissions`。 */
fun parseRoomSettings(state: JSONObject, roomId: String): RoomSettingsUi {
    val room = state.optJSONArray("rooms")
        ?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }
        ?.firstOrNull { it.optString("id") == roomId }
        ?: return RoomSettingsUi(loading = false, error = "群聊不存在")
    val botRows = state.optJSONArray("bots")?.let { array ->
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id.isEmpty()) continue
                val avatar = item.optJSONObject("avatar")
                add(
                    RoomBotOption(
                        id = id,
                        name = item.optString("name").ifEmpty { id },
                        role = item.optString("role").takeIf { it.isNotBlank() },
                        // 对齐 DS Bot `colorOf`：没手选颜色时按 id 取默认色。
                        inkId = item.optString("color").takeIf { BotInks.isColor(it) }
                            ?: BotInks.defaultColorId(id),
                        avatarImage = avatar?.optString("image")?.takeIf { it.isNotEmpty() },
                        avatarShape = avatar?.optString("shape")?.takeIf { it.isNotEmpty() }
                            ?: BotShapeDefaults.shapeFor(id),
                    ),
                )
            }
        }
    }.orEmpty()
    val bots = botRows.map { RoomMemberOption(it.id, it.name) }
    val permissions = state.optJSONObject("permissions")?.optJSONArray("options")?.let { array ->
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val ref = item.optString("value").ifEmpty { item.optString("id") }
                if (ref.isEmpty()) continue
                add(
                    BotPermissionOption(
                        ref = ref,
                        name = item.optString("name").ifEmpty { ref },
                        hint = item.optString("hint").takeIf { it.isNotEmpty() },
                    ),
                )
            }
        }
    }.orEmpty()
    val members = room.optJSONArray("members")?.let { array ->
        (0 until array.length()).mapNotNull { array.optString(it).takeIf { id -> id.isNotEmpty() } }
    }.orEmpty()
    return RoomSettingsUi(
        loading = false,
        info = RoomInfo(
            id = roomId,
            name = room.optString("name").ifEmpty { "群聊" },
            named = room.optBoolean("named", false),
            notice = room.optString("notice"),
            mode = room.optString("mode").ifEmpty { "admin" },
            admin = room.optString("admin").takeIf { it.isNotEmpty() },
            members = members,
            workDir = room.optString("workDir").takeIf { it.isNotEmpty() },
            permission = room.optString("permission").takeIf { it.isNotEmpty() },
            relayLimit = room.optInt("relayLimit", -1).takeIf { it > 0 },
            relayLimitDefault = room.optInt("relayLimitDefault", -1).takeIf { it > 0 },
            pinned = room.optBoolean("pinned", false),
            hidden = room.optBoolean("hidden", false),
        ),
        bots = bots,
        botRows = botRows,
        permissions = permissions,
        memoryEnabled = state.optBoolean("memory", true),
        memberStatus = parseMemberStatus(state, roomId),
    )
}

/**
 * `state.work` → 每个成员的实时状态（对齐 DS Bot `memberWork`）。
 *
 * - `ask`：`work.approvals` 里有这个 Bot 的待批准；
 * - `work`：先看 `work.rooms[roomId]`（在群里干活），再看 `work.bots[id]`（在自己的对话里干活）；
 * - `idle`：都没有。
 */
internal fun parseMemberStatus(state: JSONObject, roomId: String): Map<String, RoomMemberStatus> {
    val work = state.optJSONObject("work") ?: return emptyMap()
    val approvals = work.optJSONArray("approvals")
    val roomWork = work.optJSONObject("rooms")?.optJSONArray(roomId)
    val botWork = work.optJSONObject("bots")
    val out = mutableMapOf<String, RoomMemberStatus>()
    fun statusOf(id: String): RoomMemberStatus {
        val waiting = approvals != null && (0 until approvals.length()).any { index ->
            approvals.optJSONObject(index)?.optString("botId") == id
        }
        if (waiting) return RoomMemberStatus(state = "ask")
        val local = roomWork?.let { array ->
            (0 until array.length())
                .mapNotNull { array.optJSONObject(it) }
                .firstOrNull { it.optString("botId") == id }
        }
        val doing = local ?: botWork?.optJSONObject(id)
        if (doing != null) {
            return RoomMemberStatus(
                state = "work",
                doing = toolLabel(doing)?.takeIf { it.isNotBlank() } ?: "正在干活",
                here = local != null,
                speaking = local?.optBoolean("speaking") == true,
            )
        }
        return RoomMemberStatus(state = "idle")
    }
    state.optJSONArray("bots")?.let { array ->
        for (index in 0 until array.length()) {
            val id = array.optJSONObject(index)?.optString("id").orEmpty()
            if (id.isNotEmpty()) out[id] = statusOf(id)
        }
    }
    return out
}

internal fun RoomBotOption.toMemberUi(): RoomMemberUi = RoomMemberUi(
    id = id,
    name = name,
    inkId = inkId,
    avatarImage = avatarImage,
    avatarShape = avatarShape,
    role = role,
)

internal fun JSONArray.toStringList(): List<String> =
    (0 until length()).mapNotNull { optString(it).takeIf { value -> value.isNotEmpty() } }
