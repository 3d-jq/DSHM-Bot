package com.dshm.bot.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshm.bot.data.remote.CredentialStatus
import com.dshm.bot.data.remote.DshClient
import com.dshm.bot.data.remote.DshEngineSettingsRepository
import com.dshm.bot.data.remote.DshRpcException
import com.dshm.bot.data.remote.PROVIDER_NAMESPACE
import com.dshm.bot.data.remote.PermissionCatalog
import com.dshm.bot.data.remote.PluginBundle
import com.dshm.bot.data.remote.PluginInventory
import com.dshm.bot.data.remote.ProviderProfile
import com.dshm.bot.data.remote.ProviderSettings
import com.dshm.bot.data.remote.SettingsDescription
import com.dshm.bot.data.remote.SettingsNamespace
import com.dshm.bot.data.remote.SettingsOp
import com.dshm.bot.data.remote.deriveProviderKeyRef
import com.dshm.bot.data.remote.jsonEquals
import com.dshm.bot.data.remote.pathKey
import com.dshm.bot.data.remote.providerRoutePattern
import com.dshm.bot.data.runtime.RuntimeEndpoint
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
    )

    private var repository: DshEngineSettingsRepository? = null
    private var endpoint: RuntimeEndpoint? = null
    private val writeMutex = Mutex()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun setEndpoint(next: RuntimeEndpoint?) {
        if (endpoint == next && repository != null) return
        endpoint = next
        repository = next?.let { DshEngineSettingsRepository(DshClient(it)) }
        if (repository == null) {
            _ui.value = UiState(error = "本机引擎未运行")
        } else {
            refresh()
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
            val error = if (repo == null) {
                "本机引擎未运行"
            } else {
                runCatching { repo.setPluginBundleEnabled(name, enabled) }
                    .getOrElse { describeSettingsError(it) }
            }
            if (error == null) {
                _ui.update { state ->
                    state.copy(
                        bundles = state.bundles.map {
                            if (it.name == name) it.copy(enabled = enabled) else it
                        },
                    )
                }
            }
            onDone(error)
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
        modelIds: List<String>,
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
        if (modelIds.isEmpty()) {
            onDone("至少填写一个模型")
            return
        }
        val keyRef = existing?.apiKeyEnv ?: deriveProviderKeyRef(normalized)
        val profile = JSONObject(existing?.toJson()?.toString() ?: "{}").apply {
            if (displayName.isBlank()) remove("displayName") else put("displayName", displayName.trim())
            put("api", api)
            put("baseURL", baseUrl.trim())
            put("apiKeyEnv", keyRef)
            put(
                "models",
                JSONArray().apply {
                    modelIds.forEach { id ->
                        val prior = existing?.models?.firstOrNull { it.id == id }
                        put(prior?.toJson() ?: JSONObject().put("id", id))
                    }
                },
            )
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
        viewModelScope.launch {
            val error = runCatching {
                repository?.setCredential(ref, value.trim())
                refreshCredentialsNow()
            }.exceptionOrNull()?.let(::describeSettingsError)
            onDone(error)
        }
    }

    fun clearCredential(ref: String, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            val error = runCatching {
                repository?.unsetCredential(ref)
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
    fun pluginConfigNamespaces(): List<SettingsNamespace> {
        val description = _ui.value.description ?: return emptyList()
        val dedicated = setOf(
            "subagent",
            "subagent-model-selection-settings",
            "agent-loop",
            "bash-sandbox",
            "pwsh-sandbox",
            "web",
            "web-search-deepseek",
            "web-deepseek-api",
            PROVIDER_NAMESPACE,
            "permission",
            "session-log-deepseek",
            "ui-settings",
            "ui-chat",
            "ui-conversation",
            "locale",
        )
        return description.namespaces.values.filter { it.ns !in dedicated }
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
