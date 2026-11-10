package com.dshm.bot.ui.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 对话首屏缓存：引擎的 `chat` / `room-chat` 返回里能回放的 items。
 *
 * 热启动先用它把上次的消息画出来，随后再向引擎静默对齐；不缓存运行中的临时状态
 * （流式气泡、审批卡、按钮状态），避免把「正在跑」错误地带回新进程。
 */
internal class ChatHistoryCache(context: Context, botId: String) {
    private val prefs = context.applicationContext
        .getSharedPreferences("bot-chat-history:$botId", Context.MODE_PRIVATE)

    fun load(): Snapshot? = runCatching {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return null
        if (raw.length > MAX_CHARS) return null
        Snapshot(
            items = JSONArray(raw),
            sessionId = prefs.getString(KEY_SESSION, null)?.takeIf { it.isNotEmpty() },
        )
    }.getOrNull()

    fun save(response: JSONObject) {
        runCatching {
            val items = response.optJSONArray("items") ?: JSONArray()
            val raw = items.toString()
            if (raw.length > MAX_CHARS) return
            prefs.edit()
                .putString(KEY_ITEMS, raw)
                .putString(KEY_SESSION, response.optString("sessionId"))
                .apply()
        }
    }

    data class Snapshot(
        val items: JSONArray,
        val sessionId: String?,
    )

    private companion object {
        const val KEY_ITEMS = "items"
        const val KEY_SESSION = "session"
        const val MAX_CHARS = 512 * 1024
    }
}
