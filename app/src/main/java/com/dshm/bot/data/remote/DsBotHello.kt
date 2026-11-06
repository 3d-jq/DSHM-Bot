package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * DS Bot 移动端 `hello` 的能力协商结果，对齐
 * `DS-Bot-mobile-api/ds-bot/src/host/mobile.js` 的 `hello(body)`。
 *
 * [api] 为 null 表示客户端声明的版本与服务端无交集，此时应停止调用移动端专有 endpoint。
 */
data class DsBotHello(
    val api: Int?,
    val apiMin: Int,
    val apiMax: Int,
    val pluginName: String,
    val pluginVersion: String,
    val capabilities: Set<String>,
    val boot: String,
    val seq: Long,
    val revision: Long,
    val readOnly: Boolean?,
    val memory: Boolean,
    val feed: String,
    val rpcCall: String,
    val rpcFeed: String,
    val raw: JSONObject,
) {
    /** 服务端是否声明了某项能力；未协商成功时一律视为不支持。 */
    fun supports(capability: String): Boolean = api != null && capability in capabilities

    companion object {
        fun fromJson(json: JSONObject): DsBotHello {
            val plugin = json.optJSONObject("plugin") ?: JSONObject()
            val rpc = json.optJSONObject("rpc") ?: JSONObject()
            val caps = json.optJSONArray("capabilities")
            val capabilitySet = if (caps == null) {
                emptySet()
            } else {
                LinkedHashSet<String>().apply {
                    for (index in 0 until caps.length()) {
                        caps.optString(index).takeIf { it.isNotEmpty() }?.let(::add)
                    }
                }
            }
            return DsBotHello(
                api = if (json.has("api") && !json.isNull("api")) json.optInt("api") else null,
                apiMin = json.optInt("apiMin"),
                apiMax = json.optInt("apiMax"),
                pluginName = plugin.optString("name"),
                pluginVersion = plugin.optString("version"),
                capabilities = capabilitySet,
                boot = json.optString("boot"),
                seq = json.optLong("seq"),
                revision = json.optLong("revision"),
                readOnly = if (json.has("readOnly") && !json.isNull("readOnly")) {
                    json.optBoolean("readOnly")
                } else {
                    null
                },
                memory = json.optBoolean("memory", true),
                feed = json.optString("feed"),
                rpcCall = rpc.optString("call"),
                rpcFeed = rpc.optString("feed"),
                raw = json,
            )
        }
    }
}

/** `notices` 返回的一条通知；kind 见 mobile.js 的 NOTICE_KINDS。 */
data class DsBotNotice(
    val seq: Long,
    val time: Long,
    val kind: String,
    val botId: String?,
    val name: String?,
    val sessionId: String?,
    val roomId: String?,
    val title: String?,
    val text: String,
    val raw: JSONObject,
)

data class DsBotNoticePage(
    val boot: String,
    val seq: Long,
    val reset: Boolean,
    val notices: List<DsBotNotice>,
) {
    companion object {
        fun fromJson(json: JSONObject): DsBotNoticePage {
            val array = json.optJSONArray("notices") ?: JSONArray()
            val notices = List(array.length()) { index ->
                val item = array.optJSONObject(index) ?: JSONObject()
                DsBotNotice(
                    seq = item.optLong("seq"),
                    time = item.optLong("time"),
                    kind = item.optString("kind"),
                    botId = item.optString("botId").takeIf { it.isNotEmpty() },
                    name = item.optString("name").takeIf { it.isNotEmpty() },
                    sessionId = item.optString("sessionId").takeIf { it.isNotEmpty() },
                    roomId = item.optString("roomId").takeIf { it.isNotEmpty() },
                    title = item.optString("title").takeIf { it.isNotEmpty() },
                    text = item.optString("text"),
                    raw = item,
                )
            }
            return DsBotNoticePage(
                boot = json.optString("boot"),
                seq = json.optLong("seq"),
                reset = json.optBoolean("reset", false),
                notices = notices,
            )
        }
    }
}
