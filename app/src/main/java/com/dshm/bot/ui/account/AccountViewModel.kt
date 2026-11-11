package com.dshm.bot.ui.account

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshm.bot.data.remote.DshAccountRepository
import com.dshm.bot.data.remote.DshClient
import com.dshm.bot.data.remote.DshClients
import com.dshm.bot.data.runtime.RuntimeEndpoint
import com.dshm.bot.data.settings.ApiKeyStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 登录进行中的用户可见阶段，对齐 dsh-Go `SignInStage`。 */
enum class AccountStage { PREPARING, WAITING_BROWSER, FINISHING }

/**
 * 账号状态层（设置概览卡 + 账号页共用），对齐 dsh-Go `app/lib/state/account_state.dart`：
 * startSignIn → 跟随 attempt 阶段 → 打开浏览器 → 成功后读取资料/余额并初始化默认模型；
 * 支持取消登录、退出前检查运行中任务、App 回前台刷新。
 *
 * dsh-Go 用 `account/watch` 流驱动阶段，本项目尚未接入 mux 流，这里用有上限的
 * `getState` 轮询代替；阶段、终态、错误码仍按 wire 的 attempt.phase 判定。
 */
class AccountViewModel(
    private val apiKeyStore: ApiKeyStore,
    initialEndpoint: RuntimeEndpoint? = null,
) : ViewModel() {

    data class UiState(
        val ready: Boolean = false,
        val signedIn: Boolean = false,
        val name: String? = null,
        val avatarUrl: String? = null,
        /** 充值余额（概览卡副标题与余额卡左列共用）。 */
        val balance: String? = null,
        /** 赠送余额，对齐 dsh-Go 的 bonusWallets。 */
        val bonusBalance: String? = null,
        val usageUrl: String? = null,
        val topUpUrl: String? = null,
        val authorizeUrl: String? = null,
        val hasApiKey: Boolean = false,
        val maskedApiKey: String = "",
        val busy: Boolean = false,
        /** 顶栏刷新按钮：正在重读资料与余额。 */
        val refreshing: Boolean = false,
        val stage: AccountStage? = null,
        val attemptId: String? = null,
        val pendingSignOut: Boolean = false,
        val signOutBusy: Boolean = false,
        /** wire 的 errorCode，由界面映射成本地化文案，ViewModel 不写死中文。 */
        val errorCode: String? = null,
        val error: String? = null,
    )

    private var repository: DshAccountRepository? = null

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        _ui.value = _ui.value.copy(
            hasApiKey = apiKeyStore.key() != null,
            maskedApiKey = apiKeyStore.masked(),
        )
        setEndpoint(initialEndpoint)
    }

    fun saveApiKey(value: String) {
        apiKeyStore.save(value)
        _ui.value = _ui.value.copy(hasApiKey = true, maskedApiKey = apiKeyStore.masked())
    }

    fun clearApiKey() {
        apiKeyStore.clear()
        _ui.value = _ui.value.copy(hasApiKey = false, maskedApiKey = "")
    }

    /** 引擎运行/停止时由外壳调用；null 表示引擎不可用。 */
    fun setEndpoint(endpoint: RuntimeEndpoint?) {
        repository = endpoint?.let { DshAccountRepository(DshClients.of(it), it.baseUrl.trimEnd('/')) }
        if (repository == null) {
            _ui.value = UiState(
                hasApiKey = apiKeyStore.key() != null,
                maskedApiKey = apiKeyStore.masked(),
            )
        } else {
            refresh()
        }
    }

    fun consumeAuthorizeUrl() {
        _ui.value = _ui.value.copy(authorizeUrl = null)
    }

    fun refresh() {
        val repo = repository ?: return
        _ui.value = _ui.value.copy(refreshing = true)
        viewModelScope.launch {
            runCatching {
                val state = repo.state()
                val profile = if (state.signedIn) repo.profile() else null
                val balances = if (state.signedIn) repo.balances() else null
                _ui.value = UiState(
                    ready = true,
                    refreshing = false,
                    signedIn = state.signedIn,
                    name = profile?.name,
                    avatarUrl = profile?.avatarUrl,
                    balance = balances?.recharge,
                    bonusBalance = balances?.bonus,
                    usageUrl = state.usageUrl,
                    topUpUrl = state.topUpUrl,
                    hasApiKey = apiKeyStore.key() != null,
                    maskedApiKey = apiKeyStore.masked(),
                )
            }.onFailure { _ui.value = _ui.value.copy(ready = true, refreshing = false, error = it.message) }
        }
    }

    /** 对齐 dsh-Go noteAppResumed：浏览器/充值页返回后重读账号与余额。 */
    fun noteAppResumed() {
        if (_ui.value.signedIn && !_ui.value.busy) refresh()
    }

    fun signIn() {
        val repo = repository ?: run {
            _ui.value = _ui.value.copy(errorCode = ERROR_ENGINE)
            return
        }
        _ui.value = _ui.value.copy(
            busy = true,
            stage = AccountStage.PREPARING,
            error = null,
            errorCode = null,
        )
        viewModelScope.launch {
            runCatching {
                var snapshot = repo.startSignIn()
                var browserOpened = false
                repeat(SIGN_IN_POLLS) {
                    publishAttempt(snapshot)
                    if (snapshot.authorizeUrl != null && !browserOpened) {
                        _ui.value = _ui.value.copy(authorizeUrl = snapshot.authorizeUrl)
                        browserOpened = true
                    }
                    if (snapshot.signedIn) {
                        completeSignIn(repo)
                        return@launch
                    }
                    if (snapshot.phase?.terminal == true) {
                        _ui.value = _ui.value.copy(
                            busy = false,
                            stage = null,
                            attemptId = null,
                            errorCode = snapshot.errorCode ?: ERROR_PROTOCOL,
                        )
                        return@launch
                    }
                    delay(SIGN_IN_POLL_MS)
                    snapshot = repo.state()
                }
                _ui.value = _ui.value.copy(
                    busy = false,
                    stage = null,
                    attemptId = null,
                    errorCode = ERROR_TIMEOUT,
                )
            }.onFailure {
                _ui.value = _ui.value.copy(
                    busy = false,
                    stage = null,
                    attemptId = null,
                    error = it.message,
                )
            }
        }
    }

    /** 对齐 dsh-Go account/cancelSignIn：登录进行中可主动取消。 */
    fun cancelSignIn() {
        val repo = repository ?: return
        val attemptId = _ui.value.attemptId
        viewModelScope.launch {
            if (attemptId != null) runCatching { repo.cancelSignIn(attemptId) }
            _ui.value = _ui.value.copy(busy = false, stage = null, attemptId = null)
        }
    }

    /** 对齐 dsh-Go hasRunningAccountTasks：退出前先看有没有任务在用账号。 */
    fun requestSignOut() {
        val repo = repository ?: return
        viewModelScope.launch {
            val busy = runCatching { repo.hasRunningAccountTasks() }.getOrDefault(false)
            _ui.value = _ui.value.copy(pendingSignOut = true, signOutBusy = busy)
        }
    }

    fun cancelSignOut() {
        _ui.value = _ui.value.copy(pendingSignOut = false, signOutBusy = false)
    }

    fun confirmSignOut() {
        val repo = repository ?: return
        viewModelScope.launch {
            runCatching {
                repo.signOut()
                _ui.value = UiState(
                    ready = true,
                    hasApiKey = apiKeyStore.key() != null,
                    maskedApiKey = apiKeyStore.masked(),
                )
            }.onFailure {
                _ui.value = _ui.value.copy(pendingSignOut = false, error = it.message)
            }
        }
    }

    private fun publishAttempt(snapshot: DshAccountRepository.Snapshot) {
        val stage = when (snapshot.phase) {
            DshAccountRepository.Phase.WAITING_BROWSER -> AccountStage.WAITING_BROWSER
            DshAccountRepository.Phase.EXCHANGING,
            DshAccountRepository.Phase.COMMITTING,
            DshAccountRepository.Phase.SUCCEEDED,
            -> AccountStage.FINISHING
            else -> AccountStage.PREPARING
        }
        _ui.value = _ui.value.copy(stage = stage, attemptId = snapshot.attemptId)
    }

    private suspend fun completeSignIn(repo: DshAccountRepository) {
        repo.initializeDefaultModel()
        // 退出登录会清掉 links（充值 / 用量地址），重新登录必须再拉一次 state，
        // 否则「充值 / 用量」按钮因为地址为空会一直是灰的。
        val state = repo.state()
        val profile = repo.profile()
        val balances = repo.balances()
        _ui.value = _ui.value.copy(
            signedIn = true,
            name = profile?.name,
            avatarUrl = profile?.avatarUrl,
            balance = balances.recharge,
            bonusBalance = balances.bonus,
            usageUrl = state.usageUrl,
            topUpUrl = state.topUpUrl,
            busy = false,
            stage = null,
            attemptId = null,
            error = null,
            errorCode = null,
        )
    }

    companion object {
        private const val SIGN_IN_POLLS = 150
        private const val SIGN_IN_POLL_MS = 2_000L
        const val ERROR_ENGINE = "engine"
        const val ERROR_PROTOCOL = "protocol"
        const val ERROR_TIMEOUT = "timeout"

        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { AccountViewModel(ApiKeyStore(context.applicationContext)) }
        }
    }
}
