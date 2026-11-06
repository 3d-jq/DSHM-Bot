package com.dshm.bot.data.remote

import android.content.Context
import com.dshm.bot.domain.model.BotSummary
import org.json.JSONObject

/**
 * 上次读到的 DS Bot 团队快照：引擎冷启动要十几秒，先用缓存把主界面画出来，
 * 连上后再用 `state` 覆盖。只存 Bot/群聊列表，不存聊天内容。
 */
class BotCache(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("dshm_bot_cache", Context.MODE_PRIVATE)

    fun load(): List<BotSummary> = runCatching {
        val raw = prefs.getString(KEY_STATE, null) ?: return emptyList()
        if (raw.length > MAX_CHARS) return emptyList()
        DsBotParser.parseState(JSONObject(raw))
    }.getOrDefault(emptyList())

    fun save(state: JSONObject) {
        runCatching {
            val raw = state.toString()
            if (raw.length <= MAX_CHARS) {
                prefs.edit().putString(KEY_STATE, raw).apply()
            }
        }
    }

    private companion object {
        const val KEY_STATE = "state"
        const val MAX_CHARS = 256 * 1024
    }
}
