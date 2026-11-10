package com.dshm.bot.data.remote

import android.content.Context
import org.json.JSONObject

/**
 * 单 Bot 的最新回复预览。
 *
 * `state.rooms[]` 自带群聊预览，但 `state.bots[]` 只有 role；单 Bot 的预览是从
 * `chat` / feed 合成出来的。把它持久化后，热启动不必等 `usage + chat` 回填，
 * 也不会先显示角色标签、过一会儿才跳成对话预览。
 */
class BotPreviewCache(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("dshm_bot_previews", Context.MODE_PRIVATE)

    fun load(): Map<String, String> = runCatching {
        val raw = prefs.getString(KEY_PREVIEWS, null) ?: return emptyMap()
        val json = JSONObject(raw)
        buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) put(key, value)
            }
        }
    }.getOrDefault(emptyMap())

    fun save(previews: Map<String, String>) {
        runCatching {
            val json = JSONObject()
            previews.forEach { (id, text) ->
                if (id.isNotBlank() && text.isNotBlank()) json.put(id, BotPreview.oneLine(text))
            }
            prefs.edit().putString(KEY_PREVIEWS, json.toString()).apply()
        }
    }

    private companion object {
        const val KEY_PREVIEWS = "previews"
    }
}
