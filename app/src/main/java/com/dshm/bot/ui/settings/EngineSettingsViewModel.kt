package com.dshm.bot.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshm.bot.data.remote.DshClient
import com.dshm.bot.data.remote.DshEngineSettingsRepository
import com.dshm.bot.data.runtime.RuntimeEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 引擎设置页状态层（外壳层共享，引擎运行时可读）。 */
class EngineSettingsViewModel : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val namespaces: List<DshEngineSettingsRepository.Namespace> = emptyList(),
        val plugins: List<DshEngineSettingsRepository.Plugin> = emptyList(),
        val error: String? = null,
    )

    private var repository: DshEngineSettingsRepository? = null
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun setEndpoint(endpoint: RuntimeEndpoint?) {
        repository = endpoint?.let { DshEngineSettingsRepository(DshClient(it)) }
        if (repository == null) {
            _ui.value = UiState(error = "本机引擎未运行")
        } else {
            refresh()
        }
    }

    fun refresh() {
        val repo = repository ?: return
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching {
                val namespaces = repo.describe()
                val plugins = runCatching { repo.plugins() }.getOrDefault(emptyList())
                namespaces to plugins
            }
                .onSuccess { (namespaces, plugins) ->
                    _ui.value = UiState(namespaces = namespaces, plugins = plugins)
                }
                .onFailure { _ui.value = _ui.value.copy(loading = false, error = it.message) }
        }
    }

    fun toggleBoolean(namespace: DshEngineSettingsRepository.Namespace, path: String, value: Boolean) {
        val repo = repository ?: return
        viewModelScope.launch {
            runCatching { repo.setBoolean(namespace, path, value) }
                .onSuccess { refresh() }
                .onFailure { _ui.value = _ui.value.copy(error = it.message) }
        }
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer { EngineSettingsViewModel() }
        }
    }
}

