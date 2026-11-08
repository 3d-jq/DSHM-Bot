package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * DS Bot 的「Bot 间对话」记录（`/api/bot` 的 `exchange` 端点）。
 *
 * 主机把每一次 Bot → Bot 的投递记在 `state.exchanges` 里：
 * `{id, from, to, text, role, time, length?, files?, sessionId?}`；
 * `text` 只是开头（`TEXT_KEPT`），`length` 表示全文有多长。
 * 对齐 `ds-bot/src/host/delivery.js` 的 `deliver` / `exchangeText`
 * 与 `src/client/exchange.js` 的 `ExchangeDialog`。
 */
data class BotExchangeEntry(
    val id: String,
    val from: String,
    val to: String,
    val text: String,
    val role: String,
    val timeMs: Long,
    /** 全文长度；null 表示 [text] 就是全文。 */
    val length: Int?,
)

/** 解析 `exchange` 的数组；形状不对时返回空列表。 */
fun parseBotExchange(value: Any?): List<BotExchangeEntry> {
    val array = value as? JSONArray ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val from = item.optString("from").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val to = item.optString("to").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        BotExchangeEntry(
            id = id,
            from = from,
            to = to,
            text = item.optString("text"),
            role = item.optString("role"),
            timeMs = item.optLong("time", 0L),
            length = if (item.has("length") && !item.isNull("length")) item.optInt("length") else null,
        )
    }
}

/** `exchange-text` 的结果：整段文本 + 是否读到了全文。 */
data class BotExchangeText(val text: String, val whole: Boolean) {
    companion object {
        fun fromJson(value: Any?): BotExchangeText? {
            val obj = value as? JSONObject ?: return null
            return BotExchangeText(
                text = obj.optString("text"),
                whole = obj.optBoolean("whole", true),
            )
        }
    }
}
