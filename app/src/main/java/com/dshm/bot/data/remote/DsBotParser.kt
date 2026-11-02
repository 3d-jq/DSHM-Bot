package com.dshm.bot.data.remote

import org.json.JSONObject

/** DS Bot 团队里的一行（Bot 或群聊），来自 `/api/bot` 的 state。 */
data class DsBotSummary(
    val id: String,
    val name: String,
    val preview: String,
    val unread: Int,
    val isGroup: Boolean,
    val pinned: Boolean,
)

/**
 * 把 DS Bot `/api/bot` 返回的 JSON 解析成界面用的一行行数据。
 * 字段兼容常见命名（id/name/preview/unread/isGroup/pinned），缺省值安全。
 */
object DsBotParser {

    fun parseBots(state: JSONObject): List<DsBotSummary> {
        val array = state.optJSONArray("bots")
            ?: state.optJSONArray("items")
            ?: state.optJSONArray("list")
            ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").ifEmpty { item.optString("botId") }
                if (id.isEmpty()) continue
                add(
                    DsBotSummary(
                        id = id,
                        name = item.optString("name").ifEmpty { item.optString("title") },
                        preview = item.optString("preview").ifEmpty { item.optString("lastMessage") },
                        unread = item.optInt("unread", item.optInt("unreadCount", 0)),
                        isGroup = item.optBoolean("isGroup", item.optBoolean("group", false)),
                        pinned = item.optBoolean("pinned", false),
                    ),
                )
            }
        }
    }
}
