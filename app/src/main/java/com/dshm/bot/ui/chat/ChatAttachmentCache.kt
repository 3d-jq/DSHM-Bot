package com.dshm.bot.ui.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.edit

/**
 * 自己发出去的附件（图片缩略图 / 文件卡）在历史重载后的还原表。
 *
 * 引擎的 `chat` 历史里用户消息只有一个 `images` 计数（`parts.js`），没有路径 ——
 * DS Bot 网页版重载后也只显示「N 张图片」。我们能做得更好：发送时按 `rpcId`
 * 记下本地文件，历史回来后按 `user:$rpcId` 把缩略图贴回去。
 */
internal class ChatAttachmentCache(context: Context, botId: String) {
    private val prefs = context.applicationContext
        .getSharedPreferences("bot-chat-attachments:$botId", Context.MODE_PRIVATE)

    fun load(): Map<String, List<ChatAttachmentItem>> = runCatching {
        val raw = prefs.getString(KEY_MAP, null) ?: return emptyMap()
        val json = JSONObject(raw)
        buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val array = json.optJSONArray(key) ?: continue
                val items = buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val path = item.optString("path")
                        if (path.isEmpty()) continue
                        add(
                            ChatAttachmentItem(
                                name = item.optString("name"),
                                path = path,
                                isImage = item.optBoolean("image", false),
                            ),
                        )
                    }
                }
                if (items.isNotEmpty()) put(key, items)
            }
        }
    }.getOrDefault(emptyMap())

    fun save(all: Map<String, List<ChatAttachmentItem>>) {
        runCatching {
            val json = JSONObject()
            // 只保留最近 [MAX_ENTRIES] 条，别让偏好文件无限长。
            all.entries.toList().takeLast(MAX_ENTRIES).forEach { (key, items) ->
                val array = JSONArray()
                items.forEach { item ->
                    array.put(
                        JSONObject()
                            .put("name", item.name)
                            .put("path", item.path)
                            .put("image", item.isImage),
                    )
                }
                json.put(key, array)
            }
            prefs.edit { putString(KEY_MAP, json.toString()) }
        }
    }

    private companion object {
        const val KEY_MAP = "map"
        const val MAX_ENTRIES = 200
    }
}
