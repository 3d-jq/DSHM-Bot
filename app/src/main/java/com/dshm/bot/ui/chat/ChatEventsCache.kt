package com.dshm.bot.ui.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.edit

/**
 * 会话事件记录（`seq → event`）的落盘副本。
 *
 * 产物卡是**从事件记录推导**出来的（对齐 dsh-Go）：只认 `session/follow` 的实时
 * 快照窗口，冷启动 / 重新进对话就会丢卡。把最近一段事件存下来，首帧就能重算出产物。
 */
internal class ChatEventsCache(context: Context, botId: String) {
    private val prefs = context.applicationContext
        .getSharedPreferences("bot-chat-events:$botId", Context.MODE_PRIVATE)

    fun load(): List<JSONObject> = runCatching {
        val raw = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        if (raw.length > MAX_CHARS) return emptyList()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    fun save(events: List<JSONObject>) {
        runCatching {
            val array = JSONArray()
            var size = 0
            var index = events.size - 1
            // 从最新往回收，塞不下就停（老的先丢）。
            while (index >= 0 && size < MAX_CHARS) {
                val event = events[index]
                if (event.optLong("seq", -1L) < 0L) {
                    index -= 1
                    continue
                }
                val text = event.toString()
                if (size + text.length > MAX_CHARS) break
                array.put(event)
                size += text.length + 1
                index -= 1
            }
            // 倒序放进去的要翻回来：老的在前。
            val ordered = JSONArray()
            for (at in array.length() - 1 downTo 0) ordered.put(array.get(at))
            prefs.edit { putString(KEY_EVENTS, ordered.toString()) }
        }
    }

    private companion object {
        const val KEY_EVENTS = "events"
        const val MAX_CHARS = 1024 * 1024
    }
}
