package com.dshm.bot.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshm.bot.data.remote.DshClients
import com.dshm.bot.data.remote.MarketIndex
import com.dshm.bot.data.remote.MarketIndexException
import com.dshm.bot.data.remote.MarketPlugin
import com.dshm.bot.data.remote.PluginCancelStatus
import com.dshm.bot.data.remote.PluginInspection
import com.dshm.bot.data.remote.PluginMarketEntry
import com.dshm.bot.data.remote.PluginMarketRepository
import com.dshm.bot.data.remote.PluginRegistries
import com.dshm.bot.data.remote.pluginFailureText
import com.dshm.bot.data.runtime.RuntimeEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「插件市场」页的错误。
 *
 * 界面用 [R.string.plugins_market_error_*] 渲染提示，原因本身是引擎/网络给的原始文本，
 * 当数据传，不当文案硬编码。
 */
sealed interface PluginMarketError {
    /** 本机引擎没起来。 */
    data object EngineUnavailable : PluginMarketError

    /** 索引读不出来（不是 JSON / 不是 format 1 / 没有插件列表 / 网络不通）。 */
    data class IndexUnreadable(val reason: String) : PluginMarketError

    /** 装 / 卸失败。 */
    data class ActionFailed(val reason: String) : PluginMarketError
}

/** 等用户批准构建脚本的那次安装。 */
data class PluginMarketPendingApproval(
    val pluginName: String,
    val spec: String,
    val registry: String?,
    /** pnpm 拦下来的包（构建脚本要批准）。 */
    val pendingBuilds: List<String>,
)

/**
 * 索引声明了「构建脚本要批准」的包 —— 装之前得先问用户一句。
 */
data class PluginMarketPendingInstall(
    val pluginName: String,
    val spec: String,
    val registry: String?,
    /** 索引声明已获批准的包（装的时候要一起传给宿主）。 */
    val approvedBuilds: List<String>,
)

/**
 * 装 / 卸之后的一次性提示。
 *
 * 文案在界面上用 `stringResource` 渲染，这里只带数据。
 */
sealed interface PluginMarketNotice {
    /** 装好了。 */
    data class Installed(val pluginName: String) : PluginMarketNotice

    /** 卸掉了。 */
    data class Removed(val pluginName: String) : PluginMarketNotice

    /** 安装被取消了。 */
    data object InstallCancelled : PluginMarketNotice

    /** 安装已经结束，取消不了。 */
    data object InstallTooLate : PluginMarketNotice
}

/**
 * 「插件市场」页的状态层：读索引、装包、卸包。
 *
 * 引擎通道（`pluginManager` 这一族 RPC）在 [PluginMarketRepository] 里；索引是 App 自己
 * 用 HTTP GET 拉的远程 JSON（格式 1）。
 */
class PluginMarketViewModel : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val index: MarketIndex? = null,
        /** 实际读到索引的地址（顶栏显示「索引来源」）。 */
        val indexSource: String? = null,
        val registries: PluginRegistries? = null,
        /** 宿主里已装载的模块名。 */
        val installed: Set<String> = emptySet(),
        /** 正在装 / 卸的包名。 */
        val busy: Set<String> = emptySet(),
        /** 正在装的那次安装的 requestId（用来取消）。 */
        val installRequestId: String? = null,
        val error: PluginMarketError? = null,
        /** 装 / 卸的结果提示（成功/失败原因）。 */
        val notice: PluginMarketNotice? = null,
        /** 等用户批准构建脚本。 */
        val pendingApproval: PluginMarketPendingApproval? = null,
        /** 索引声明了要批准的构建脚本，等用户点头再装。 */
        val pendingInstall: PluginMarketPendingInstall? = null,
    )

    private var repository: PluginMarketRepository? = null

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun setEndpoint(endpoint: RuntimeEndpoint?) {
        repository = endpoint?.let { PluginMarketRepository(DshClients.of(it)) }
        refresh()
    }

    /** 重读：注册表 → 索引 → 已装的插件。 */
    fun refresh() {
        val repo = repository
        if (repo == null) {
            _ui.update { it.copy(loading = false, error = PluginMarketError.EngineUnavailable) }
            return
        }
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val registries = runCatching { repo.registries() }.getOrNull()
            val entries = runCatching { repo.listPlugins() }.getOrDefault(emptyList())
            _ui.update {
                it.copy(
                    registries = registries,
                    installed = installedNamesOf(entries),
                )
            }
            loadIndex(repo)
        }
    }

    private suspend fun loadIndex(repo: PluginMarketRepository) {
        val candidates = repo.marketIndexCandidates()
        if (candidates.isEmpty()) {
            _ui.update {
                it.copy(
                    loading = false,
                    error = PluginMarketError.IndexUnreadable("引擎没有给出任何索引地址"),
                )
            }
            return
        }
        val failures = mutableListOf<String>()
        for (url in candidates) {
            try {
                val index = repo.fetchMarketIndex(url)
                _ui.update {
                    it.copy(
                        loading = false,
                        index = index,
                        indexSource = url,
                        error = null,
                        notice = null,
                    )
                }
                return
            } catch (failure: MarketIndexException) {
                failures += "${url}：${failure.reason}"
            } catch (failure: Exception) {
                failures += "${url}：${failure.message ?: failure.javaClass.simpleName}"
            }
        }
        _ui.update {
            it.copy(loading = false, error = PluginMarketError.IndexUnreadable(failures.joinToString("；")))
        }
    }

    /**
     * 装一个插件。
     *
     * 索引里声明了 `approvedBuilds`（构建脚本要批准）的包，先问用户一句再装。
     * 其余情况直接走：`inspect` 看这个 spec 能不能装（已装 / 找不到会在这里被拒），
     * 再 `installBundle`（很慢，30 分钟超时）。
     */
    fun install(pluginName: String) {
        val repo = repository
        val plugin = _ui.value.index?.plugins?.firstOrNull { it.name == pluginName }
        if (repo == null || plugin == null) {
            _ui.update { it.copy(error = PluginMarketError.EngineUnavailable) }
            return
        }
        if (pluginName in _ui.value.busy) return
        if (plugin.approvedBuilds.isNotEmpty()) {
            _ui.update {
                it.copy(
                    error = null,
                    notice = null,
                    pendingApproval = null,
                    pendingInstall = PluginMarketPendingInstall(
                        pluginName = plugin.name,
                        spec = plugin.installSpec(),
                        registry = plugin.registry,
                        approvedBuilds = plugin.approvedBuilds,
                    ),
                )
            }
            return
        }
        startInstall(repo, plugin, approvedBuilds = null)
    }

    /** 用户点头了：带着批准的构建脚本装。 */
    fun confirmInstall() {
        val repo = repository
        val pending = _ui.value.pendingInstall ?: return
        if (repo == null) return
        _ui.update { it.copy(pendingInstall = null) }
        val plugin = _ui.value.index?.plugins?.firstOrNull { it.name == pending.pluginName }
        if (plugin == null) return
        startInstall(repo, plugin, approvedBuilds = pending.approvedBuilds)
    }

    private fun startInstall(
        repo: PluginMarketRepository,
        plugin: MarketPlugin,
        approvedBuilds: List<String>?,
    ) {
        val spec = plugin.installSpec()
        _ui.update {
            it.copy(
                busy = it.busy + plugin.name,
                error = null,
                notice = null,
                pendingApproval = null,
                pendingInstall = null,
                installRequestId = PluginMarketRepository.newRequestId(),
            )
        }
        viewModelScope.launch {
            val inspected = runCatching { repo.inspect(spec, plugin.registry) }.getOrNull()
            if (inspected is PluginInspection.Refused) {
                _ui.update {
                    it.copy(
                        busy = it.busy - plugin.name,
                        error = PluginMarketError.ActionFailed(refusalText(inspected)),
                        installRequestId = null,
                    )
                }
                return@launch
            }
            val requestId = _ui.value.installRequestId ?: PluginMarketRepository.newRequestId()
            val result = runCatching {
                repo.installBundle(
                    spec = spec,
                    requestId = requestId,
                    enabled = true,
                    saveExact = true,
                    approvedBuilds = approvedBuilds,
                    registry = plugin.registry,
                )
            }
            result.onSuccess { outcome -> finishInstall(plugin, outcome) }
                .onFailure { failure ->
                    _ui.update {
                        it.copy(
                            busy = it.busy - plugin.name,
                            error = PluginMarketError.ActionFailed(failure.message ?: "安装失败"),
                            installRequestId = null,
                        )
                    }
                }
        }
    }

    /** 装完了：处理「构建脚本要批准」和最终状态。 */
    private fun finishInstall(
        plugin: MarketPlugin,
        outcome: com.dshm.bot.data.remote.PluginOperationResult,
    ) {
        if (outcome.pendingBuilds.isNotEmpty()) {
            _ui.update {
                it.copy(
                    busy = it.busy - plugin.name,
                    installRequestId = null,
                    pendingApproval = PluginMarketPendingApproval(
                        pluginName = plugin.name,
                        spec = plugin.installSpec(),
                        registry = plugin.registry,
                        pendingBuilds = outcome.pendingBuilds,
                    ),
                )
            }
            return
        }
        _ui.update {
            it.copy(
                busy = it.busy - plugin.name,
                installRequestId = null,
                error = if (outcome.succeeded) null else PluginMarketError.ActionFailed(pluginFailureText(outcome)),
                notice = if (outcome.succeeded) PluginMarketNotice.Installed(outcome.bundle ?: plugin.name) else null,
            )
        }
        if (outcome.succeeded) refreshInstalled()
    }

    /** 用户批准了构建脚本：带着 `approvedBuilds` 再装一次。 */
    fun approvePendingBuilds() {
        val repo = repository
        val pending = _ui.value.pendingApproval
        if (repo == null || pending == null) return
        _ui.update { it.copy(pendingApproval = null, busy = it.busy + pending.pluginName, notice = null) }
        viewModelScope.launch {
            val requestId = PluginMarketRepository.newRequestId()
            _ui.update { it.copy(installRequestId = requestId) }
            runCatching {
                repo.installBundle(
                    spec = pending.spec,
                    requestId = requestId,
                    enabled = true,
                    saveExact = true,
                    approvedBuilds = pending.pendingBuilds,
                    registry = pending.registry,
                )
            }.onSuccess { outcome ->
                if (outcome.pendingBuilds.isNotEmpty()) {
                    _ui.update {
                        it.copy(
                            busy = it.busy - pending.pluginName,
                            installRequestId = null,
                            pendingApproval = pending.copy(pendingBuilds = outcome.pendingBuilds),
                            error = PluginMarketError.ActionFailed(pluginFailureText(outcome)),
                        )
                    }
                } else {
                    _ui.update {
                        it.copy(
                            busy = it.busy - pending.pluginName,
                            installRequestId = null,
                            error = if (outcome.succeeded) null else PluginMarketError.ActionFailed(pluginFailureText(outcome)),
                            notice = if (outcome.succeeded) PluginMarketNotice.Installed(outcome.bundle ?: pending.pluginName) else null,
                        )
                    }
                    if (outcome.succeeded) refreshInstalled()
                }
            }.onFailure { failure ->
                _ui.update {
                    it.copy(
                        busy = it.busy - pending.pluginName,
                        installRequestId = null,
                        error = PluginMarketError.ActionFailed(failure.message ?: "安装失败"),
                    )
                }
            }
        }
    }

    /** 取消正在跑的安装。 */
    fun cancelInstall() {
        val repo = repository
        val requestId = _ui.value.installRequestId ?: return
        if (repo == null) return
        viewModelScope.launch {
            val status = runCatching { repo.cancelInstall(requestId) }.getOrNull()
            _ui.update {
                it.copy(
                    busy = emptySet(),
                    installRequestId = null,
                    notice = when (status) {
                        PluginCancelStatus.CANCELLED -> PluginMarketNotice.InstallCancelled
                        PluginCancelStatus.TOO_LATE -> PluginMarketNotice.InstallTooLate
                        else -> null
                    },
                )
            }
        }
    }

    /** 卸一个插件。 */
    fun uninstall(pluginName: String) {
        val repo = repository
        if (repo == null) {
            _ui.update { it.copy(error = PluginMarketError.EngineUnavailable) }
            return
        }
        if (pluginName in _ui.value.busy) return
        _ui.update { it.copy(busy = it.busy + pluginName, error = null, notice = null) }
        viewModelScope.launch {
            runCatching { repo.removeBundle(pluginName) }
                .onSuccess { outcome ->
                    _ui.update {
                        it.copy(
                            busy = it.busy - pluginName,
                            error = if (outcome.succeeded) null else PluginMarketError.ActionFailed(pluginFailureText(outcome)),
                            notice = if (outcome.succeeded) PluginMarketNotice.Removed(pluginName) else null,
                        )
                    }
                    if (outcome.succeeded) refreshInstalled()
                }
                .onFailure { failure ->
                    _ui.update {
                        it.copy(
                            busy = it.busy - pluginName,
                            error = PluginMarketError.ActionFailed(failure.message ?: "卸载失败"),
                        )
                    }
                }
        }
    }

    /** 装完 / 卸完刷新一次已装列表。 */
    private fun refreshInstalled() {
        val repo = repository ?: return
        viewModelScope.launch {
            val entries = runCatching { repo.listPlugins() }.getOrDefault(emptyList())
            _ui.update { it.copy(installed = installedNamesOf(entries)) }
        }
    }

    /** 清掉一次性的提示。 */
    fun clearNotice() {
        _ui.update { it.copy(notice = null) }
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer { PluginMarketViewModel() }
        }
    }
}

/**
 * 宿主里已装载的模块名集合。
 *
 * 市场的包名是 npm 包名（`@scope/pkg`），宿主给的 `moduleName` 也可能是
 * `@scope/pkg` 或去掉作用域的 `pkg`，两种都算装上了。
 */
fun installedNamesOf(entries: List<PluginMarketEntry>): Set<String> =
    entries.map { it.moduleName }.filter { it.isNotEmpty() }.toSet()

/**
 * 市场里的这个包是不是已经装上了。
 *
 * 有作用域的包（`@scope/pkg`）必须整个名字对上 —— 不同作用域的同名包不是同一个。
 * 没作用域的包（`pkg`）可以对应宿主报的 `@scope/pkg`。
 */
fun isMarketPluginInstalled(name: String, installed: Set<String>): Boolean {
    if (name in installed) return true
    if (name.startsWith("@")) return false
    return installed.any { it.substringAfterLast('/') == name }
}

/** `inspect` 被拒时的原因文本。 */
fun refusalText(refused: PluginInspection.Refused): String =
    listOfNotNull(
        refused.problem.takeIf { it.isNotBlank() },
        refused.reason.takeIf { it.isNotBlank() },
    ).joinToString("：")
