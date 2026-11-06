package com.dshm.bot.data.remote

import org.json.JSONObject

/**
 * 主页 Bot 行下面那行文字：**大模型最新输出的信息预览**。
 *
 * 引擎的 `state` 里 Bot 只有 `role`（角色描述），没有消息预览 ——
 * 群聊才有 `preview`（最后一条消息）。Bot 的预览要问 `chat` 端点：
 * 取最后一页里最后一条 `kind = "bot"` 的文本。
 */
internal object BotPreview {

    /**
     * 从 `chat` 端点的返回里取最后一条模型输出。
     *
     * @return 预览文本；没有模型输出时返回 null（调用方决定显示什么）。
     */
    fun fromChatPage(raw: JSONObject?): String? {
        val items = raw?.optJSONArray("items") ?: return null
        for (index in items.length() - 1 downTo 0) {
            val item = items.optJSONObject(index) ?: continue
            if (item.optString("kind") != "bot") continue
            val text = item.optString("text").trim()
            if (text.isNotEmpty()) return text
        }
        return null
    }

    /**
     * 从 `usage` 端点里取出「有过活动」的 Bot id。
     *
     * `usage` 的 `owners` 列出所有有过用量的归属（bot / room / agent / other），
     * 一次请求就知道哪些 Bot 聊过 —— 主页补预览时只问这些 Bot，
     * 不用对每个 Bot 都问一次 `chat`。
     */
    fun activeBotIds(raw: JSONObject?): Set<String> {
        val owners = raw?.optJSONArray("owners") ?: return emptySet()
        val ids = mutableSetOf<String>()
        for (index in 0 until owners.length()) {
            val owner = owners.optJSONObject(index) ?: continue
            if (owner.optString("kind") != "bot") continue
            val id = owner.optString("id").ifEmpty { continue }
            ids += id
        }
        return ids
    }

    /**
     * 预览文字压成一行：换行压成空格、去掉多余空白、限长。
     */
    fun oneLine(text: String, maxLength: Int = 120): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= maxLength) flat else flat.take(maxLength - 1) + "…"
    }
}
