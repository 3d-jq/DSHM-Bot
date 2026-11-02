package com.dshm.bot.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshm.bot.data.remote.DshAccountRepository
import com.dshm.bot.data.remote.DshClient
import com.dshm.bot.data.runtime.RuntimeEndpoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 账号页状态层：对齐 dsh-Go account_state，登录走 startSignIn + 轮询 getState。 */
class AccountViewModel(private val repository: DshAccountRepository) : ViewModel() {

    data class UiState(
        val signedIn: Boolean = false,
        val name: String? = null,
        val authorizeUrl: String? = null,
        val busy: Boolean = false,
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun consumeAuthorizeUrl() {
        _ui.value = _ui.value.copy(authorizeUrl = null)
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching {
                val state = repository.state()
                val name = if (state.signedIn) repository.displayName() else null
                _ui.value = UiState(signedIn = state.signedIn, name = name)
            }.onFailure { _ui.value = _ui.value.copy(error = it.message) }
        }
    }

    fun signIn() {
        _ui.value = _ui.value.copy(busy = true, error = null)
        viewModelScope.launch {
            runCatching {
                val started = repository.startSignIn()
                var url = started.authorizeUrl
                var waited = 0
                while (url == null && waited < 10) {
                    delay(1_000)
                    url = repository.state().authorizeUrl
                    waited++
                }
                if (url == null) {
                    _ui.value = _ui.value.copy(busy = false, error = "无法获取登录页，请重试")
                    return@launch
                }
                _ui.value = _ui.value.copy(authorizeUrl = url)
                repeat(90) {
                    delay(2_000)
                    val current = repository.state()
                    if (current.signedIn) {
                        _ui.value = UiState(signedIn = true, name = repository.displayName())
                        return@launch
                    }
                }
                _ui.value = _ui.value.copy(busy = false, error = "登录超时")
            }.onFailure {
                _ui.value = _ui.value.copy(busy = false, error = it.message)
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            runCatching {
                repository.signOut()
                _ui.value = UiState()
            }.onFailure { _ui.value = _ui.value.copy(error = it.message) }
        }
    }

    companion object {
        fun factory(endpoint: RuntimeEndpoint): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val callbackOrigin = endpoint.baseUrl.trimEnd('/')
                AccountViewModel(DshAccountRepository(DshClient(endpoint), callbackOrigin))
            }
        }
    }
}

