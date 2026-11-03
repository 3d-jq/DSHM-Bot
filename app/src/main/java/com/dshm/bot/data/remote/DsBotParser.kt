package com.dshm.bot.data.remote

import com.dshm.bot.domain.model.AvatarGlyph
import com.dshm.bot.domain.model.BotInks
import com.dshm.bot.domain.model.BotSummary
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

    fun parseState(state: JSONObject): List<BotSummary> {
        val out = mutableListOf<BotSummary>()
        val now = System.currentTimeMillis()

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
                        inkId = colorOf(item.optString("color")),
                        glyph = AvatarGlyph.BOT,
                        pinned = item.optBoolean("pinned", false),
                        avatarImage = avatar?.anyOrNull("image") as? String,
                        avatarShape = avatar?.anyOrNull("shape") as? String,
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
                    ),
                )
            }
        }
        return out
    }

    private fun stamp(value: Long, now: Long): Instant =
        Instant.ofEpochMilli(if (value > 0L) value else now)

    private fun colorOf(value: String): String? =
        value.takeIf { BotInks.isColor(it) }
}
