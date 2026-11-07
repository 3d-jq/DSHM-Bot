package com.dshm.bot.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshm.bot.data.remote.DshRemoteApi
import com.dshm.bot.data.remote.RemoteDshApi
import com.dshm.bot.data.remote.DshClients
import com.dshm.bot.data.runtime.RuntimeEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class BotModelOption(val ref: String, val name: String)

data class BotPermissionOption(val ref: String, val name: String, val hint: String? = null)

data class BotMemoryEntry(val scope: String, val topic: String?, val text: String)

data class BotScheduleTask(
    val id: String,
    val sessionId: String,
    val title: String,
    val prompt: String,
)

data class BotDetailsInfo(
    val id: String,
    val name: String,
    val role: String,
    val color: String?,
    val shape: String?,
    val model: String?,
    val workDir: String?,
    val permission: String? = null,
    val instructions: String = "",
    val hidden: Boolean = false,
)

data class BotDetailsUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val info: BotDetailsInfo? = null,
    val models: List<BotModelOption> = emptyList(),
    val permissions: List<BotPermissionOption> = emptyList(),
    val memory: List<BotMemoryEntry> = emptyList(),
    val schedules: List<BotScheduleTask> = emptyList(),
    val usageTokens: Long = 0L,
    val cacheHit: Float? = null,
    val mainBotIds: Set<String> = emptySet(),
    val memoryEnabled: Boolean = true,
    val error: String? = null,
) {
    val isMain: Boolean get() = info?.id in mainBotIds
}

/** Bot 详情/设置：`/api/bot` 的 `state` 读取，`update-bot` / `delete-bot` 写回。 */
class BotDetailsViewModel(
    private val botId: String,
    private val endpoint: RuntimeEndpoint?,
) : ViewModel() {

    private val client: DshRemoteApi? = endpoint?.let { RemoteDshApi(DshClients.of(it)) }
    private val _ui = MutableStateFlow(BotDetailsUiState(loading = client != null))
    val ui: StateFlow<BotDetailsUiState> = _ui.asStateFlow()

    init {
        if (client == null) {
            _ui.update { it.copy(loading = false, error = "本机引擎未运行") }
        } else {
            load()
        }
    }

    fun load() {
        val connection = client ?: return
        viewModelScope.launch {
            runCatching { connection.bot("state") }
                .onSuccess { state ->
                    val bot = state.optJSONArray("bots")
                        ?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }
                        ?.firstOrNull { it.optString("id") == botId }
                    if (bot == null) {
                        _ui.update { it.copy(loading = false, error = "Bot 不存在") }
                        return@onSuccess
                    }
                    val models = mutableListOf<BotModelOption>()
                    state.optJSONArray("models")?.let { array ->
                        for (index in 0 until array.length()) {
                            val item = array.optJSONObject(index) ?: continue
                            val ref = item.optString("ref")
                            if (ref.isNotEmpty()) {
                                models += BotModelOption(ref, item.optString("name").ifEmpty { ref })
                            }
                        }
                    }
                    val permissions = mutableListOf<BotPermissionOption>()
                    val permissionCatalog = state.optJSONObject("permissions")
                    permissionCatalog?.optJSONArray("options")?.let { array ->
                        for (index in 0 until array.length()) {
                            val item = array.optJSONObject(index) ?: continue
                            val ref = item.optString("value").ifEmpty { item.optString("id") }
                            if (ref.isEmpty()) continue
                            permissions += BotPermissionOption(
                                ref = ref,
                                name = item.optString("name").ifEmpty { ref },
                                hint = item.optString("hint").takeIf { hint -> hint.isNotEmpty() },
                            )
                        }
                    }
                    val avatar = bot.optJSONObject("avatar")
                    val mainIds = buildSet {
                        state.optJSONArray("mainBotIds")?.let { array ->
                            for (index in 0 until array.length()) {
                                array.optString(index).takeIf { it.isNotEmpty() }?.let(::add)
                            }
                        }
                    }
                    _ui.update {
                        it.copy(
                            loading = false,
                            error = null,
                            models = models,
                            permissions = permissions,
                            mainBotIds = mainIds,
                            memoryEnabled = state.optBoolean("memory", true),
                            info = BotDetailsInfo(
                                id = botId,
                                name = bot.optString("name").ifEmpty { "Bot" },
                                role = bot.optString("role"),
                                color = bot.optString("color").takeIf { color -> color.isNotEmpty() },
                                shape = (avatar?.optString("shape"))?.takeIf { shape -> shape.isNotEmpty() },
                                model = bot.optString("model")
                                    .ifEmpty { bot.optString("appliedModel") }
                                    .takeIf { ref -> ref.isNotEmpty() },
                                workDir = bot.optString("workDir").takeIf { dir -> dir.isNotEmpty() },
                                permission = bot.optString("permission").takeIf { preset -> preset.isNotEmpty() }
                                    ?: permissionCatalog?.optString("defaultPreset")
                                        ?.takeIf { preset -> preset.isNotEmpty() },
                                instructions = bot.optString("instructions"),
                                hidden = bot.optBoolean("hidden", false),
                            ),
                        )
                    }
                    viewModelScope.launch { loadExtras(connection) }
                }
                .onFailure { error ->
                    _ui.update { it.copy(loading = false, error = error.message ?: "读取失败") }
                }
        }
    }

    private suspend fun loadExtras(connection: DshRemoteApi) {
        val memory = runCatching {
            connection.bot("memory-view", JSONObject().put("botId", botId))
        }.getOrNull()?.optJSONArray("entries")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val text = item.optString("text")
                    if (text.isNotEmpty()) {
                        add(
                            BotMemoryEntry(
                                scope = item.optString("scope", "own"),
                                topic = item.optString("topic").takeIf { it.isNotEmpty() },
                                text = text,
                            ),
                        )
                    }
                }
            }
        }.orEmpty()

        val schedules = runCatching { connection.bot("schedules") }.getOrNull()
            ?.optJSONArray("tasks")?.let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        if (item.optString("botId") != botId) continue
                        add(
                            BotScheduleTask(
                                id = item.optString("id"),
                                sessionId = item.optString("sessionId"),
                                title = item.optString("title").ifEmpty { "Task" },
                                prompt = item.optString("prompt"),
                            ),
                        )
                    }
                }
            }.orEmpty()

        _ui.update { it.copy(memory = memory, schedules = schedules) }

        val usage = runCatching {
            connection.bot(
                "usage-summary",
                JSONObject()
                    .put("botId", botId)
                    .put("timeZone", java.util.TimeZone.getDefault().id),
            )
        }.getOrNull()?.optJSONObject("day")
        if (usage != null) {
            val input = usage.optLong("input", 0L)
            val cacheRead = usage.optLong("cacheRead", 0L)
            val cacheWrite = usage.optLong("cacheWrite", 0L)
            val output = usage.optLong("output", 0L)
            val tokens = input + cacheRead + cacheWrite + output
            val hit = if (input + cacheRead > 0) {
                cacheRead.toFloat() / (input + cacheRead)
            } else {
                null
            }
            _ui.update { it.copy(usageTokens = tokens, cacheHit = hit) }
        }
    }

    fun update(
        patch: JSONObject,
        optimistic: (BotDetailsInfo) -> BotDetailsInfo = { it },
        onDone: (String?) -> Unit = {},
    ) {
        val connection = client ?: return
        val previous = _ui.value.info
        _ui.update { it.copy(saving = true) }
        _ui.update { state ->
            state.info?.let { info -> state.copy(info = optimistic(info)) } ?: state
        }
        viewModelScope.launch {
            runCatching {
                connection.bot("update-bot", JSONObject().put("id", botId).also { target ->
                    patch.keys().forEach { key -> target.put(key, patch.get(key)) }
                })
            }.onSuccess {
                _ui.update { it.copy(saving = false) }
                load()
                onDone(null)
            }.onFailure {
                _ui.update { state ->
                    state.copy(saving = false, info = previous, error = it.message)
                }
                onDone(it.message ?: "保存失败")
            }
        }
    }

    fun setPermission(preset: String, onDone: (String?) -> Unit = {}) {
        update(
            patch = JSONObject().put("permission", preset),
            optimistic = { it.copy(permission = preset) },
            onDone = onDone,
        )
    }

    fun setHidden(hidden: Boolean, onDone: (String?) -> Unit = {}) {
        val connection = client ?: return
        _ui.update { it.copy(saving = true) }
        viewModelScope.launch {
            runCatching {
                connection.bot(
                    "set-flags",
                    JSONObject().put("id", botId).put("hidden", hidden),
                )
            }.onSuccess {
                _ui.update { state ->
                    state.copy(
                        saving = false,
                        info = state.info?.copy(hidden = hidden),
                    )
                }
                onDone(null)
            }.onFailure {
                _ui.update { state -> state.copy(saving = false) }
                onDone(it.message ?: "隐私设置失败")
            }
        }
    }

    fun setMain(onDone: (String?) -> Unit = {}) {
        val connection = client ?: return
        _ui.update { it.copy(saving = true) }
        viewModelScope.launch {
            runCatching {
                connection.bot(
                    "set-main",
                    JSONObject().put("id", botId).put("main", true).put("primary", true),
                )
            }.onSuccess {
                _ui.update { it.copy(saving = false) }
                load()
                onDone(null)
            }.onFailure {
                _ui.update { state -> state.copy(saving = false) }
                onDone(it.message ?: "设置主 Bot 失败")
            }
        }
    }

    fun duplicate(onDone: (String?) -> Unit = {}) {
        val connection = client ?: return
        _ui.update { it.copy(saving = true) }
        viewModelScope.launch {
            runCatching { connection.bot("duplicate-bot", JSONObject().put("id", botId)) }
                .onSuccess {
                    _ui.update { it.copy(saving = false) }
                    onDone(null)
                }
                .onFailure {
                    _ui.update { state -> state.copy(saving = false) }
                    onDone(it.message ?: "复制失败")
                }
        }
    }

    fun createDailySchedule(title: String, prompt: String, time: String, onDone: (String?) -> Unit = {}) {
        val connection = client ?: return
        _ui.update { it.copy(saving = true) }
        viewModelScope.launch {
            val whenSpec = JSONObject()
                .put("kind", "daily")
                .put("time", time)
                .put("timeZone", java.util.TimeZone.getDefault().id)
            runCatching {
                connection.bot(
                    "schedule-create",
                    JSONObject()
                        .put("target", botId)
                        .put("title", title)
                        .put("prompt", prompt)
                        .put("when", whenSpec),
                )
            }.onSuccess {
                _ui.update { it.copy(saving = false) }
                loadExtras(connection)
                onDone(null)
            }.onFailure {
                _ui.update { state -> state.copy(saving = false) }
                onDone(it.message ?: "创建定时任务失败")
            }
        }
    }

    fun deleteSchedule(task: BotScheduleTask, onDone: (String?) -> Unit = {}) {
        val connection = client ?: return
        viewModelScope.launch {
            runCatching {
                connection.bot(
                    "schedule-delete",
                    JSONObject().put("sessionId", task.sessionId).put("id", task.id),
                )
            }.onSuccess {
                loadExtras(connection)
                onDone(null)
            }.onFailure {
                onDone(it.message ?: "删除定时任务失败")
            }
        }
    }

    fun forgetMemory(entry: BotMemoryEntry, onDone: (String?) -> Unit = {}) {
        val connection = client ?: return
        viewModelScope.launch {
            runCatching {
                connection.bot(
                    "memory-forget",
                    JSONObject()
                        .put("botId", botId)
                        .put("scope", entry.scope)
                        .put("topic", entry.topic)
                        .put("text", entry.text),
                )
            }.onSuccess {
                loadExtras(connection)
                onDone(null)
            }.onFailure {
                onDone(it.message ?: "删除记忆失败")
            }
        }
    }

    fun delete(onDone: (String?) -> Unit) {
        val connection = client ?: return
        _ui.update { it.copy(saving = true) }
        viewModelScope.launch {
            runCatching { connection.bot("delete-bot", JSONObject().put("id", botId)) }
                .onSuccess {
                    _ui.update { it.copy(saving = false) }
                    onDone(null)
                }
                .onFailure {
                    _ui.update { state -> state.copy(saving = false, error = it.message) }
                    onDone(it.message ?: "删除失败")
                }
        }
    }

    companion object {
        fun factory(botId: String, endpoint: RuntimeEndpoint?): ViewModelProvider.Factory = viewModelFactory {
            initializer { BotDetailsViewModel(botId, endpoint) }
        }
    }
}
