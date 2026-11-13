package com.dshm.bot.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshm.bot.data.remote.DshClients
import com.dshm.bot.data.remote.RemoteDshApi
import com.dshm.bot.data.runtime.RuntimeEndpoint
import com.dshm.bot.data.settings.BotPrefsStore
import com.dshm.bot.domain.model.AppPalette
import com.dshm.bot.domain.model.BotPrefs
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
 *
 * 写完同步进 [BotPrefsStore]：全 App 按团队偏好渲染（配色 / 强调色 /
 * 气泡布局 / 内容宽度 / 动效 / 思考过程）。
 */

class BotPrefsViewModel(private val endpoint: RuntimeEndpoint?) : ViewModel() {

    private val api = endpoint?.let { RemoteDshApi(DshClients.of(it)) }
    private val _ui = MutableStateFlow(PrefsUiState(loading = api != null))
    val ui: StateFlow<PrefsUiState> = _ui.asStateFlow()

    data class PrefsUiState(
        val loading: Boolean = false,
        val prefs: BotPrefs = BotPrefs(),
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
                    val prefs = parsePrefs(state.optJSONObject("prefs"))
                    BotPrefsStore.set(prefs)
                    _ui.update {
                        it.copy(loading = false, error = null, prefs = prefs)
                    }
                }
                .onFailure { failure ->
                    _ui.update { it.copy(loading = false, error = failure.message ?: "读取失败") }
                }
        }
    }

    /**
     * 写一项：**乐观更新**（点即变），不发 `saving` 整页刷新。
     *
     * 以前把 `saving=true` 写进 uiState，会让整页重组、所有行先禁用再启用，
     * Switch 和文字肉眼可见地闪一下。现在只改 `prefs` 那一个字段，
     * 失败时回滚到写之前的值并给错误；成功时用回执覆写一次（引擎是唯一真值），
     * 也不再额外发一次 `state`。
     *
     * 每次写都同步 [BotPrefsStore]，所以**本机界面立刻跟着变**（配色、
     * 气泡布局、内容宽度、动效、思考过程），不用重开页面。
     */
    fun set(patch: JSONObject) {
        val connection = api ?: return
        val before = _ui.value.prefs
        val optimistic = applyPatch(before, patch)
        _ui.update { state ->
            state.copy(error = null, prefs = optimistic)
        }
        BotPrefsStore.set(optimistic)
        viewModelScope.launch {
            val result = runCatching { connection.bot("set-prefs", patch) }
            result.onSuccess { response ->
                val confirmed = prefsFromResponse(response)?.let { parsePrefs(it) } ?: optimistic
                BotPrefsStore.set(confirmed)
                _ui.update { state ->
                    state.copy(prefs = confirmed, error = null)
                }
            }.onFailure { failure ->
                BotPrefsStore.set(before)
                _ui.update { state ->
                    state.copy(prefs = before, error = failure.message ?: "保存失败")
                }
            }
        }
    }

    companion object {
        fun factory(endpoint: RuntimeEndpoint?): ViewModelProvider.Factory = viewModelFactory {
            initializer { BotPrefsViewModel(endpoint) }
        }

        /**
         * `set-prefs` 的回执是 `ok(state.prefs)`，即 `{"ok":true,"value":{...prefs...}}`；
         * 有的实现可能把 prefs 包在 `prefs` 字段里。两种都认，取不到就返回 null
         * （调用方保留乐观值，不用硬刷新）。
         */
        internal fun prefsFromResponse(response: JSONObject?): JSONObject? {
            if (response == null) return null
            response.optJSONObject("prefs")?.let { return it }
            response.optJSONObject("value")?.let { return it }
            return response.takeIf { it.has("theme") || it.has("thinking") || it.has("motion") }
        }

        /** 把一次 patch 应用到本地 prefs（乐观值），不改其它字段。 */
        internal fun applyPatch(current: BotPrefs, patch: JSONObject): BotPrefs {
            var next = current
            if (patch.has("theme")) {
                // 只认认识的配色 id（网页端也是 `themes[prefs.theme] ?? deepseek`）。
                next = next.copy(theme = AppPalette.fromId(patch.optString("theme")).id)
            }
            if (patch.has("accent")) {
                next = next.copy(
                    accent = if (patch.isNull("accent")) null
                    else patch.optString("accent").takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) } ?: next.accent,
                )
            }
            if (patch.has("motion")) {
                val value = if (patch.isNull("motion")) "normal" else patch.optString("motion")
                next = next.copy(motion = value.takeIf { it == "quiet" || it == "lively" } ?: "normal")
            }
            if (patch.has("layout")) {
                val value = if (patch.isNull("layout")) "bubble" else patch.optString("layout")
                next = next.copy(layout = value.takeIf { it == "flat" } ?: "bubble")
            }
            if (patch.has("width")) {
                next = next.copy(width = patch.optInt("width", 80).coerceIn(50, 100))
            }
            if (patch.has("flatWidth")) {
                next = next.copy(flatWidth = patch.optInt("flatWidth", 100).coerceIn(50, 100))
            }
            if (patch.has("thinking")) {
                next = next.copy(thinking = !patch.isNull("thinking") && patch.optBoolean("thinking", false))
            }
            return next
        }

        /** `state.prefs` 与 `set-prefs` 的回执都是同一个对象。 */
        fun parsePrefs(raw: JSONObject?): BotPrefs = BotPrefsStore.parse(raw)
    }
}
