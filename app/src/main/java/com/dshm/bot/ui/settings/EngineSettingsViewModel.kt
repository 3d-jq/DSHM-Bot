package com.dshm.bot.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshm.bot.data.remote.CredentialStatus
import com.dshm.bot.data.remote.DshClient
import com.dshm.bot.data.remote.DshClients
import com.dshm.bot.data.remote.DshEngineSettingsRepository
import com.dshm.bot.data.remote.DshRpcException
import com.dshm.bot.data.remote.DshFeaturesRepository
import com.dshm.bot.data.remote.DshSessionRepository
import com.dshm.bot.data.remote.PROVIDER_NAMESPACE
import com.dshm.bot.data.remote.PermissionCatalog
import com.dshm.bot.data.remote.PluginBundle
import com.dshm.bot.data.remote.AGENT_PRESET_REGISTRY_NS
import com.dshm.bot.data.remote.AGENT_PRESET_SELECTED_DEFAULT_PATH
import com.dshm.bot.data.remote.AgentPresetDocument
import com.dshm.bot.data.remote.AgentPresetInfo
import com.dshm.bot.data.remote.ModelCatalog
import com.dshm.bot.data.remote.PluginInventory
import com.dshm.bot.data.remote.ProviderModelSpec
import com.dshm.bot.data.remote.ProviderProfile
import com.dshm.bot.data.remote.ProviderSettings
import com.dshm.bot.data.remote.SettingsDescription
import com.dshm.bot.data.remote.SettingsNamespace
import com.dshm.bot.data.remote.SettingsOp
import com.dshm.bot.data.remote.SpeechCatalog
import com.dshm.bot.data.remote.deriveProviderKeyRef
import com.dshm.bot.data.remote.jsonEquals
import com.dshm.bot.data.remote.pathKey
import com.dshm.bot.data.remote.providerRoutePattern
import com.dshm.bot.data.runtime.RuntimeEndpoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * 引擎设置状态层（设置外壳共享）：
 * - 读 `settings/describe` 的完整 schema；
 * - 写按 namespace 串行，带 revision 冲突重读/重试；
 * - providers 走 `llm-pi-ai` + `credentials/set|unset`；
 * - 插件清单走 `pluginInventory/list`。
 */
class EngineSettingsViewModel : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val description: SettingsDescription? = null,
        val plugins: PluginInventory? = null,
        val bundles: List<PluginBundle> = emptyList(),
        val permission: PermissionCatalog? = null,
        val listedProviders: List<DshEngineSettingsRepository.ListedProvider> = emptyList(),
        val credentials: Map<String, CredentialStatus> = emptyMap(),
        val saving: Set<String> = emptySet(),
        val fieldErrors: Map<String, String> = emptyMap(),
        val error: String? = null,
        val message: String? = null,
        /** 插件开关已保存但需要重启本机引擎。 */
        val pluginRestartNeeded: Boolean = false,
        /** 官方语音输入插件的 `speech/catalog`。 */
        val speech: SpeechCatalog? = null,
        /** 模型目录（`session/modelCatalog`），子代理页挑模型用。 */
        val modelCatalog: ModelCatalog? = null,
        val modelCatalogError: String? = null,
        /** 「Agent 预设」清单。 */
        val agentPresets: List<AgentPresetInfo> = emptyList(),
        val agentPresetsError: String? = null,
        val speechLoading: Boolean = false,
        val speechError: String? = null,
        val speechPreparing: Boolean = false,
    )

    private var repository: DshEngineSettingsRepository? = null
    private var sessionRepository: DshSessionRepository? = null
    private var featuresRepository: DshFeaturesRepository? = null
    private var endpoint: RuntimeEndpoint? = null
    private var speechPoll: Job? = null
    private val writeMutex = Mutex()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun setEndpoint(next: RuntimeEndpoint?) {
        if (endpoint == next && repository != null) return
        endpoint = next
        repository = next?.let { DshEngineSettingsRepository(DshClients.of(it)) }
        sessionRepository = next?.let { DshSessionRepository(DshClients.of(it)) }
        featuresRepository = next?.let { DshFeaturesRepository(DshClients.of(it)) }
        if (repository == null) {
            _ui.value = UiState(error = "本机引擎未运行")
        } else {
            refresh()
        }
    }

    /** 读「Agent 预设」清单。 */
    fun refreshAgentPresets() {
        val features = featuresRepository ?: return
        viewModelScope.launch {
            runCatching { features.agentPresets() }
                .onSuccess { presets ->
                    _ui.update { it.copy(agentPresets = presets, agentPresetsError = null) }
                }
                .onFailure { failure ->
                    _ui.update { it.copy(agentPresetsError = describeSettingsError(failure)) }
                }
        }
    }

    /** 把某个预设设为新对话的默认（写 `agent-preset-registry` 的 `selectedDefault`）。 */
    fun setDefaultAgentPreset(id: String, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            val error = applyWrite(
                AGENT_PRESET_REGISTRY_NS,
                listOf(SettingsOp.set(AGENT_PRESET_SELECTED_DEFAULT_PATH, id)),
                listOf(AGENT_PRESET_SELECTED_DEFAULT_PATH),
            )
            if (error == null) refreshAgentPresets()
            onDone(error)
        }
    }

    /**
     * 读一个预设的原始 YAML（「查看配置」用）。
     */
    fun agentPresetDocument(id: String, onDone: (AgentPresetDocument?) -> Unit) {
        val features = featuresRepository ?: run {
            onDone(null)
            return
        }
        viewModelScope.launch {
            val document = runCatching { features.agentPresetDocument(id) }.getOrNull()
            onDone(document)
        }
    }

    /**
     * 删除一个预设：删的是**声明它的那个组合包**（对齐 dsh-Go 的 `deletePresetBundle`）。
     * 默认预设不能删 —— 得先把别的预设设为默认。
     */
    fun deleteAgentPreset(preset: AgentPresetInfo, onDone: (String?) -> Unit = {}) {
        val features = featuresRepository ?: run {
            onDone("本机引擎未运行")
            return
        }
        val bundle = presetBundleOf(preset.id)
        if (bundle == null) {
            onDone("没有找到声明这个预设的插件包")
            return
        }
        viewModelScope.launch {
            val error = runCatching { features.removeBundle(bundle.name) }
                .exceptionOrNull()
                ?.let { describeSettingsError(it) }
            if (error == null) refreshAgentPresets()
            onDone(error)
        }
    }

    /** 哪个组合包声明了这个预设（`@deepseek-ai/dsh-agent-preset` 的 `preset-<id>` 行）。 */
    private fun presetBundleOf(presetId: String): PluginBundle? =
        _ui.value.bundles.firstOrNull { bundle ->
            bundle.rows.any { row ->
                row.moduleName == AGENT_PRESET_MODULE && row.rowId == "preset-$presetId"
            }
        }

    /**
     * 读模型目录（`session/modelCatalog`）：子代理页要给用户挑可选模型。
     * 目录只有配好凭据的渠道，失败不报错，只是空目录。
     */
    fun refreshModelCatalog() {
        val session = sessionRepository ?: return
        viewModelScope.launch {
            runCatching { session.modelCatalog() }
                .onSuccess { raw ->
                    _ui.update { it.copy(modelCatalog = ModelCatalog.fromJson(raw), modelCatalogError = null) }
                }
                .onFailure { failure ->
                    _ui.update { it.copy(modelCatalogError = describeSettingsError(failure)) }
                }
        }
    }

    private data class Loaded(
        val description: SettingsDescription,
        val plugins: PluginInventory?,
        val bundles: List<PluginBundle>,
        val permission: PermissionCatalog?,
        val listedProviders: List<DshEngineSettingsRepository.ListedProvider>,
    )

    fun setPluginEnabled(name: String, enabled: Boolean, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            val repo = repository
            if (repo == null) {
                onDone("本机引擎未运行")
                return@launch
            }
            val result = runCatching { repo.setPluginBundleEnabled(name, enabled) }.getOrNull()
            val error = if (result == null) {
                "插件操作失败"
            } else if (!result.succeeded) {
                result.message ?: "插件操作失败"
            } else {
                null
            }
            if (result?.succeeded == true) {
                _ui.update { state ->
                    state.copy(
                        bundles = state.bundles.map {
                            if (it.name == name) it.copy(enabled = enabled) else it
                        },
                        pluginRestartNeeded = state.pluginRestartNeeded || result.needsRestart,
                    )
                }
                if (result.needsRestart) refresh()
            }
            onDone(error)
        }
    }

    fun clearPluginRestart() {
        _ui.update { it.copy(pluginRestartNeeded = false) }
    }

    /** 读 `speech/catalog`；语音设置页打开时调用。 */
    fun refreshSpeech() {
        val repo = repository ?: return
        speechPoll?.cancel()
        _ui.update { it.copy(speechLoading = true, speechError = null) }
        viewModelScope.launch {
            val catalog = runCatching { repo.speech.catalog() }.getOrNull()
            _ui.update {
                it.copy(
                    speechLoading = false,
                    speech = catalog,
                    speechError = if (catalog == null) "语音插件未启用，或本机引擎未运行" else null,
                    speechPreparing = catalog?.providers?.any { provider -> provider.busy } == true,
                )
            }
            if (catalog?.providers?.any { it.busy } == true) pollSpeech(repo)
        }
    }

    /** `speech/prepare`：开始下载/准备 SenseVoice 模型，然后轮询进度。 */
    fun prepareSpeech() {
        val repo = repository ?: return
        val catalog = _ui.value.speech
        val provider = catalog?.selected ?: catalog?.providers?.firstOrNull()
        if (provider == null) {
            _ui.update { it.copy(speechError = "没有可用的语音识别器") }
            return
        }
        _ui.update { it.copy(speechPreparing = true, speechError = null) }
        viewModelScope.launch {
            if (provider.languages.isNotEmpty() && catalog?.language.isNullOrBlank()) {
                // 使用识别器的首选语言，避免 Host 选错语言。
                runCatching { repo.speech.configure(provider.id, provider.languages.first()) }
            }
            runCatching { repo.speech.prepare(provider.id) }
                .onFailure { error ->
                    _ui.update {
                        it.copy(
                            speechPreparing = false,
                            speechError = describeSettingsError(error),
                        )
                    }
                }
                .onSuccess {
                    pollSpeech(repo)
                }
        }
    }

    fun cancelSpeechPreparation() {
        val repo = repository ?: return
        val provider = _ui.value.speech?.selected ?: _ui.value.speech?.providers?.firstOrNull() ?: return
        viewModelScope.launch {
            runCatching { repo.speech.cancelPreparation(provider.id) }
            speechPoll?.cancel()
            _ui.update { it.copy(speechPreparing = false) }
            refreshSpeech()
        }
    }

    private fun pollSpeech(repo: DshEngineSettingsRepository) {
        speechPoll?.cancel()
        speechPoll = viewModelScope.launch {
            repeat(400) {
                delay(1_500)
                val catalog = runCatching { repo.speech.catalog() }.getOrNull() ?: return@repeat
                _ui.update {
                    it.copy(
                        speech = catalog,
                        speechLoading = false,
                        speechPreparing = catalog.providers.any { provider -> provider.busy },
                    )
                }
                if (catalog.providers.none { it.busy }) {
                    _ui.update { it.copy(speechPreparing = false) }
                    return@launch
                }
            }
            _ui.update { it.copy(speechPreparing = false) }
        }
    }

    fun refresh() {
        val repo = repository ?: return
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                Loaded(
                    description = repo.describe(),
                    plugins = runCatching { repo.pluginInventory() }.getOrNull(),
                    bundles = runCatching { repo.pluginBundles() }
                        .onFailure { Log.w("DshSettings", "pluginManager/listBundles failed", it) }
                        .getOrDefault(emptyList()),
                    permission = runCatching { repo.permissionCatalog() }.getOrNull(),
                    listedProviders = runCatching { repo.listProviders() }.getOrDefault(emptyList()),
                )
            }.onSuccess { loaded ->
                _ui.update {
                    it.copy(
                        loading = false,
                        description = loaded.description,
                        plugins = loaded.plugins,
                        bundles = loaded.bundles,
                        permission = loaded.permission,
                        listedProviders = loaded.listedProviders,
                        error = null,
                    )
                }
                loadCredentials(repo, loaded.description)
            }.onFailure { error ->
                _ui.update { it.copy(loading = false, error = describeSettingsError(error)) }
            }
        }
    }

    private fun loadCredentials(repo: DshEngineSettingsRepository, description: SettingsDescription) {
        val refs = credentialRefs(description)
        if (refs.isEmpty()) return
        viewModelScope.launch {
            runCatching { repo.describeCredentials(refs) }
                .onSuccess { credentials -> _ui.update { it.copy(credentials = credentials) } }
        }
    }

    private fun credentialRefs(description: SettingsDescription): List<String> = buildSet {
        ProviderSettings.fromNamespace(description.namespace(PROVIDER_NAMESPACE))
            .profiles
            .forEach { add(it.apiKeyEnv ?: deriveProviderKeyRef(it.route)) }
        for (namespace in description.namespaces.values) {
            for (key in namespace.secrets.keys) {
                val path = key.split('\u0000').filter { it.isNotEmpty() }
                namespace.credentialRefOf(path)?.let { add(it) }
            }
        }
    }.toList()

    fun reloadCredentials() {
        val repo = repository ?: return
        val description = _ui.value.description ?: return
        loadCredentials(repo, description)
    }

    // ------------------------------------------------------------------
    // 写操作
    // ------------------------------------------------------------------

    fun setValue(ns: String, path: List<String>, value: Any?) {
        viewModelScope.launch { applyWrite(ns, listOf(SettingsOp.set(path, value)), listOf(path)) }
    }

    /**
     * 一次写多个字段（引擎当成一个事务）。
     * 子代理页「打开开关 + 选第一个模型」必须一起发，引擎才不拒绝。
     */
    fun setValues(ns: String, values: List<Pair<List<String>, Any?>>) {
        if (values.isEmpty()) return
        viewModelScope.launch {
            applyWrite(
                ns,
                values.map { (path, value) -> SettingsOp.set(path, value) },
                values.map { (path, _) -> path },
            )
        }
    }

    fun unsetValue(ns: String, path: List<String>) {
        viewModelScope.launch { applyWrite(ns, listOf(SettingsOp.unset(path)), listOf(path)) }
    }

    /** 联网搜索：按 dsh-Go 同时写 searchProvider / fetchProvider。 */
    fun setWebSearchApi(api: Boolean) {
        val web = _ui.value.description?.namespace("web") ?: run {
            _ui.update { it.copy(error = "引擎没有提供 web 设置") }
            return
        }
        val fields = listOf("searchProvider", "fetchProvider")
        val ops = fields.map { field ->
            val target = if (api) {
                if (field == "searchProvider") "deepseek-search-api" else "deepseek-fetch-api"
            } else {
                if (field == "searchProvider") "deepseek-official" else "http"
            }
            val base = web.baseValueOf(listOf(field))
            if (jsonEquals(base, target)) {
                SettingsOp.unset(listOf(field))
            } else {
                SettingsOp.set(listOf(field), target)
            }
        }
        viewModelScope.launch { applyWrite("web", ops, fields.map { listOf(it) }) }
    }

    fun setPermissionDefault(value: String?) {
        val permission = _ui.value.description?.namespace("permission") ?: run {
            _ui.update { it.copy(error = "引擎没有提供权限设置") }
            return
        }
        val op = if (value == null) SettingsOp.unset(listOf("defaultPreset")) else SettingsOp.set(listOf("defaultPreset"), value)
        viewModelScope.launch { applyWrite(permission.ns, listOf(op), listOf(listOf("defaultPreset"))) }
    }

    fun saveProvider(
        route: String,
        displayName: String,
        api: String,
        baseUrl: String,
        models: List<ProviderModelSpec>,
        apiKey: String,
        existing: ProviderProfile?,
        onDone: (String?) -> Unit = {},
    ) {
        val normalized = route.trim()
        if (!providerRoutePattern.matches(normalized)) {
            onDone("渠道 ID 只能用小写字母、数字和单个连字符，且以字母开头")
            return
        }
        if (baseUrl.isBlank()) {
            onDone("请填写服务地址")
            return
        }
        if (models.isEmpty()) {
            onDone("至少填写一个模型")
            return
        }
        val keyRef = existing?.apiKeyEnv ?: deriveProviderKeyRef(normalized)
        val profile = JSONObject(existing?.toJson()?.toString() ?: "{}").apply {
            if (displayName.isBlank()) remove("displayName") else put("displayName", displayName.trim())
            put("api", api)
            put("baseURL", baseUrl.trim())
            put("apiKeyEnv", keyRef)
            // 模型规格整份写入：上下文长度、输入类型、思考档位都在这条里。
            put("models", JSONArray().apply { models.forEach { put(it.toJson()) } })
        }
        viewModelScope.launch {
            val error = applyWrite(
                PROVIDER_NAMESPACE,
                listOf(SettingsOp.set(listOf("providers", normalized), profile)),
                listOf(listOf("providers", normalized)),
            )
            if (error == null && apiKey.isNotBlank()) {
                runCatching {
                    repository?.setCredential(keyRef, apiKey.trim())
                    refreshCredentialsNow()
                }.onFailure { onDone(describeSettingsError(it)); return@launch }
            }
            onDone(error)
        }
    }

    /**
     * 只改一个模型：写整份 `models` 列表（对齐 dsh-Go 的 `saveModel`）。
     * 引擎按 `providers.<route>.models` 存模型规格，没有单独的覆盖字段。
     */
    fun saveProviderModel(route: String, model: ProviderModelSpec, onDone: (String?) -> Unit = {}) {
        if (repository == null) {
            onDone("本机引擎未运行")
            return
        }
        viewModelScope.launch {
            val current = providers().profiles.firstOrNull { it.route == route }
            if (current == null) {
                onDone("渠道已不存在")
                return@launch
            }
            val merged = current.models.map { if (it.id == model.id) model else it }
            val error = applyWrite(
                PROVIDER_NAMESPACE,
                listOf(
                    SettingsOp.set(
                        listOf("providers", route, "models"),
                        JSONArray().apply { merged.forEach { put(it.toJson()) } },
                    ),
                ),
                listOf(listOf("providers", route, "models")),
            )
            onDone(error)
        }
    }

    fun discoverModels(
        baseUrl: String,
        api: String,
        apiKey: String,
        onDone: (List<String>?, String?) -> Unit,
    ) {
        val repo = repository
        if (repo == null) {
            onDone(null, "本机引擎未运行")
            return
        }
        viewModelScope.launch {
            runCatching { repo.discoverModels(baseUrl.trim(), api, apiKey.trim()) }
                .onSuccess { models ->
                    if (models.isEmpty()) onDone(null, "端点没有返回模型") else onDone(models, null)
                }
                .onFailure { onDone(null, describeSettingsError(it)) }
        }
    }

    fun deleteProvider(route: String, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            val error = applyWrite(
                PROVIDER_NAMESPACE,
                listOf(SettingsOp.unset(listOf("providers", route))),
                listOf(listOf("providers", route)),
            )
            onDone(error)
        }
    }

    fun saveCredential(ref: String, value: String, onDone: (String?) -> Unit = {}) {
        if (value.isBlank()) {
            onDone("API Key 不能为空")
            return
        }
        // 安全调用会让「引擎未运行」静默变成保存成功，必须先显式判空。
        val repo = repository ?: run {
            onDone("本机引擎未运行")
            return
        }
        viewModelScope.launch {
            val error = runCatching {
                repo.setCredential(ref, value.trim())
                refreshCredentialsNow()
            }.exceptionOrNull()?.let(::describeSettingsError)
            onDone(error)
        }
    }

    fun clearCredential(ref: String, onDone: (String?) -> Unit = {}) {
        val repo = repository ?: run {
            onDone("本机引擎未运行")
            return
        }
        viewModelScope.launch {
            val error = runCatching {
                repo.unsetCredential(ref)
                refreshCredentialsNow()
            }.exceptionOrNull()?.let(::describeSettingsError)
            onDone(error)
        }
    }

    private suspend fun refreshCredentialsNow() {
        val repo = repository ?: return
        val description = _ui.value.description ?: return
        val refs = credentialRefs(description)
        if (refs.isEmpty()) return
        runCatching { repo.describeCredentials(refs) }
            .onSuccess { credentials -> _ui.update { it.copy(credentials = credentials) } }
    }

    fun secretRef(ns: String, path: List<String>): String? =
        _ui.value.description?.namespace(ns)?.credentialRefOf(path)

    fun secretConfigured(ns: String, path: List<String>): Boolean {
        val ref = secretRef(ns, path)
        if (ref != null) return _ui.value.credentials[ref]?.configured == true
        return _ui.value.description?.namespace(ns)?.secretSet(path) == true
    }

    fun secretWritable(ns: String, path: List<String>): Boolean {
        val ref = secretRef(ns, path)
        if (ref != null) return _ui.value.credentials[ref]?.writable != false
        return _ui.value.description?.namespace(ns)?.writable == true
    }

    /** secret：有 credential-ref 兄弟字段时写凭据库，否则写 settings 文档。 */
    fun saveSecret(ns: String, path: List<String>, value: String, onDone: (String?) -> Unit = {}) {
        val ref = secretRef(ns, path)
        if (ref != null) {
            saveCredential(ref, value) { error ->
                setFieldError(ns, path, error)
                onDone(error)
            }
        } else {
            viewModelScope.launch {
                val error = applyWrite(ns, listOf(SettingsOp.set(path, value)), listOf(path))
                setFieldError(ns, path, error)
                onDone(error)
            }
        }
    }

    fun clearSecret(ns: String, path: List<String>, onDone: (String?) -> Unit = {}) {
        val ref = secretRef(ns, path)
        if (ref != null) {
            clearCredential(ref) { error ->
                setFieldError(ns, path, error)
                onDone(error)
            }
        } else {
            viewModelScope.launch {
                val error = applyWrite(ns, listOf(SettingsOp.unset(path)), listOf(path))
                setFieldError(ns, path, error)
                onDone(error)
            }
        }
    }

    fun setFieldError(ns: String, path: List<String>, message: String?) {
        val key = "$ns:${pathKey(path)}"
        _ui.update { state ->
            state.copy(
                fieldErrors = if (message == null) state.fieldErrors - key else state.fieldErrors + (key to message),
            )
        }
    }

    private suspend fun applyWrite(
        ns: String,
        ops: List<SettingsOp>,
        fields: List<List<String>>,
    ): String? {
        val repo = repository ?: return "本机引擎未运行"
        return writeMutex.withLock {
            val current = _ui.value.description?.namespace(ns) ?: return@withLock "引擎没有提供 $ns 设置"
            if (!current.writable) return@withLock "该设置由配置文件固定，无法在 App 内修改"
            val keys = fields.map { pathKey(it) }
            val uiKeys = keys.map { "$ns:$it" }
            val seen = fields.associateWith { current.valueOf(it) }
            _ui.update {
                it.copy(
                    saving = it.saving + uiKeys,
                    fieldErrors = it.fieldErrors - uiKeys.toSet(),
                    message = null,
                )
            }

            var error: String? = null
            var updated: SettingsNamespace? = null
            try {
                updated = repo.mutate(ns, ops, current.revision, current.writable)
            } catch (failure: Throwable) {
                if (failure is DshRpcException && failure.code == "settings/conflict") {
                    val freshDescription = runCatching { repo.describe() }.getOrNull()
                    val fresh = freshDescription?.namespace(ns)
                    if (freshDescription != null) {
                        _ui.update { it.copy(description = freshDescription) }
                    }
                    if (fresh != null && fields.all { jsonEquals(fresh.valueOf(it), seen[it]) }) {
                        updated = runCatching {
                            repo.mutate(ns, ops, fresh.revision, fresh.writable)
                        }.onFailure { retryError ->
                            error = describeSettingsError(retryError)
                        }.getOrNull()
                    } else {
                        error = "设置已在其他页面被修改，请重新打开后再试"
                    }
                } else {
                    error = describeSettingsError(failure)
                }
            }

            if (updated != null) {
                val next = if (updated.schemaProvided) updated else updated.copy(schema = current.schema)
                _ui.update { state ->
                    val description = state.description
                    if (description == null) {
                        state
                    } else {
                        val map = LinkedHashMap(description.namespaces)
                        map[next.ns] = next
                        state.copy(description = description.copy(namespaces = map))
                    }
                }
            }
            _ui.update { state ->
                val errors = state.fieldErrors.toMutableMap()
                if (error != null) uiKeys.forEach { errors[it] = error } else uiKeys.forEach { errors.remove(it) }
                state.copy(
                    saving = state.saving - uiKeys.toSet(),
                    fieldErrors = errors,
                )
            }
            error
        }
    }

    // ------------------------------------------------------------------
    // 派生值
    // ------------------------------------------------------------------

    fun namespace(ns: String): SettingsNamespace? = _ui.value.description?.namespace(ns)

    fun providers(): ProviderSettings =
        ProviderSettings.fromNamespace(_ui.value.description?.namespace(PROVIDER_NAMESPACE))

    fun permissionDefault(): String? {
        val stored = _ui.value.description?.namespace("permission")?.valueOf(listOf("defaultPreset")) as? String
        return stored ?: _ui.value.permission?.defaultPreset
    }

    fun permissionRevisionWritable(): Boolean =
        _ui.value.description?.namespace("permission")?.writable == true

    /** 引擎 Hub 里固定展示的三个内置 namespace。 */
    fun shellNamespace(): String? {
        val description = _ui.value.description ?: return null
        return when {
            description.served("bash-sandbox") -> "bash-sandbox"
            description.served("pwsh-sandbox") -> "pwsh-sandbox"
            else -> null
        }
    }

    /** 插件自带的设置表单：排除专页/内置 namespace 后剩下的可写项。 */
    /**
     * 有「插件设置」页的 namespace。
     *
     * 对齐 dsh-Go：白名单 —— 不是 App 自己策展的、不是引擎内部或别的宿主
     * （网页客户端 dsh-go-bridge / dsh-app）私有状态的、并且被**已启用的
     * 组合包声明**的，才算插件设置。
     */
    /**
     * 卸载一个插件包（`pluginManager/removeBundle`）。
     * 引擎会把它卸掉并恢复原来的文件；有些包标了「只读 / 由配置文件固定」，卸不掉。
     */
    fun removeBundle(name: String, onDone: (String?) -> Unit = {}) {
        val repo = repository ?: run {
            onDone("本机引擎未运行")
            return
        }
        viewModelScope.launch {
            val error = runCatching { repo.removeBundle(name) }
                .exceptionOrNull()
                ?.let { describeSettingsError(it) }
            if (error == null) refresh()
            onDone(error)
        }
    }

    /**
     * 重新安装一个插件包 —— 「更新」就是按它的安装目标重装一遍。
     */
    fun reinstallBundle(bundle: PluginBundle, onDone: (String?) -> Unit = {}) {
        val repo = repository ?: run {
            onDone("本机引擎未运行")
            return
        }
        val spec = bundle.installTargetSpec
        if (spec.isNullOrBlank()) {
            onDone("这个包没有安装来源，无法重新安装")
            return
        }
        viewModelScope.launch {
            val error = runCatching { repo.installBundle(spec, enabled = bundle.enabled) }
                .exceptionOrNull()
                ?.let { describeSettingsError(it) }
            if (error == null) refresh()
            onDone(error)
        }
    }

    fun pluginConfigNamespaces(): List<SettingsNamespace> {
        val description = _ui.value.description ?: return emptyList()
        val declared = PluginConfigScope.declaredRows(
            _ui.value.bundles.filter { it.enabled }.map { bundle -> bundle.rows.map { it.rowId } },
        )
        return description.namespaces.values.filter { ns ->
            PluginConfigScope.isPluginNamespace(ns.ns, declared)
        }
    }

    fun webNamespaces(): List<SettingsNamespace> {
        val description = _ui.value.description ?: return emptyList()
        return listOf("ui-settings", "ui-chat", "ui-conversation")
            .mapNotNull { description.namespace(it) }
    }

    fun isSaving(ns: String, path: List<String>): Boolean =
        _ui.value.saving.contains("$ns:${pathKey(path)}")

    fun fieldError(ns: String, path: List<String>): String? =
        _ui.value.fieldErrors["$ns:${pathKey(path)}"]

    companion object {
        /** 声明 Agent 预设的模块名。 */
        private const val AGENT_PRESET_MODULE = "@deepseek-ai/dsh-agent-preset"

        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer { EngineSettingsViewModel() }
        }
    }
}

fun describeSettingsError(error: Throwable): String = when (error) {
    is DshRpcException -> when (error.code) {
        "settings/conflict" -> "设置已在其他页面被修改，请重试"
        "settings/rejected" -> error.message ?: "设置被拒绝"
        "credential/rejected" -> "该凭据由启动环境提供，无法在 App 内修改"
        else -> error.message ?: "操作失败"
    }
    else -> error.message ?: error.javaClass.simpleName
}
