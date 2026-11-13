package com.dshm.bot.data.remote

import com.dshm.bot.data.runtime.RuntimeEndpoint
import org.json.JSONArray

/**
 * ViewModel 的依赖接缝。
 *
 * 这两个 ViewModel 原来在内部直接 `new` 具体 repository / `DshClient`，
 * revision 冲突、凭据写入、事件流 → 卡片这些路径都没法写 JVM 单测。
 * 这里按 ViewModel **实际用到的方法**抽最小接口，生产实现仍是原来的类，
 * 只加接口声明；测试用假实现替换。
 */

/** `EngineSettingsViewModel` 用到的引擎设置能力（`DshEngineSettingsRepository` 的子集）。 */
interface EngineSettingsGateway {
    /** 官方语音输入插件。 */
    val speech: SpeechRepository

    suspend fun describe(): SettingsDescription

    suspend fun mutate(
        ns: String,
        ops: List<SettingsOp>,
        expectedRevision: Int?,
        writable: Boolean = true,
    ): SettingsNamespace

    suspend fun pluginInventory(): PluginInventory

    suspend fun pluginBundles(): List<PluginBundle>

    suspend fun permissionCatalog(): PermissionCatalog

    suspend fun describeCredentials(refs: List<String>): Map<String, CredentialStatus>

    suspend fun setCredential(ref: String, value: String)

    suspend fun unsetCredential(ref: String)

    suspend fun listProviders(): List<DshEngineSettingsRepository.ListedProvider>

    suspend fun discoverModels(
        baseUrl: String,
        api: String,
        apiKey: String,
        route: String?,
    ): DiscoveredModels

    suspend fun removeBundle(name: String)

    suspend fun installBundle(spec: String, enabled: Boolean?)

    suspend fun setPluginBundleEnabled(name: String, enabled: Boolean): PluginToggleResult
}

/** `EngineSettingsViewModel` 的模型目录能力（`DshSessionRepository` 的子集）。 */
interface SessionModelCatalogSource {
    suspend fun modelCatalog(): org.json.JSONObject
}

/** `EngineSettingsViewModel` 的 Agent 预设能力（`DshFeaturesRepository` 的子集）。 */
interface AgentPresetSource {
    suspend fun agentPresets(): List<AgentPresetInfo>
    suspend fun agentPresetDocument(id: String): AgentPresetDocument?
    suspend fun removeBundle(name: String)
}

/** 一个端点对应的三组 repository。 */
data class EngineSettingsRepositories(
    val settings: EngineSettingsGateway,
    val sessions: SessionModelCatalogSource,
    val features: AgentPresetSource,
)

/**
 * 按引擎端点创建 repository 的工厂。
 *
 * 生产实现走 `DshClients.of(endpoint)`；测试传假工厂即可让 ViewModel
 * 在纯 JVM 里跑（不需要引擎、不需要 Context）。
 */
fun interface EngineSettingsRepositoryFactory {
    fun create(endpoint: RuntimeEndpoint): EngineSettingsRepositories
}

/** 生产实现的工厂：三组 repository 共享同一个缓存 client。 */
val ProductionEngineSettingsRepositories = EngineSettingsRepositoryFactory { endpoint ->
    val client = DshClients.of(endpoint)
    EngineSettingsRepositories(
        settings = DshEngineSettingsRepository(client),
        sessions = DshSessionRepository(client),
        features = DshFeaturesRepository(client),
    )
}

/**
 * `BotChatViewModel` 用到的发送 / 附件 / 提问应答能力。
 *
 * 替代 ViewModel 内部临时 `DshSessionRepository(rawClient)` 的写法：
 * 测试传假实现就能断言「发了什么」，不用起引擎。
 */
interface ChatTransport {
    suspend fun uploadAttachment(
        sessionId: String,
        name: String,
        bytes: ByteArray,
    ): DshUploadReceipt

    suspend fun prompt(
        sessionId: String,
        text: String,
        requestId: String,
        fileReceiptIds: List<String> = emptyList(),
    ): String

    suspend fun answerQuestion(clientId: String, eventId: String, answers: JSONArray)
}

/** 生产实现：包一层 `DshClient` / `DshSessionRepository`。 */
class DshChatTransport(private val client: DshClient) : ChatTransport {

    private val sessions = DshSessionRepository(client)

    override suspend fun uploadAttachment(
        sessionId: String,
        name: String,
        bytes: ByteArray,
    ): DshUploadReceipt = client.uploadAttachment(sessionId, name, bytes)

    override suspend fun prompt(
        sessionId: String,
        text: String,
        requestId: String,
        fileReceiptIds: List<String>,
    ): String = sessions.prompt(
        sessionId = sessionId,
        text = text,
        requestId = requestId,
        fileReceiptIds = fileReceiptIds,
    )

    override suspend fun answerQuestion(clientId: String, eventId: String, answers: JSONArray) {
        sessions.answerQuestion(clientId, eventId, answers)
    }
}
