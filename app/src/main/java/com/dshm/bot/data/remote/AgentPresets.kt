package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「Agent 预设」：新对话默认用的工作方式（系统提示 + 一组插件开关）。
 *
 * 对齐 dsh-Go `packages/dsh_client/lib/src/features_api.dart` 的
 * `AgentPresetInfo` 与 `AgentPresetDocument`。
 */
data class AgentPresetInfo(
    /** 预设标识，如 `standard`。 */
    val id: String,
    /** 是不是新对话的默认预设。 */
    val isDefault: Boolean,
    /** 自定义预设的名字；内建预设用引擎给的官方名。 */
    val name: String? = null,
    val description: String? = null,
    /** 加载失败的原因；非空时不能被选为默认。 */
    val broken: String? = null,
    /** 排序权重；null 排最后。 */
    val order: Int? = null,
) {
    /** 显示名：自定义用名字，内建用引擎给的官方名，都没有就用 id。 */
    fun displayName(): String = name?.takeIf { it.isNotBlank() } ?: id
}

/** 一个预设的原始 YAML 文档（「查看配置」用）。 */
data class AgentPresetDocument(
    val id: String,
    val content: String,
    val name: String? = null,
    val description: String? = null,
)

/**
 * 解析 `agentPresets/list` 的返回。
 *
 * 排序按 `order` 升序（null 排最后）—— 默认预设**不会**自动置顶，
 * 顺序完全由引擎给的 order 决定（对齐 dsh-Go）。
 */
fun parseAgentPresets(raw: JSONObject?): List<AgentPresetInfo> {
    if (raw == null) return emptyList()
    val array = raw.optJSONArray("presets") ?: return emptyList()
    val out = mutableListOf<AgentPresetInfo>()
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val id = item.optString("id").trim()
        if (id.isEmpty()) continue
        out += AgentPresetInfo(
            id = id,
            isDefault = item.optBoolean("isDefault", false),
            name = item.optString("name").takeIf { it.isNotEmpty() },
            description = item.optString("description").takeIf { it.isNotEmpty() },
            broken = item.optString("broken").takeIf { it.isNotEmpty() },
            order = if (item.has("order") && !item.isNull("order")) item.optInt("order") else null,
        )
    }
    return out.sortedBy { it.order ?: (1 shl 20) }
}

/** 解析 `agentPresets/read` 的返回。 */
fun parseAgentPresetDocument(raw: JSONObject?): AgentPresetDocument? {
    if (raw == null) return null
    val id = raw.optString("agentPreset").ifEmpty { raw.optString("id") }
    if (id.isEmpty()) return null
    return AgentPresetDocument(
        id = id,
        content = raw.optString("content"),
        name = raw.optString("name").takeIf { it.isNotEmpty() },
        description = raw.optString("description").takeIf { it.isNotEmpty() },
    )
}

/** 内建的四个预设：不能编辑、不能删除（对齐 dsh-Go 的 `builtInAgentPresets`）。 */
val BUILT_IN_AGENT_PRESETS = setOf("standard", "ptc", "minimal", "cordis")

/** 「设为新对话默认」要写的那个 namespace。 */
const val AGENT_PRESET_REGISTRY_NS = "agent-preset-registry"

/** 「设为新对话默认」要写的字段。 */
val AGENT_PRESET_SELECTED_DEFAULT_PATH = listOf("selectedDefault")
