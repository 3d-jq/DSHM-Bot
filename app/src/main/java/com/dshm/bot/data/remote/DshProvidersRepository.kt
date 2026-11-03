package com.dshm.bot.data.remote

import org.json.JSONArray

/**
 * 模型渠道（对齐 dsh-Go `providers_page.dart` 的数据来源）：
 * `llm/listProviders` 给出可用的模型渠道，`llm/listConfigurableProviders` 给出引擎里自定义的渠道。
 */
class DshProvidersRepository(private val client: DshClient) {

    enum class Kind { OFFICIAL, SUBSCRIPTION, CUSTOM }

    data class Provider(val id: String, val name: String, val kind: Kind)

    suspend fun providers(): List<Provider> {
        val listed = client.rpcValue("llm/listProviders") as? JSONArray
        val configurable = client.rpcValue("llm/listConfigurableProviders") as? JSONArray
        val result = LinkedHashMap<String, Provider>()
        fun put(id: String, name: String, kind: Kind) {
            if (id.isEmpty()) return
            result.putIfAbsent(id, Provider(id, name.ifEmpty { id }, kind))
        }
        if (listed != null) {
            for (index in 0 until listed.length()) {
                val item = listed.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val name = item.optString("name")
                put(id, name, classify(id))
            }
        }
        if (configurable != null) {
            for (index in 0 until configurable.length()) {
                val item = configurable.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val name = item.optString("name").ifEmpty { id }
                put(id, name, Kind.CUSTOM)
            }
        }
        return result.values.toList()
    }

    private fun classify(id: String): Kind {
        val lower = id.lowercase()
        return when {
            lower.contains("deepseek") -> Kind.OFFICIAL
            lower.contains("openai") || lower.contains("anthropic") || lower.contains("claude") || lower.contains("chatgpt") -> Kind.SUBSCRIPTION
            else -> Kind.CUSTOM
        }
    }
}
