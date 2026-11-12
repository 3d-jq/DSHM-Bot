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

    /** 内存里的当前页 item（冷启动首帧 + 增量追加都用它，别每次重新解析 prefs）。 */
    private val items: JSONArray = readItems()

    fun load(): Snapshot? = runCatching {
        val snapshotItems = readItems()
        if (snapshotItems.length() == 0) return null
        Snapshot(
            items = snapshotItems,
            sessionId = prefs.getString(KEY_SESSION, null)?.takeIf { it.isNotEmpty() },
        )
    }.getOrNull()

    fun save(response: JSONObject) {
        runCatching {
            val incoming = response.optJSONArray("items") ?: JSONArray()
            replaceAll(incoming)
            prefs.edit()
                .putString(KEY_SESSION, response.optString("sessionId"))
                .apply()
        }
    }

    /**
     * 一条新消息落地时**就地追加**（feed `message` 用）。
     *
     * 这是冷启动快照能跟上的关键：以前 `save` 只在完整重拉时写，
     * 追加消息必须等下一次 `load()` 才进快照 —— 于是「回复完就退 App、冷启动」
     * 读到的是旧快照，重新进一次对话才变新。
     */
    fun appendItem(item: JSONObject) {
        runCatching {
            val id = item.optString("id")
            val seq = item.optLong("seq", -1L)
            // 去重：同 id 或同 seq 的旧条删掉（LazyColumn 的 key 不能重复）。
            val filtered = JSONArray()
            for (index in 0 until items.length()) {
                val existing = items.optJSONObject(index) ?: continue
                val sameId = id.isNotEmpty() && existing.optString("id") == id
                val sameSeq = seq >= 0 && existing.optLong("seq", -1L) == seq
                if (!sameId && !sameSeq) filtered.put(existing)
            }
            filtered.put(item)
            replaceAll(filtered)
        }
    }

    private fun replaceAll(incoming: JSONArray) {
        val kept = keepNewest(incoming)
        // 替换内存副本。
        while (items.length() > 0) items.remove(0)
        for (index in 0 until kept.length()) items.put(kept.get(index))
        prefs.edit().putString(KEY_ITEMS, kept.toString()).apply()
    }

    private fun readItems(): JSONArray = runCatching {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return JSONArray()
        if (raw.length > MAX_CHARS) JSONArray() else JSONArray(raw)
    }.getOrDefault(JSONArray())

    /** 超出上限时保留**最新的一段**，而不是整份放弃。 */
    private fun keepNewest(array: JSONArray): JSONArray {
        if (array.toString().length <= MAX_CHARS) return array
        val reversed = JSONArray()
        var size = 0
        for (index in array.length() - 1 downTo 0) {
            val item = array.optJSONObject(index) ?: continue
            val text = item.toString()
            if (reversed.length() > 0 && size + text.length > MAX_CHARS) break
            reversed.put(item)
            size += text.length + 1
        }
        return JSONArray().apply {
            for (index in reversed.length() - 1 downTo 0) put(reversed.get(index))
        }
    }

    data class Snapshot(
        val items: JSONArray,
        val sessionId: String?,
    )

    private companion object {
        const val KEY_ITEMS = "items"
        const val KEY_SESSION = "session"
        /** 上限放宽到 1 MB（SharedPreferences 存得下），超出的按「保留最新」裁掉。 */
        const val MAX_CHARS = 1024 * 1024
    }
}
