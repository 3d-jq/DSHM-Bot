package com.dshm.bot.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshm.bot.data.remote.DshRemoteApi
import com.dshm.bot.data.remote.RemoteDshApi
import com.dshm.bot.data.remote.DshClients
import com.dshm.bot.data.remote.textOrNull
import com.dshm.bot.data.runtime.RuntimeEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class BotModelOption(val ref: String, val name: String)

data class BotPermissionOption(val ref: String, val name: String, val hint: String? = null)

data class BotMemoryEntry(
    val scope: String,
    val topic: String?,
    val text: String,
    val added: String? = null,
    val by: String? = null,
    val source: String? = null,
)

/** DS Bot `run-stats`：DSH 状态栏那一行的原始数字。 */
data class BotRunStats(
    val turns: Int = 0,
    val tokensPerSecond: Double? = null,
    val cacheHit: Float? = null,
    val contextPercent: Int? = null,
    val contextUsed: Long = 0L,
    val contextWindow: Long = 0L,
) {
    val empty: Boolean
        get() = turns <= 0 && tokensPerSecond == null && cacheHit == null && contextPercent == null
}

/** 一个可选的工作目录（`folders` 接口给的已知目录）。 */
data class BotFolder(val path: String, val title: String)

/** `folder-list` 的一层目录。 */
data class BotFolderListing(
    val path: String,
    val home: String?,
    val crumbs: List<Pair<String, String>>,
    val entries: List<BotFolder>,
    val truncated: Boolean,
)

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
    /** 当前选中的推理强度 id；null = 用模型默认。 */
    val effort: String? = null,
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
    /** 模型 ref → 可选推理强度（含「默认」由界面补）。 */
    val efforts: Map<String, List<BotEffortOption>> = emptyMap(),
    val permissions: List<BotPermissionOption> = emptyList(),
    val memory: List<BotMemoryEntry> = emptyList(),
    val schedules: List<BotScheduleTask> = emptyList(),
    val usageTokens: Long = 0L,
    val cacheHit: Float? = null,
    val runStats: BotRunStats? = null,
    /** 记忆正文按 scope 的字符数（`memory-view.sizes`）。 */
    val memorySizes: Map<String, String> = emptyMap(),
    val memorySummary: String? = null,
    /** `memory-view.summary.at`：摘要生成时间（epoch ms）。 */
    val memorySummaryAt: Long? = null,
    /** `memory-view.error`：摘要写失败时的原因（对齐 DS Bot 的「摘要读不出来了」）。 */
    val memorySummaryError: String? = null,
    /** `memory-view.limits.main`：MEMORY.md 的字符上限。 */
    val memoryLimit: Int? = null,
    val memorySummarizing: Boolean = false,
    /** 摘要是否跟当前条目对得上；false 时是旧摘要。 */
    val memoryFresh: Boolean = true,
    /** `folders` 接口已知的目录 + 这个 DSH 提供哪种选择方式。 */
    val folders: List<BotFolder> = emptyList(),
    val teamFolder: String? = null,
    val folderPicker: String? = null,
    val mainBotIds: Set<String> = emptySet(),
    val memoryEnabled: Boolean = true,
    val error: String? = null,
) {
    val isMain: Boolean get() = info?.id in mainBotIds
}

/** `efforts {ref}` 的一档：`{id, name, description?}`。 */
data class BotEffortOption(val id: String, val name: String, val description: String? = null)

/** Bot 详情/设置：`/api/bot` 的 `state` 读取，`update-bot` / `delete-bot` 写回。 */
class BotDetailsViewModel(
    private val botId: String,
    private val endpoint: RuntimeEndpoint?,
) : ViewModel() {

    private val client: DshRemoteApi? = endpoint?.let { RemoteDshApi(DshClients.of(it)) }
    private val _ui = MutableStateFlow(BotDetailsUiState(loading = client != null))
    val ui: StateFlow<BotDetailsUiState> = _ui.asStateFlow()
    private var memoryJob: Job? = null

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
                    val efforts = mutableMapOf<String, List<BotEffortOption>>()
                    state.optJSONArray("models")?.let { array ->
                        for (index in 0 until array.length()) {
                            val item = array.optJSONObject(index) ?: continue
                            val ref = item.optString("ref")
                            if (ref.isNotEmpty()) {
                                models += BotModelOption(ref, item.optString("name").ifEmpty { ref })
                                // 网页版 `useEfforts` 优先用 state.models[].reasoning，
                                // 只有缺的时候才另发 `efforts {ref}`。
                                val reasoning = item.optJSONObject("reasoning")
                                val options = reasoning?.optJSONArray("efforts")?.let { list ->
                                    buildList {
                                        for (entry in 0 until list.length()) {
                                            val option = list.optJSONObject(entry) ?: continue
                                            val id = option.optString("id")
                                            if (id.isNotEmpty()) {
                                                add(
                                                    BotEffortOption(
                                                        id = id,
                                                        name = option.optString("name").ifEmpty { id },
                                                        description = option.optString("description")
                                                            .takeIf { text -> text.isNotEmpty() },
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                }.orEmpty()
                                if (options.isNotEmpty()) efforts[ref] = options
                            }
                        }
                    }
                    val effort = bot.optString("effort").takeIf { value -> value.isNotEmpty() }
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
                            efforts = efforts,
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
                                effort = effort,
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

    /**
     * 按 `efforts {ref}` 补某个模型的推理强度档位。
     *
     * state 只给「已被某个 Bot 用着」的模型带 `reasoning`；用户刚切到一个新模型时
     * 需要现问引擎（网页版 `useEfforts` 同款懒加载）。结果缓存进 [BotDetailsUiState.efforts]，
     * 同一 ref 不重复请求。
     */
    fun ensureEfforts(ref: String) {
        val connection = client ?: return
        if (ref.isEmpty() || _ui.value.efforts.containsKey(ref)) return
        viewModelScope.launch {
            val value = runCatching {
                connection.bot("efforts", JSONObject().put("ref", ref))
            }.getOrNull() ?: return@launch
            val options = value.optJSONArray("efforts")?.let { list ->
                buildList {
                    for (index in 0 until list.length()) {
                        val item = list.optJSONObject(index) ?: continue
                        val id = item.optString("id")
                        if (id.isNotEmpty()) {
                            add(
                                BotEffortOption(
                                    id = id,
                                    name = item.optString("name").ifEmpty { id },
                                    description = item.optString("description")
                                        .takeIf { text -> text.isNotEmpty() },
                                ),
                            )
                        }
                    }
                }
            }.orEmpty()
            _ui.update { state ->
                state.copy(efforts = state.efforts + (ref to options))
            }
        }
    }

    private suspend fun loadExtras(connection: DshRemoteApi) {
        runCatching {
            connection.bot("memory-view", JSONObject().put("botId", botId))
        }.getOrNull()?.let { applyMemoryView(it) }

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

        _ui.update { it.copy(schedules = schedules) }

        // DS Bot 的 `run-stats`：DSH 状态栏那一行的原始数字（轮数 / 速度 / 缓存 / 上下文）。
        val stats = runCatching {
            connection.bot("run-stats", JSONObject().put("botId", botId))
        }.getOrNull()
        _ui.update { it.copy(runStats = parseRunStats(stats)) }

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

    /** 把一份 `memory-view` 回执写进状态（`memory-view` / `memory-add` / `memory-edit` 同一个形状）。 */
    private fun applyMemoryView(view: JSONObject) {
        val memory = view.optJSONArray("entries")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val text = item.optString("text")
                    if (text.isNotEmpty()) {
                        add(
                            BotMemoryEntry(
                                scope = item.optString("scope", "own"),
                                topic = item.textOrNull("topic"),
                                text = text,
                                added = item.textOrNull("added"),
                                by = item.textOrNull("by"),
                                source = item.textOrNull("source"),
                            ),
                        )
                    }
                }
            }
        }.orEmpty()
        val sizes = view.optJSONObject("sizes")?.let { json ->
            buildMap {
                json.keys().forEach { key -> put(key, json.opt(key)?.toString().orEmpty()) }
            }
        }.orEmpty()
        val summary = view.optJSONObject("summary")?.optString("text")?.takeIf { it.isNotEmpty() }
        val summaryAt = view.optJSONObject("summary")?.optLong("at", 0L)?.takeIf { it > 0L }
        val limit = view.optJSONObject("limits")?.optInt("main", 0)?.takeIf { it > 0 }
        _ui.update {
            it.copy(
                memory = memory,
                memorySizes = sizes,
                memorySummary = summary,
                memorySummaryAt = summaryAt,
                memorySummaryError = view.textOrNull("error"),
                memoryLimit = limit,
                memorySummarizing = view.optBoolean("summarizing", false),
                memoryFresh = view.optBoolean("fresh", true),
            )
        }
    }

    /**
     * 打开记忆面板时拉一次，并让 DSH 去写摘要（`memory-view {summarize:true}`）。
     *
     * DS Bot 在 `summarizing` 期间每 1.5s 轮询一次，摘要落地就停；这里照做，
     * 最多轮询 90 次（约两分半）兜底，避免面板关掉以后一直在打接口。
     */
    fun refreshMemory(regenerate: Boolean = false) {
        val connection = client ?: return
        memoryJob?.cancel()
        memoryJob = viewModelScope.launch {
            repeat(90) { attempt ->
                val body = JSONObject().put("botId", botId)
                if (attempt == 0) {
                    if (regenerate) body.put("regenerate", true) else body.put("summarize", true)
                } else {
                    body.put("summarize", true)
                }
                val view = runCatching { connection.bot("memory-view", body) }.getOrNull()
                    ?: return@launch
                applyMemoryView(view)
                if (!view.optBoolean("summarizing", false)) return@launch
                delay(1_500)
            }
        }
    }

    fun stopMemoryPolling() {
        memoryJob?.cancel()
        memoryJob = null
    }

    private fun parseRunStats(values: JSONObject?): BotRunStats {
        if (values == null) return BotRunStats()
        val stats = values.optJSONObject("sessionStats")
        val usage = values.optJSONObject("tokenUsage")
        val pressure = values.optJSONObject("contextPressure")
        val turns = stats?.optInt("turns", 0) ?: 0
        val decodeMs = stats?.optDouble("decodeMs", 0.0) ?: 0.0
        val decodeTokens = stats?.optDouble("decodeTokens", 0.0) ?: 0.0
        val speed = if (decodeMs > 0 && decodeTokens > 0) decodeTokens / (decodeMs / 1000.0) else null
        val cacheRead = usage?.optLong("cacheReadTokens", 0L) ?: 0L
        val prompt = cacheRead +
            (usage?.optLong("uncachedInputTokens", 0L) ?: 0L) +
            (usage?.optLong("cacheWriteTokens", 0L) ?: 0L)
        val hit = if (prompt > 0) cacheRead.toFloat() / prompt else null
        val used = pressure?.let { entry ->
            if (entry.has("projectedTokens")) entry.optLong("projectedTokens") else entry.optLong("pressureTokens", 0L)
        } ?: 0L
        val window = pressure?.optLong("contextWindow", 0L) ?: 0L
        val percent = if (window > 0) {
            ((used.toDouble() / window) * 100).toInt().coerceIn(0, 100)
        } else {
            null
        }
        return BotRunStats(
            turns = turns,
            tokensPerSecond = speed,
            cacheHit = hit,
            contextPercent = percent,
            contextUsed = used,
            contextWindow = window,
        )
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

    /**
     * 对着记忆说话 / 直接更新（`memory-ask`，DS Bot 面板底部那个胶囊输入）。
     * `result.reply` 给界面显示，`result.view` 直接回填整张面板。
     */
    fun memoryAsk(text: String, onReply: (String?) -> Unit = {}) {
        val connection = client ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            onReply(null)
            return
        }
        viewModelScope.launch {
            runCatching {
                connection.bot("memory-ask", JSONObject().put("botId", botId).put("text", trimmed))
            }.onSuccess { view ->
                applyMemoryView(view)
                val reply = view.optString("reply").takeIf { it.isNotEmpty() }
                onReply(reply)
                if (view.optBoolean("summarizing", false)) refreshMemory(false)
            }.onFailure { failure ->
                onReply(failure.message ?: "记忆更新失败")
            }
        }
    }

    /** 加一条记忆：`memory-add {botId, scope, topic?, text}`。 */
    fun addMemory(scope: String, topic: String?, text: String, onDone: (String?) -> Unit = {}) {
        val connection = client ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            onDone("先写一条要记住的内容")
            return
        }
        viewModelScope.launch {
            runCatching {
                val body = JSONObject()
                    .put("botId", botId)
                    .put("scope", scope)
                    .put("text", trimmed)
                topic?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("topic", it) }
                connection.bot("memory-add", body)
            }.onSuccess { view ->
                applyMemoryView(view)
                onDone(null)
            }.onFailure {
                onDone(it.message ?: "保存记忆失败")
            }
        }
    }

    /**
     * 改一条记忆：`memory-edit {botId, scope, topic, text, next}`。
     *
     * `text` 是要替换的那条原文，`next` 是新内容（DS Bot `replaceEntry` 按原文匹配）。
     */
    fun editMemory(
        entry: BotMemoryEntry,
        next: String,
        topic: String? = entry.topic,
        onDone: (String?) -> Unit = {},
    ) {
        val connection = client ?: return
        val trimmed = next.trim()
        if (trimmed.isEmpty()) {
            onDone("内容不能为空")
            return
        }
        viewModelScope.launch {
            runCatching {
                connection.bot(
                    "memory-edit",
                    JSONObject()
                        .put("botId", botId)
                        .put("scope", entry.scope)
                        .put("topic", topic.orEmpty())
                        .put("text", entry.text)
                        .put("next", trimmed),
                )
            }.onSuccess { view ->
                applyMemoryView(view)
                onDone(null)
            }.onFailure {
                onDone(it.message ?: "修改记忆失败")
            }
        }
    }

    /** 读已知目录 + 这个 DSH 提供哪种目录选择方式（`folders`）。 */
    fun loadFolders(onDone: (String?) -> Unit = {}) {
        val connection = client ?: return
        viewModelScope.launch {
            runCatching { connection.bot("folders") }
                .onSuccess { view ->
                    val folders = view.optJSONArray("folders")?.let { array ->
                        buildList {
                            for (index in 0 until array.length()) {
                                val item = array.optJSONObject(index) ?: continue
                                val path = item.optString("path")
                                if (path.isNotEmpty()) {
                                    add(BotFolder(path, item.optString("title").ifEmpty { path }))
                                }
                            }
                        }
                    }.orEmpty()
                    _ui.update {
                        it.copy(
                            folders = folders,
                            teamFolder = view.optString("team").takeIf { team -> team.isNotEmpty() },
                            folderPicker = view.optString("picker").takeIf { kind -> kind.isNotEmpty() },
                        )
                    }
                    onDone(null)
                }
                .onFailure { onDone(it.message ?: "读取目录失败") }
        }
    }

    /** 列一层目录（`folder-list`，只有 `picker == "browse"` 的 DSH 支持）。 */
    fun listFolders(path: String?, onDone: (BotFolderListing?) -> Unit) {
        val connection = client ?: return
        viewModelScope.launch {
            runCatching {
                connection.bot("folder-list", JSONObject().put("path", path.orEmpty()))
            }.onSuccess { view ->
                val crumbs = view.optJSONArray("crumbs")?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            val item = array.optJSONObject(index) ?: continue
                            add(item.optString("name") to item.optString("path"))
                        }
                    }
                }.orEmpty()
                val entries = view.optJSONArray("entries")?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            val item = array.optJSONObject(index) ?: continue
                            if (item.optBoolean("hidden", false)) continue
                            val entryPath = item.optString("path")
                            if (entryPath.isNotEmpty()) {
                                add(BotFolder(entryPath, item.optString("name").ifEmpty { entryPath }))
                            }
                        }
                    }
                }.orEmpty()
                onDone(
                    BotFolderListing(
                        path = view.optString("path"),
                        home = view.optString("home").takeIf { it.isNotEmpty() },
                        crumbs = crumbs,
                        entries = entries,
                        truncated = view.optBoolean("truncated", false),
                    ),
                )
            }.onFailure {
                onDone(null)
            }
        }
    }

    /** 系统目录选择器（`folder-pick`，只有 `picker == "native"` 的 DSH 支持）。 */
    fun pickFolder(onDone: (String?) -> Unit) {
        val connection = client ?: return
        viewModelScope.launch {
            val path = runCatching { connection.botValue("folder-pick") }.getOrNull() as? String
            onDone(path?.takeIf { it.isNotEmpty() })
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
