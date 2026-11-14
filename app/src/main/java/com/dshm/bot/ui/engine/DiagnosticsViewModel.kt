package com.dshm.bot.ui.engine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshm.bot.data.remote.DshClients
import com.dshm.bot.data.remote.DshRemoteApi
import com.dshm.bot.data.remote.RemoteDshApi
import com.dshm.bot.data.runtime.RuntimeEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 日志长文视图的状态。 */
enum class DiagnosticsLogStatus {
    /** 还没打开过。 */
    IDLE,

    /** 正在向宿主取日志。 */
    LOADING,

    /** 拿到了（可能是空文本）。 */
    LOADED,

    /** 没拿到：引擎没跑 / 端点拒绝 / 网络错误。 */
    FAILED,
}

/** 清空诊断的结果，页面据此给一句反馈。 */
enum class DiagnosticsClearStatus {
    IDLE,
    WORKING,
    CLEARED,
    FAILED,
}

/**
 * 诊断页的宿主侧数据。
 *
 * 两个动作都是 `/api/bot` 的既有端点（`channel.js`）：
 * - `diagnostics-log`：宿主诊断日志全文（`rt.diagnostics.logText()`）；
 * - `diagnostics-clear`：清空宿主诊断（`rt.diagnostics.clear()`，返回 true）。
 *
 * [reportRevision] 在每次清空成功后 +1：诊断报告是纯函数拼出来的，页面订阅它
 * 重算，报告不会停在清空前的快照上。清空成功后还会重读日志，打开着的长文视图
 * 立刻反映「已清空」。
 */
class DiagnosticsViewModel(
    /** 测试注入用；为 null 时按 [endpoint] 现建 [RemoteDshApi]。 */
    private val providedApi: (() -> DshRemoteApi?)? = null,
) : ViewModel() {

    private var endpoint: RuntimeEndpoint? = null

    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    /** 引擎启动 / 重启 / 停止时更新端点（与对话告警同一个模式）。 */
    fun setEndpoint(value: RuntimeEndpoint?) {
        if (endpoint?.baseUrl == value?.baseUrl && endpoint?.token == value?.token) return
        endpoint = value
        // 换引擎：上一台的日志、打开的弹窗与反馈都作废。
        _state.update {
            it.copy(
                logOpen = false,
                logStatus = DiagnosticsLogStatus.IDLE,
                logText = "",
                clearStatus = DiagnosticsClearStatus.IDLE,
            )
        }
    }

    private fun api(): DshRemoteApi? =
        providedApi?.invoke() ?: endpoint?.let { RemoteDshApi(DshClients.of(it)) }

    /** 打开日志长文：每次打开都重读，排障时不看旧快照。 */
    fun openLog() {
        _state.update { it.copy(logOpen = true) }
        reloadLog()
    }

    fun closeLog() {
        _state.update { it.copy(logOpen = false) }
    }

    /** 重读日志（打开时、清空成功后、以及错误态的「重试」）。 */
    fun reloadLog() {
        val client = api()
        if (client == null) {
            _state.update { it.copy(logStatus = DiagnosticsLogStatus.FAILED, logText = "") }
            return
        }
        _state.update { it.copy(logStatus = DiagnosticsLogStatus.LOADING) }
        viewModelScope.launch {
            val text = runCatching {
                (client.botValue(ENDPOINT_LOG, JSONObject()) as? String).orEmpty()
            }
            text.fold(
                onSuccess = { value ->
                    _state.update { it.copy(logStatus = DiagnosticsLogStatus.LOADED, logText = value) }
                },
                onFailure = {
                    // 失败要看得见：诊断页本身就是排障入口，静默等于骗人。
                    _state.update { it.copy(logStatus = DiagnosticsLogStatus.FAILED, logText = "") }
                },
            )
        }
    }

    /**
     * 清空宿主诊断。成功后刷新日志（打开着的长文立刻变空）并让报告重算；
     * 失败保留可见错误态，不静默。
     */
    fun clear() {
        if (_state.value.clearStatus == DiagnosticsClearStatus.WORKING) return
        val client = api()
        if (client == null) {
            _state.update { it.copy(clearStatus = DiagnosticsClearStatus.FAILED) }
            return
        }
        _state.update { it.copy(clearStatus = DiagnosticsClearStatus.WORKING) }
        viewModelScope.launch {
            val cleared = runCatching { client.botValue(ENDPOINT_CLEAR, JSONObject()) }.isSuccess
            if (!cleared) {
                _state.update { it.copy(clearStatus = DiagnosticsClearStatus.FAILED) }
                return@launch
            }
            _state.update {
                it.copy(clearStatus = DiagnosticsClearStatus.CLEARED, reportRevision = it.reportRevision + 1)
            }
            // Refresh: the log view (if open) and the report revision.
            reloadLog()
        }
    }

    companion object {
        const val ENDPOINT_LOG = "diagnostics-log"
        const val ENDPOINT_CLEAR = "diagnostics-clear"

        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer { DiagnosticsViewModel() }
        }
    }
}

/** 诊断页一份状态。 */
data class DiagnosticsUiState(
    val logOpen: Boolean = false,
    val logStatus: DiagnosticsLogStatus = DiagnosticsLogStatus.IDLE,
    val logText: String = "",
    val clearStatus: DiagnosticsClearStatus = DiagnosticsClearStatus.IDLE,
    /** 每次清空成功 +1；页面按它重算诊断报告。 */
    val reportRevision: Int = 0,
)
