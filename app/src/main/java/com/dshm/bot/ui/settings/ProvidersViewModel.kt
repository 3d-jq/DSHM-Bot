package com.dshm.bot.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshm.bot.data.remote.DshClient
import com.dshm.bot.data.remote.DshProvidersRepository
import com.dshm.bot.data.runtime.RuntimeEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 模型渠道页状态层。 */
class ProvidersViewModel : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val providers: List<DshProvidersRepository.Provider> = emptyList(),
        val error: String? = null,
    )

    private var repository: DshProvidersRepository? = null
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun setEndpoint(endpoint: RuntimeEndpoint?) {
        repository = endpoint?.let { DshProvidersRepository(DshClient(it)) }
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
            runCatching { repo.providers() }
                .onSuccess { _ui.value = UiState(providers = it) }
                .onFailure { _ui.value = _ui.value.copy(loading = false, error = it.message) }
        }
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProvidersViewModel() }
        }
    }
}
