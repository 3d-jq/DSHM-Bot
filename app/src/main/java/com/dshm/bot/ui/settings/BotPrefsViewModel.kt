package com.dshm.bot.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshm.bot.data.remote.DshClients
import com.dshm.bot.data.remote.RemoteDshApi
import com.dshm.bot.data.runtime.RuntimeEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * DS Bot 的「个性化」(`state.prefs` / `set-prefs`)。
 *
 * 这些值属于团队、由引擎持久化，网页版和其它设备共用；和本 App 自己的
 * 主题 / 字号设置是两套，不互相覆盖。
 */
data class BotPrefs(
    val theme: String = "deepseek",
    val accent: String? = null,
    val motion: String = "normal",
    val layout: String = "bubble",
    val width: Int = 80,
    val flatWidth: Int = 100,
    val thinking: Boolean = false,
)

class BotPrefsViewModel(private val endpoint: RuntimeEndpoint?) : ViewModel() {

    private val api = endpoint?.let { RemoteDshApi(DshClients.of(it)) }
    private val _ui = MutableStateFlow(PrefsUiState(loading = api != null))
    val ui: StateFlow<PrefsUiState> = _ui.asStateFlow()

    data class PrefsUiState(
        val loading: Boolean = false,
        val prefs: BotPrefs = BotPrefs(),
        val saving: Boolean = false,
        val error: String? = null,
    )

    init {
        if (api == null) {
            _ui.value = PrefsUiState(error = "本机引擎未运行")
        } else {
            load()
        }
    }

    fun load() {
        val connection = api ?: return
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching { connection.bot("state") }
                .onSuccess { state ->
                    _ui.update {
                        it.copy(loading = false, error = null, prefs = parsePrefs(state.optJSONObject("prefs")))
                    }
                }
                .onFailure { failure ->
                    _ui.update { it.copy(loading = false, error = failure.message ?: "读取失败") }
                }
        }
    }

    /** 写一项；成功后再拉一次 state，避免本地乐观值跟网页端漂移。 */
    fun set(patch: JSONObject) {
        val connection = api ?: return
        viewModelScope.launch {
            _ui.update { it.copy(saving = true, error = null) }
            val result = runCatching { connection.bot("set-prefs", patch) }
            if (result.isSuccess) {
                _ui.update { state ->
                    state.copy(
                        saving = false,
                        prefs = parsePrefs(result.getOrNull()?.optJSONObject("prefs") ?: result.getOrNull()),
                    )
                }
            } else {
                _ui.update {
                    it.copy(saving = false, error = result.exceptionOrNull()?.message ?: "保存失败")
                }
            }
        }
    }

    companion object {
        fun factory(endpoint: RuntimeEndpoint?): ViewModelProvider.Factory = viewModelFactory {
            initializer { BotPrefsViewModel(endpoint) }
        }

        /** `state.prefs` 与 `set-prefs` 的回执都是同一个对象。 */
        fun parsePrefs(raw: JSONObject?): BotPrefs {
            if (raw == null) return BotPrefs()
            val motion = raw.optString("motion").takeIf { it == "quiet" || it == "lively" } ?: "normal"
            val layout = raw.optString("layout").takeIf { it == "flat" } ?: "bubble"
            return BotPrefs(
                theme = raw.optString("theme").takeIf { it.isNotEmpty() } ?: "deepseek",
                accent = raw.optString("accent").takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) },
                motion = motion,
                layout = layout,
                width = raw.optInt("width", 80).coerceIn(50, 100),
                flatWidth = raw.optInt("flatWidth", 100).coerceIn(50, 100),
                thinking = raw.optBoolean("thinking", false),
            )
        }
    }
}
