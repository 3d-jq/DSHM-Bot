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
    val usageTokens: Long = 0L,
    val cacheHit: Float? = null,
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
    )
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
