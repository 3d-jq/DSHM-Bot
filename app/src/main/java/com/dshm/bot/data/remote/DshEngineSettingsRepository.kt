package com.dshm.bot.data.remote

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * 引擎设置（对齐 dsh-Go `dsh_client` 的 engine_settings_api / providers_api /
 * session_api 子集）：
 *
 * - `settings/describe` → 每个 namespace 的 schema / value / base / user / secrets
 * - `settings/mutate {ns, ops, expectedRevision}` → 新的 namespace 视图
 * - `pluginInventory/list` → 插件条目
 * - `permissionPresets/catalog` → 权限预设
 * - `credentials/describe|set|unset` → API Key（值永远不回显）
 */
class DshEngineSettingsRepository(private val client: DshClient) {

    /** 语音输入用例，和设置 schema 解耦。 */
    val speech: SpeechRepository = RemoteSpeechRepository(client)

    suspend fun describe(): SettingsDescription {
        val root = client.rpc("settings/describe")
        val writable = root.optBoolean("writable", true)
        val namespaces = LinkedHashMap<String, SettingsNamespace>()
        val array = root.optJSONArray("namespaces") ?: JSONArray()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val namespace = SettingsNamespace.fromJson(item, writable) ?: continue
            namespaces[namespace.ns] = namespace
        }
        return SettingsDescription(
            namespaces = namespaces,
            writable = writable,
            hasDocument = root.optBoolean("hasDocument", false),
        )
    }

    suspend fun mutate(
        ns: String,
        ops: List<SettingsOp>,
        expectedRevision: Int?,
        writable: Boolean = true,
    ): SettingsNamespace {
        val args = JSONObject()
            .put("ns", ns)
            .put("ops", JSONArray().apply { ops.forEach { put(it.toJson()) } })
        if (expectedRevision != null) args.put("expectedRevision", expectedRevision)
        val root = client.rpc("settings/mutate", args)
        return SettingsNamespace.fromJson(root, writable)
            ?: error("settings/mutate 返回了无效的 namespace：$ns")
    }

    suspend fun pluginInventory(): PluginInventory {
        val root = client.rpc("pluginInventory/list")
        val entries = mutableListOf<PluginInventoryEntry>()
        root.optJSONArray("entries")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val entryId = item.optString("entryId")
                val moduleName = item.optString("moduleName")
                if (entryId.isEmpty() && moduleName.isEmpty()) continue
                entries.add(
                    PluginInventoryEntry(
                        entryId = entryId,
                        moduleName = moduleName,
                        enabled = item.optBoolean("enabled", false),
                        fiberPhase = item.anyOrNull("fiberPhase") as? String,
                        meta = PluginDisplayMeta.fromJson(item.optJSONObject("meta")),
                    ),
                )
            }
        }
        // 引擎组合出来的 Agent 预设：每个预设带出来哪些插件条目。
        val presets = mutableListOf<PresetPlugins>()
        root.optJSONArray("agentPresets")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id.isEmpty()) continue
                val rows = mutableListOf<PluginInventoryEntry>()
                item.optJSONArray("rows")?.let { rowsArray ->
                    for (rowIndex in 0 until rowsArray.length()) {
                        val row = rowsArray.optJSONObject(rowIndex) ?: continue
                        val entryId = row.optString("entryId")
                        val moduleName = row.optString("moduleName")
                        if (entryId.isEmpty() && moduleName.isEmpty()) continue
                        rows.add(
                            PluginInventoryEntry(
                                entryId = entryId,
                                moduleName = moduleName,
                                enabled = row.optBoolean("enabled", false),
                                fiberPhase = row.anyOrNull("fiberPhase") as? String,
                                meta = PluginDisplayMeta.fromJson(row.optJSONObject("meta")),
                            ),
                        )
                    }
                }
                presets.add(
                    PresetPlugins(
                        id = id,
                        name = item.optString("name").takeIf { it.isNotEmpty() },
                        isDefault = item.optBoolean("isDefault", false),
                        broken = item.optString("broken").takeIf { it.isNotEmpty() },
                        rows = rows,
                    ),
                )
            }
        }
        return PluginInventory(
            entries = entries,
            managementAvailable = root.optBoolean("managementAvailable", false),
            presets = presets,
        )
    }

    /**
     * 卸载一个插件包（`pluginManager/removeBundle`）。
     * 引擎会把它卸掉并恢复原来的文件。
     */
    suspend fun removeBundle(name: String) {
        client.rpc("pluginManager/removeBundle", JSONObject().put("name", name), timeoutMs = 600_000)
    }

    /**
     * 按安装目标重新安装一个包 —— 「更新」就是重装一遍。
     * 安装要跑 pnpm 加激活，很慢，所以给 30 分钟。
     */
    suspend fun installBundle(spec: String, enabled: Boolean?) {
        val options = JSONObject().apply { if (enabled != null) put("enabled", enabled) }
        client.rpc(
            "pluginManager/installBundle",
            JSONObject().put("spec", spec).put("options", options),
            timeoutMs = 30 * 60_000,
        )
    }

    /** `pluginManager/listBundles`：官方可选包 + 第三方已安装包（隐藏基础设施包）。 */
    suspend fun pluginBundles(): List<PluginBundle> {
        val array = client.rpcValue("pluginManager/listBundles") as? JSONArray ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val bundle = parsePluginBundle(item)
                if (bundle.name.isNotEmpty() && bundle.group != PluginBundleGroup.HIDDEN) add(bundle)
            }
        }
    }

    /** Host 的结果：`application=restart-required` 表示已保存但需要重启引擎。 */
    suspend fun setPluginBundleEnabled(name: String, enabled: Boolean): PluginToggleResult {
        val result = client.rpc(
            "pluginManager/setBundleEnabled",
            JSONObject().put("name", name).put("enabled", enabled),
        )
        val error = result.optJSONObject("error")
        val message = error?.let {
            (it.anyOrNull("diagnostic") as? String)
                ?: (it.anyOrNull("code") as? String)
                ?: "操作失败"
        }
        val application = result.optString("application")
        val needsRestart = application == "restart-required"
        val succeeded = error == null &&
            (application.isEmpty() || application in setOf("applied", "restart-required", "overridden"))
        return PluginToggleResult(succeeded = succeeded, needsRestart = needsRestart, message = message)
    }

    suspend fun permissionCatalog(): PermissionCatalog {
        val root = client.rpc("permissionPresets/catalog")
        fun options(raw: JSONArray?): List<PermissionOption> {
            if (raw == null) return emptyList()
            return buildList {
                for (index in 0 until raw.length()) {
                    val item = raw.optJSONObject(index) ?: continue
                    val value = item.optString("value")
                    if (value.isEmpty()) continue
                    add(
                        PermissionOption(
                            value = value,
                            name = item.optString("name", value),
                            description = item.anyOrNull("description") as? String,
                        ),
                    )
                }
            }
        }
        val all = options(root.optJSONArray("options"))
        val defaults = if (root.has("defaultOptions")) {
            options(root.optJSONArray("defaultOptions"))
        } else {
            all.filterNot { it.value == "auto" }
        }
        return PermissionCatalog(
            options = all,
            defaultOptions = defaults,
            defaultPreset = root.optString("defaultPreset"),
        )
    }

    suspend fun describeCredentials(refs: List<String>): Map<String, CredentialStatus> {
        if (refs.isEmpty()) return emptyMap()
        val root = client.rpc("credentials/describe", JSONObject().put("refs", JSONArray(refs)))
        val out = LinkedHashMap<String, CredentialStatus>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = root.optJSONObject(key) ?: continue
            out[key] = CredentialStatus(
                configured = item.optBoolean("configured", false),
                writable = item.optBoolean("writable", false),
                source = item.anyOrNull("source") as? String,
            )
        }
        return out
    }

    suspend fun setCredential(ref: String, value: String) {
        require(value.isNotBlank()) { "API Key 不能为空" }
        client.rpc("credentials/set", JSONObject().put("ref", ref).put("value", value))
    }

    suspend fun unsetCredential(ref: String) {
        client.rpc("credentials/unset", JSONObject().put("ref", ref))
    }

    /** 只取一个 namespace，用于 providers / permission 这类单独页面。 */
    suspend fun section(ns: String): SettingsNamespace? = describe().namespace(ns)

    data class ListedProvider(val id: String, val name: String)

    /** `llm/listProviders`：引擎当前注册的渠道（官方 / 订阅）。 */
    suspend fun listProviders(): List<ListedProvider> {
        val array = client.rpcValue("llm/listProviders") as? JSONArray ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id.isEmpty()) continue
                add(ListedProvider(id = id, name = item.optString("name").ifEmpty { id }))
            }
        }
    }

    /**
     * `llm/discoverModels`：对齐 dsh-Go `ProviderActions.discover`。
     *
     * `provider` 认已存凭据（`storedProfile.resolveApiKey`），`baseURL/api` 用来问端点，
     * 两者同时传；只有用户当场输入的新 Key 才作为 `apiKey` 明文传过去。
     * 裸域名端点返回 404 时，按 dsh-Go 的写法补一次 `/v1` 并回填生效 URL。
     */
    suspend fun discoverModels(
        baseUrl: String,
        api: String,
        apiKey: String,
        route: String?,
    ): DiscoveredModels {
        val candidates = discoveryBaseUrlCandidates(baseUrl, api)

        suspend fun ask(url: String): List<String> {
            val call = JSONObject()
                .put("settingsNs", PROVIDER_NAMESPACE)
                .put("request", discoveryRequest(url, api, apiKey, route))
            val value = client.rpcValue("llm/discoverModels", call)
            val array = value as? JSONArray ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    if (id.isNotEmpty()) add(id)
                }
            }
        }

        var firstError: Throwable? = null
        for ((index, candidate) in candidates.withIndex()) {
            val result = runCatching { ask(candidate) }
            if (result.isSuccess) return DiscoveredModels(result.getOrThrow(), candidate)
            val error = result.exceptionOrNull() ?: continue
            if (index == 0) {
                if (candidates.size == 1 || !isDiscoveryNotFound(error)) throw error
                firstError = error
            } else {
                throw firstError ?: error
            }
        }
        error("unreachable")
    }

    suspend fun providerSettings(): ProviderSettings =
        ProviderSettings.fromNamespace(describe().namespace(PROVIDER_NAMESPACE))

    /** 权限页：`permission` namespace 的 revision + 用户层 defaultPreset。 */
    suspend fun permissionSection(): SettingsNamespace? = describe().namespace("permission")

    // ---- 语音输入（官方 speech-to-text-sensevoice 插件） ----

    /** `speech/catalog`；插件关闭或旧 Host 没有该接口时返回 null。 */
    suspend fun speechCatalog(): SpeechCatalog? = runCatching {
        SpeechCatalog.fromJson(client.rpc("speech/catalog"))
    }.getOrNull()

    /** `speech/prepare`：让 Host 下载/校验/加载 SenseVoice 模型，立即返回。 */
    suspend fun prepareSpeech(providerId: String) {
        client.rpc("speech/prepare", JSONObject().put("providerId", providerId))
    }

    suspend fun cancelSpeechPreparation(providerId: String) {
        client.rpc("speech/cancelPreparation", JSONObject().put("providerId", providerId), timeoutMs = 60_000)
    }

    suspend fun configureSpeech(providerId: String?, language: String?) {
        val patch = JSONObject()
        providerId?.takeIf { it.isNotBlank() }?.let { patch.put("providerId", it) }
        language?.takeIf { it.isNotBlank() }?.let { patch.put("language", it) }
        client.rpc("speech/configure", JSONObject().put("patch", patch))
    }

    /** `speech/transcribe`：16 kHz 单声道 PCM16 WAV → 最终文字。 */
    suspend fun transcribeSpeech(wav: ByteArray): String {
        val request = JSONObject().put(
            "audioBase64",
            Base64.encodeToString(wav, Base64.NO_WRAP),
        )
        val result = client.rpc(
            "speech/transcribe",
            JSONObject().put("request", request),
            timeoutMs = 180_000,
        )
        return result.optString("text").trim()
    }
}
