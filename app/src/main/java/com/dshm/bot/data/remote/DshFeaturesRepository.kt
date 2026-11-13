package com.dshm.bot.data.remote

import org.json.JSONObject

/**
 * 「Agent 预设」相关的引擎接口，对齐 dsh-Go
 * `packages/dsh_client/lib/src/features_api.dart`：
 *
 * - `agentPresets/list`：预设清单（含哪个是默认）；
 * - `agentPresets/read`：读一个预设的原始 YAML；
 * - 设默认走 `settings/mutate`（namespace `agent-preset-registry` 的
 *   `selectedDefault`）；
 * - 删除走 `pluginManager/removeBundle`（删的是声明这个预设的组合包）。
 */
class DshFeaturesRepository(private val client: DshClient) : AgentPresetSource {

    /** 预设清单；引擎没这个接口时返回空表。 */
    override suspend fun agentPresets(): List<AgentPresetInfo> {
        val raw = client.rpc("agentPresets/list")
        return parseAgentPresets(raw)
    }

    /** 读一个预设的原始 YAML（「查看配置」用）。 */
    override suspend fun agentPresetDocument(id: String): AgentPresetDocument? {
        val raw = client.rpc("agentPresets/read", JSONObject().put("agentPreset", id))
        return parseAgentPresetDocument(raw)
    }

    /** 移除声明某个预设的组合包（删除预设）。 */
    override suspend fun removeBundle(name: String) {
        client.rpc("pluginManager/removeBundle", JSONObject().put("name", name))
    }
}
