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

/**
 * 账号状态层（外壳层共享：设置概览卡 + 账号页共用），对齐 dsh-Go account_state：
 * 登录走 startSignIn + 轮询 getState，登录后拉 profile/balance。
 */
class AccountViewModel(initialEndpoint: RuntimeEndpoint? = null) : ViewModel() {

    data class UiState(
        val ready: Boolean = false,
        val signedIn: Boolean = false,
        val name: String? = null,
        val avatarUrl: String? = null,
        val balance: String? = null,
        val usageUrl: String? = null,
        val topUpUrl: String? = null,
        val authorizeUrl: String? = null,
        val busy: Boolean = false,
        val error: String? = null,
    )

    private var repository: DshAccountRepository? = null

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        setEndpoint(initialEndpoint)
    }

    /** 引擎运行/停止时由外壳调用；null 表示引擎不可用。 */
    fun setEndpoint(endpoint: RuntimeEndpoint?) {
        repository = endpoint?.let { DshAccountRepository(DshClient(it), it.baseUrl.trimEnd('/')) }
        if (repository == null) {
            _ui.value = UiState()
        } else {
            refresh()
        }
    }

    fun consumeAuthorizeUrl() {
        _ui.value = _ui.value.copy(authorizeUrl = null)
    }

    fun refresh() {
        val repo = repository ?: return
        viewModelScope.launch {
            runCatching {
                val state = repo.state()
                val profile = if (state.signedIn) repo.profile() else null
                val balance = if (state.signedIn) repo.balanceText() else null
                _ui.value = UiState(
                    ready = true,
                    signedIn = state.signedIn,
                    name = profile?.name,
                    avatarUrl = profile?.avatarUrl,
                    balance = balance,
                    usageUrl = state.usageUrl,
                    topUpUrl = state.topUpUrl,
                )
            }.onFailure { _ui.value = _ui.value.copy(ready = true, error = it.message) }
        }
    }

    fun signIn() {
        val repo = repository ?: run {
            _ui.value = _ui.value.copy(error = "本机引擎未运行")
            return
        }
        _ui.value = _ui.value.copy(busy = true, error = null)
        viewModelScope.launch {
            runCatching {
                val started = repo.startSignIn()
                var url = started.authorizeUrl
                var waited = 0
                while (url == null && waited < 10) {
                    delay(1_000)
                    url = repo.state().authorizeUrl
                    waited++
                }
                if (url == null) {
                    _ui.value = _ui.value.copy(busy = false, error = "无法获取登录页，请重试")
                    return@launch
                }
                _ui.value = _ui.value.copy(authorizeUrl = url)
                repeat(90) {
                    delay(2_000)
                    val current = repo.state()
                    if (current.signedIn) {
                        val profile = repo.profile()
                        _ui.value = _ui.value.copy(
                            signedIn = true,
                            name = profile?.name,
                            avatarUrl = profile?.avatarUrl,
                            balance = repo.balanceText(),
                            usageUrl = current.usageUrl,
                            topUpUrl = current.topUpUrl,
                            busy = false,
                        )
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
        val repo = repository ?: return
        viewModelScope.launch {
            runCatching {
                repo.signOut()
                _ui.value = UiState(ready = true)
            }.onFailure { _ui.value = _ui.value.copy(error = it.message) }
        }
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer { AccountViewModel() }
        }
    }
}
