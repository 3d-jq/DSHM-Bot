package com.dshm.bot.ui.engine

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshm.bot.data.runtime.DsBotPluginInstaller
import com.dshm.bot.data.runtime.EngineLog
import com.dshm.bot.data.runtime.RuntimeInstaller
import com.dshm.bot.data.runtime.RuntimeLauncher
import com.dshm.bot.data.runtime.RuntimeManager
import com.dshm.bot.data.runtime.RuntimeManifest
import com.dshm.bot.data.runtime.RuntimeEndpoint
import com.dshm.bot.data.runtime.RuntimeState
import com.dshm.bot.data.runtime.TextMasking
import com.dshm.bot.data.runtime.buildDiagnosticsReport
import com.dshm.bot.data.runtime.phaseName
import com.dshm.bot.data.remote.DshClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 重置运行环境时保留什么。 */
enum class ResetKeep {
    /** 保留对话、工作区与个性化配置（用户写下的东西）。 */
    KEEP_WORKSPACE,

    /** 全部删除：运行时回到未安装状态。 */
    DELETE_ALL,
}

/**
 * 让 DS Bot 认为「这个宿主有浏览器窗口」。
 *
 * 插件只在 DSH Desktop 或 `DS_BOT_APP=1` 时才注册 `read_browser`、打开 `browser-wait`
 * 通道（`src/host/browser.js` 的 `install`）。手机端那个窗口就是我们的 WebView，
 * 由 `BrowserReadBridge` 应答读页请求。
 */
/**
 * 引擎进程的环境：
 * - `DS_BOT_APP=1`：让 DS Bot 认为宿主有浏览器窗口（见上）；
 * - `NODE_COMPILE_CACHE`：Node 22.8+ 的 V8 编译缓存。DSH 启动要加载大量 JS，
 *   缓存落在 rootfs 里（跟着运行时一起持久化），第二次以后的冷启动能省一截解析/编译时间。
 */
private val BROWSER_GUEST_ENV = listOf(
    "DS_BOT_APP=1",
    "NODE_COMPILE_CACHE=/root/.cache/node-compile-cache",
)

private fun phoneGuestEnv(enabled: Boolean, ownerBotId: String?): List<String> =
    if (enabled && !ownerBotId.isNullOrBlank()) {
        BROWSER_GUEST_ENV + listOf("DSH_PHONE_CONTROL=1", "DSH_PHONE_OWNER=$ownerBotId")
    } else {
        BROWSER_GUEST_ENV
    }

/** 本机引擎页状态层：安装/状态交给 RuntimeManager，启动/停止交给 RuntimeLauncher。 */
class EngineViewModel(
    private val appContext: Context,
    private val manager: RuntimeManager,
    private val launcher: RuntimeLauncher,
    private val installer: RuntimeInstaller,
    private val manifest: RuntimeManifest,
) : ViewModel() {

    private fun phoneControlEnabled(): Boolean =
        appContext.getSharedPreferences("dshm_settings", Context.MODE_PRIVATE)
            .getBoolean("phone_control_enabled", false)

    private fun phoneControlOwner(): String? =
        appContext.getSharedPreferences("dshm_settings", Context.MODE_PRIVATE)
            .getString("phone_control_bot_id", null)

    private val _state = MutableStateFlow(restoredState ?: manager.state.value)
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    /** 本机引擎是长驻进程：App 热启动、Activity 重建、ViewModel 回收都不该把它判回「未启动」。 */
    private val endpointPrefs = appContext.getSharedPreferences("runtime-launch", Context.MODE_PRIVATE)

    val version: String = manifest.dshVersion.ifEmpty { manifest.runtimeVersion }
    val abi: String = manifest.abi.ifEmpty { "arm64-v8a" }
    val nodeVersion: String = manifest.nodeVersion

    /** 内置运行时版本（App 里带的清单）。 */
    val bundledVersion: String = manifest.runtimeVersion

    /** 已安装的运行时版本；没装过是 null。 */
    val installedVersion: String? get() = installer.installedVersion()

    init {
        restoredState = _state.value
        restoreRunningEngine()
        viewModelScope.launch {
            manager.state.collect { state ->
                // StateFlow 会给新订阅者重放当前值（Installed / NotInstalled）。它可能
                // 晚于 start() 到达，把刚写进去的 Starting 盖回去 —— 首页就会误以为
                // 「引擎没在启动」而显示空列表。启动中 / 运行中不让 manager 覆盖。
                if (_state.value !is RuntimeState.Starting && _state.value !is RuntimeState.Running) {
                    _state.value = state
                    restoredState = state
                }
            }
        }
    }

    /**
     * 从私有偏好恢复上一次的本地端点。只有端口真的还在监听且 token 还能登录时，
     * 才直接把状态设成 Running —— 这样热启动没有「引擎启动中」骨架，也不会重启 proot。
     */
    private fun restoreRunningEngine() {
        if (_state.value is RuntimeState.Running) return
        val url = endpointPrefs.getString("endpoint-url", null) ?: return
        val token = endpointPrefs.getString("endpoint-token", null) ?: return
        val endpoint = RuntimeEndpoint(url, token)
        if (runCatching { launcher.adoptAlive(manifest) }.getOrNull() != endpoint) return
        _state.value = RuntimeState.Running(endpoint)
        restoredState = _state.value
        EngineLog.log("startup: restored running engine at ${endpoint.baseUrl}")
    }

    fun install(url: String) {
        viewModelScope.launch { manager.installFromUrl(url) }
    }

    fun start() {
        if (launcher.isRunning) return
        if (_state.value is RuntimeState.Running) return
        viewModelScope.launch {
            val adopted = withContext(Dispatchers.IO) {
                runCatching { launcher.adoptAlive(manifest) }.getOrNull()
            }
            if (adopted != null && withContext(Dispatchers.IO) {
                    runCatching { DshClients.of(adopted).login() }.isSuccess
                }) {
                _state.value = RuntimeState.Running(adopted)
                restoredState = _state.value
                EngineLog.log("startup: adopted running engine at ${adopted.baseUrl}")
                return@launch
            }
            _state.value = RuntimeState.Starting
            restoredState = _state.value
            runCatching {
                withContext(Dispatchers.IO) {
                    runCatching { DsBotPluginInstaller.install(appContext, installer.rootfsDir) }
                        .onFailure { EngineLog.log("ds-bot install failed: ${it.message}") }
                    launcher.start(manifest, phoneGuestEnv(phoneControlEnabled(), phoneControlOwner()))
                }
            }
                .onSuccess {
                    _state.value = RuntimeState.Running(it)
                    restoredState = _state.value
                }
                .onFailure {
                    _state.value = RuntimeState.Failed(it.message ?: "启动失败")
                    restoredState = _state.value
                }
        }
    }

    fun restart() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { launcher.stop() }
            _state.value = RuntimeState.Starting
            restoredState = _state.value
            runCatching {
                withContext(Dispatchers.IO) {
                    runCatching { DsBotPluginInstaller.install(appContext, installer.rootfsDir) }
                        .onFailure { EngineLog.log("ds-bot install failed: ${it.message}") }
                    launcher.start(manifest, phoneGuestEnv(phoneControlEnabled(), phoneControlOwner()))
                }
            }
                .onSuccess {
                    _state.value = RuntimeState.Running(it)
                    restoredState = _state.value
                }
                .onFailure {
                    _state.value = RuntimeState.Failed(it.message ?: "启动失败")
                    restoredState = _state.value
                }
        }
    }

    fun stop() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { launcher.stop() }
            _state.value = RuntimeState.Stopped
            restoredState = _state.value
        }
    }

    /** 重置运行环境：停止引擎并删除已解压的 rootfs（下次启动需重新下载安装）。 */
    fun reset(keep: ResetKeep = ResetKeep.DELETE_ALL) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                launcher.stop()
                // 要保留的东西先挪到 rootfs 之外，删完运行时再装回来。
                if (keep == ResetKeep.KEEP_WORKSPACE) {
                    installer.stash(RuntimeInstaller.KEEP_PATHS)
                }
                installer.delete()
            }
            _state.value = RuntimeState.NotInstalled
            restoredState = _state.value
        }
    }

    /**
     * 诊断报告：运行时状态 + 连接信息，密钥一律打码。
     * 对齐 dsh-Go 的 `DiagnosticsPage.report`。
     */
    fun diagnosticsReport(
        state: RuntimeState,
        hasApiKey: Boolean,
        connection: String,
    ): String = buildDiagnosticsReport(
        phase = state.phaseName(),
        abi = abi,
        bundledVersion = bundledVersion,
        installedVersion = installedVersion,
        dshVersion = version,
        nodeVersion = nodeVersion,
        baseUrl = (state as? RuntimeState.Running)?.endpoint?.baseUrl,
        error = (state as? RuntimeState.Failed)?.message,
        logLines = logText().count { it == '\n' } + 1,
        hasApiKey = hasApiKey,
        connection = connection,
    )

    /** 运行日志：与 dsh-Go 一样只展示当前引擎进程的 stdout 流，避免多份文件拼接后顺序混乱。 */
    fun logText(): String = runCatching {
        val engineLog = File(appContext.filesDir, "engine.log")
            .takeIf { it.isFile }?.readText().orEmpty()
        TextMasking.maskSecrets(engineLog.trim().takeLast(300_000))
    }.getOrDefault("")


    companion object {
        /** 进程级保留，Activity / ViewModel 重建时不把已运行的引擎判回 Installed。 */
        @Volatile
        private var restoredState: RuntimeState? = null

        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = context.applicationContext
                EngineViewModel(app, RuntimeManager(app), RuntimeLauncher(app), RuntimeInstaller(app), RuntimeManifest.load(app))
            }
        }
    }
}





