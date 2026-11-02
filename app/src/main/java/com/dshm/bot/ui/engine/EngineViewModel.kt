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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 本机引擎页状态层：安装交给 RuntimeManager，启动/停止交给 RuntimeLauncher。 */
class EngineViewModel(
    private val manager: RuntimeManager,
    private val launcher: RuntimeLauncher,
    private val manifest: RuntimeManifest,
) : ViewModel() {

    private val _state = MutableStateFlow(manager.state.value)
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

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

    fun stop() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { launcher.stop() }
            _state.value = RuntimeState.Stopped
        }
    }

    override fun onCleared() {
        launcher.stop()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = context.applicationContext
                EngineViewModel(RuntimeManager(app), RuntimeLauncher(app), RuntimeManifest.load(app))
            }
        }
    }
}
