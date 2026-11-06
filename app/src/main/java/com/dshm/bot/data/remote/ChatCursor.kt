package com.dshm.bot.data.remote

import org.json.JSONObject

/**
 * 聊天历史的分页游标，对齐 ds-bot `src/host/parts.js` 的 `chatPage` / `partPage`：
 *
 * - `{beforeSeq: N}` —— 同一段内往前翻一页（一页 60 条）；
 * - `{part: N}` —— 跳到第 N 段（Bot 换段 / 上下文满了会开新段）。
 *
 * 引擎返回的 `cursor` 为 null 时表示**没有更早的了**。
 */
data class ChatCursor(
    /** 段内游标：从哪一条往前翻。 */
    val beforeSeq: Long? = null,
    /** 段游标：第几段。 */
    val part: Int? = null,
) {
    /** 这个游标还有东西可拉吗。 */
    val hasMore: Boolean get() = beforeSeq != null || part != null

    /** 转成引擎要的 JSON 形状（两个都没有就不传）。 */
    fun toJson(): JSONObject? {
        val json = JSONObject()
        beforeSeq?.let { json.put("beforeSeq", it) }
        part?.let { json.put("part", it) }
        return if (json.length() == 0) null else json
    }

    companion object {
        /** 解析引擎返回的游标。 */
        fun fromJson(raw: JSONObject?): ChatCursor? {
            if (raw == null) return null
            val beforeSeq = if (raw.has("beforeSeq") && !raw.isNull("beforeSeq")) {
                raw.optLong("beforeSeq")
            } else {
                null
            }
            val part = if (raw.has("part") && !raw.isNull("part")) raw.optInt("part") else null
            if (beforeSeq == null && part == null) return null
            return ChatCursor(beforeSeq = beforeSeq, part = part)
        }
    }
}

/**
 * 一页聊天记录：消息 + 下一段的游标。
 */
data class ChatPage(
    val sessionId: String?,
    val cursor: ChatCursor?,
)

/**
 * 解析 `chat` / `room-chat` 的返回。
 */
fun parseChatPage(raw: JSONObject?): ChatPage {
    if (raw == null) return ChatPage(sessionId = null, cursor = null)
    return ChatPage(
        sessionId = raw.optString("sessionId").takeIf { it.isNotEmpty() },
        cursor = ChatCursor.fromJson(raw.optJSONObject("cursor")),
    )
}
