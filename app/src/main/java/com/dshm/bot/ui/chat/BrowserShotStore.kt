package com.dshm.bot.ui.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class StoredBrowserShot(
    val shot: ChatBrowserShot,
    val seq: Long?,
)

/** 网页缩略图卡：按 Bot + Session 持久化，重启后仍能贴回对应回合。 */
internal class BrowserShotStore(context: Context, botId: String) {
    private val prefs = context.applicationContext
        .getSharedPreferences("bot-chat-shots:$botId", Context.MODE_PRIVATE)

    fun load(sessionId: String): List<StoredBrowserShot> {
        val raw = prefs.getString(key(sessionId), null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val path = item.optString("path")
                val url = item.optString("url")
                if (path.isEmpty() || url.isEmpty()) continue
                add(
                    StoredBrowserShot(
                        shot = ChatBrowserShot(
                            path = path,
                            url = url,
                            title = item.optString("title"),
                            timeMs = item.optLong("timeMs"),
                        ),
                        seq = item.optLong("seq", -1L).takeIf { it >= 0L },
                    ),
                )
            }
        }
    }

    fun add(sessionId: String, shot: ChatBrowserShot, seq: Long?) {
        val current = load(sessionId).toMutableList()
        current.removeAll { it.shot.timeMs == shot.timeMs && it.shot.url == shot.url }
        current += StoredBrowserShot(shot, seq)
        val array = JSONArray()
        current.takeLast(MAX).forEach { entry ->
            array.put(
                JSONObject()
                    .put("path", entry.shot.path)
                    .put("url", entry.shot.url)
                    .put("title", entry.shot.title)
                    .put("timeMs", entry.shot.timeMs)
                    .apply { entry.seq?.let { put("seq", it) } },
            )
        }
        prefs.edit().putString(key(sessionId), array.toString()).apply()
    }

    private fun key(sessionId: String) = "session:$sessionId"

    private companion object {
        const val MAX = 30
    }
}
