package com.dshm.bot.ui.engine

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshm.bot.data.runtime.RuntimeInstaller
import com.dshm.bot.data.runtime.RuntimeLauncher
import com.dshm.bot.data.runtime.RuntimeManager
import com.dshm.bot.data.runtime.RuntimeManifest
import com.dshm.bot.data.runtime.RuntimeState
import com.dshm.bot.data.runtime.TextMasking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 本机引擎页状态层：安装/状态交给 RuntimeManager，启动/停止交给 RuntimeLauncher。 */
class EngineViewModel(
    private val appContext: Context,
    private val manager: RuntimeManager,
    private val launcher: RuntimeLauncher,
    private val installer: RuntimeInstaller,
    private val manifest: RuntimeManifest,
) : ViewModel() {

    private val _state = MutableStateFlow(manager.state.value)
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    val version: String = manifest.dshVersion.ifEmpty { manifest.runtimeVersion }
    val abi: String = manifest.abi.ifEmpty { "arm64-v8a" }
    val nodeVersion: String = manifest.nodeVersion

    init {
        viewModelScope.launch { manager.state.collect { _state.value = it } }
    }

    fun install(url: String) {
        viewModelScope.launch { manager.installFromUrl(url) }
    }

    fun start() {
        if (launcher.isRunning) return
        _state.value = RuntimeState.Starting
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { launcher.start(manifest) } }
                .onSuccess { _state.value = RuntimeState.Running(it) }
                .onFailure { _state.value = RuntimeState.Failed(it.message ?: "启动失败") }
        }
    }

    fun restart() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { launcher.stop() }
            _state.value = RuntimeState.Starting
            runCatching { withContext(Dispatchers.IO) { launcher.start(manifest) } }
                .onSuccess { _state.value = RuntimeState.Running(it) }
                .onFailure { _state.value = RuntimeState.Failed(it.message ?: "启动失败") }
        }
    }

    fun stop() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { launcher.stop() }
            _state.value = RuntimeState.Stopped
        }
    }

    /** 重置运行环境：停止引擎并删除已解压的 rootfs（下次启动需重新下载安装）。 */
    fun reset() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                launcher.stop()
                installer.delete()
            }
            _state.value = RuntimeState.NotInstalled
        }
    }

    /** 启动日志（尾部最多 20000 字符），供 Terminal 卡片展示/复制。 */
    fun logText(): String = runCatching {
        val engineLog = File(appContext.filesDir, "engine.log")
            .takeIf { it.isFile }?.readText().orEmpty()
        // DSH 自己的启动日志比 stdout 丰富，取最新一份一起展示。
        val dshLogs = File(appContext.filesDir, "runtime/rootfs/root/.dsh/logs")
            .listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() }
            ?.readText()?.takeLast(12_000).orEmpty()
        TextMasking.maskSecrets((engineLog + "\n" + dshLogs).trim().takeLast(20_000))
    }.getOrDefault("")

    override fun onCleared() {
        launcher.stop()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = context.applicationContext
                EngineViewModel(app, RuntimeManager(app), RuntimeLauncher(app), RuntimeInstaller(app), RuntimeManifest.load(app))
            }
        }
    }
}





