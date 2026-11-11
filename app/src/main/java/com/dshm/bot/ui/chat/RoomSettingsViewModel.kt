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

class RoomSettingsViewModel(
    private val roomId: String,
    private val endpoint: RuntimeEndpoint?,
) : ViewModel() {

    private val client: DshRemoteApi? = endpoint?.let { RemoteDshApi(DshClients.of(it)) }
    private val _ui = MutableStateFlow(RoomSettingsUi(loading = client != null))
    val ui: StateFlow<RoomSettingsUi> = _ui.asStateFlow()
    private var statusJob: Job? = null
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
                .onSuccess { state -> _ui.value = parseRoomSettings(state, roomId) }
                .onFailure { failure ->
                    _ui.update { it.copy(loading = false, error = failure.message ?: "读取群聊失败") }
                }
            loadMemory(connection)
            loadUsage(connection)
        }
    }

    /**
     * 群详情开着时轻量轮询成员状态（`state.work`）。
     *
     * 只更新 `memberStatus`：记忆 / 用量不跟着重拉（那两条很贵）。
     */
    fun startStatusPolling() {
        val connection = client ?: return
        if (statusJob?.isActive == true) return
        statusJob = viewModelScope.launch {
            while (true) {
                delay(3_000)
                val state = runCatching { connection.bot("state") }.getOrNull() ?: continue
                _ui.update { it.copy(memberStatus = parseMemberStatus(state, roomId)) }
            }
        }
    }

    fun stopStatusPolling() {
        statusJob?.cancel()
        statusJob = null
    }

    /**
     * 工作目录候选 + 这个 DSH 提供哪种选择方式（`folders`）。
     *
     * 与 Bot 详情页同一套接口：群聊和 Bot 用的是同一个 `folder-list` / `folder-pick`。
     */
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

    /** 浏览一个目录（`folder-list`）。 */
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
                            if (entryPath.isEmpty()) continue
                            add(BotFolder(entryPath, item.optString("name").ifEmpty { entryPath }))
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
            }.onFailure { onDone(null) }
        }
    }

    /** 宿主自己的目录选择器（`folder-pick`），本机 DSH 通常没有。 */
    fun pickFolder(onDone: (String?) -> Unit) {
        val connection = client ?: return
        viewModelScope.launch {
            val path = runCatching { connection.botValue("folder-pick") }.getOrNull() as? String
            onDone(path?.takeIf { it.isNotEmpty() })
        }
    }

    private suspend fun loadMemory(connection: DshRemoteApi) {
        val view = runCatching {
            connection.bot("memory-view", JSONObject().put("roomId", roomId))
        }.getOrNull() ?: return
        applyMemoryView(view)
    }

    /** `memory-view` / `memory-add` / `memory-edit` 回执都长这样。 */
    private fun applyMemoryView(view: JSONObject) {
        val memory = view.optJSONArray("entries")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val text = item.optString("text")
                    if (text.isEmpty()) continue
                    add(
                        BotMemoryEntry(
                            scope = item.optString("scope", "group"),
                            topic = item.textOrNull("topic"),
                            text = text,
                            added = item.textOrNull("added"),
                            by = item.textOrNull("by"),
                            source = item.textOrNull("source"),
                        ),
                    )
                }
            }
        }.orEmpty()
        val sizes = view.optJSONObject("sizes")?.let { json ->
            buildMap { json.keys().forEach { key -> put(key, json.opt(key)?.toString().orEmpty()) } }
        }.orEmpty()
        _ui.update {
            it.copy(
                memory = memory,
                memorySizes = sizes,
                memorySummary = view.optJSONObject("summary")?.optString("text")?.takeIf { text -> text.isNotEmpty() },
                memorySummaryAt = view.optJSONObject("summary")?.optLong("at", 0L)?.takeIf { at -> at > 0L },
                memorySummaryError = view.textOrNull("error"),
                memoryLimit = view.optJSONObject("limits")?.optInt("main", 0)?.takeIf { limit -> limit > 0 },
                memorySummarizing = view.optBoolean("summarizing", false),
                memoryFresh = view.optBoolean("fresh", true),
            )
        }
    }

    /**
     * 打开记忆面板时拉一次并让 DSH 写摘要；`summarizing` 期间照 DS Bot 每 1.5s 轮询，
     * 最多 90 次兜底（面板关掉后不会一直打接口）。
     */
    fun refreshMemory(regenerate: Boolean = false) {
        val connection = client ?: return
        memoryJob?.cancel()
        memoryJob = viewModelScope.launch {
            repeat(90) { attempt ->
                val body = JSONObject().put("roomId", roomId)
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

    private suspend fun loadUsage(connection: DshRemoteApi) {
        val usage = runCatching {
            connection.bot("usage", JSONObject().put("timeZone", java.util.TimeZone.getDefault().id))
        }.getOrNull() ?: return
        val owners = usage.optJSONArray("owners") ?: return
        val rows = usage.optJSONArray("rows") ?: return
        var ownerIndex = -1
        for (index in 0 until owners.length()) {
            val owner = owners.optJSONObject(index) ?: continue
            if (owner.optString("kind") == "room" && owner.optString("id") == roomId) {
                ownerIndex = index
                break
            }
        }
        if (ownerIndex < 0) return
        var input = 0L
        var cacheRead = 0L
        var cacheWrite = 0L
        var output = 0L
        for (index in 0 until rows.length()) {
            val row = rows.optJSONArray(index) ?: continue
            if (row.optInt(1, -1) != ownerIndex) continue
            input += row.optLong(4, 0L)
            cacheRead += row.optLong(5, 0L)
            cacheWrite += row.optLong(6, 0L)
            output += row.optLong(7, 0L)
        }
        val tokens = input + cacheRead + cacheWrite + output
        val hit = if (input + cacheRead > 0L) cacheRead.toFloat() / (input + cacheRead) else null
        _ui.update { it.copy(usageTokens = tokens, cacheHit = hit) }
    }

    fun updateRoom(
        patch: JSONObject,
        optimistic: (RoomInfo) -> RoomInfo = { it },
        onDone: (String?) -> Unit = {},
    ) {
        val connection = client ?: return
        val previous = _ui.value.info
        _ui.update { state ->
            state.copy(
                saving = true,
                info = state.info?.let(optimistic),
            )
        }
        viewModelScope.launch {
            runCatching {
                connection.bot(
                    "update-room",
                    JSONObject().put("id", roomId).also { body ->
                        patch.keys().forEach { key -> body.put(key, patch.get(key)) }
                    },
                )
            }.onSuccess {
                _ui.update { it.copy(saving = false) }
                load()
                onDone(null)
            }.onFailure { failure ->
                _ui.update {
                    it.copy(
                        saving = false,
                        info = previous,
                        error = failure.message ?: "群设置保存失败",
                    )
                }
                onDone(failure.message ?: "群设置保存失败")
            }
        }
    }

    fun setFlags(patch: JSONObject, optimistic: (RoomInfo) -> RoomInfo) {
        val connection = client ?: return
        val previous = _ui.value.info
        _ui.update { state -> state.copy(saving = true, info = state.info?.let(optimistic)) }
        viewModelScope.launch {
            runCatching {
                connection.bot(
                    "set-flags",
                    JSONObject().put("id", roomId).also { body ->
                        patch.keys().forEach { key -> body.put(key, patch.get(key)) }
                    },
                )
            }.onSuccess {
                _ui.update { it.copy(saving = false) }
                load()
            }.onFailure { failure ->
                _ui.update {
                    it.copy(
                        saving = false,
                        info = previous,
                        error = failure.message ?: "群显示设置失败",
                    )
                }
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
                        .put("roomId", roomId)
                        .put("scope", entry.scope)
                        .put("topic", entry.topic)
                        .put("text", entry.text),
                )
            }.onSuccess {
                loadMemory(connection)
                onDone(null)
            }.onFailure { failure -> onDone(failure.message ?: "删除群记忆失败") }
        }
    }

    /** 对着群记忆说话 / 直接更新（`memory-ask {roomId, text}`）。 */
    fun memoryAsk(text: String, onReply: (String?) -> Unit = {}) {
        val connection = client ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            onReply(null)
            return
        }
        viewModelScope.launch {
            runCatching {
                connection.bot("memory-ask", JSONObject().put("roomId", roomId).put("text", trimmed))
            }.onSuccess { view ->
                applyMemoryView(view)
                onReply(view.optString("reply").takeIf { it.isNotEmpty() })
                if (view.optBoolean("summarizing", false)) refreshMemory(false)
            }.onFailure { failure -> onReply(failure.message ?: "群记忆更新失败") }
        }
    }

    /** 加一条群记忆：`memory-add {roomId, scope, topic?, text}`。 */
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
                    .put("roomId", roomId)
                    .put("scope", scope)
                    .put("text", trimmed)
                topic?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("topic", it) }
                connection.bot("memory-add", body)
            }.onSuccess { view ->
                applyMemoryView(view)
                onDone(null)
            }.onFailure { onDone(it.message ?: "保存群记忆失败") }
        }
    }

    /** 改一条群记忆：`memory-edit {roomId, scope, topic, text, next}`。 */
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
                        .put("roomId", roomId)
                        .put("scope", entry.scope)
                        .put("topic", topic.orEmpty())
                        .put("text", entry.text)
                        .put("next", trimmed),
                )
            }.onSuccess { view ->
                applyMemoryView(view)
                onDone(null)
            }.onFailure { onDone(it.message ?: "修改群记忆失败") }
        }
    }

    fun delete(onDone: (String?) -> Unit) {
        val connection = client ?: return
        _ui.update { it.copy(saving = true) }
        viewModelScope.launch {
            runCatching { connection.bot("delete-room", JSONObject().put("id", roomId)) }
                .onSuccess {
                    _ui.update { it.copy(saving = false) }
                    onDone(null)
                }
                .onFailure { failure ->
                    _ui.update { it.copy(saving = false, error = failure.message ?: "删除群聊失败") }
                    onDone(failure.message ?: "删除群聊失败")
                }
        }
    }

    companion object {
        fun factory(roomId: String, endpoint: RuntimeEndpoint?): ViewModelProvider.Factory =
            viewModelFactory { initializer { RoomSettingsViewModel(roomId, endpoint) } }

        suspend fun createRoom(
            endpoint: RuntimeEndpoint?,
            name: String,
            admin: String,
            members: List<String>,
        ): Result<String> {
            val connection = endpoint ?: return Result.failure(IllegalStateException("本机引擎未运行"))
            val api = RemoteDshApi(DshClients.of(connection))
            return runCatching {
                val body = JSONObject()
                    .put("members", JSONArray().apply { (listOf(admin) + members).forEach { put(it) } })
                    .put("admin", admin)
                    .put("greet", true)
                if (name.isNotBlank()) body.put("name", name.trim())
                api.bot("create-room", body).optString("id")
            }.also { result ->
                if (result.isSuccess && result.getOrNull().isNullOrEmpty()) {
                    return Result.failure(IllegalStateException("创建群聊没有返回 id"))
                }
            }
        }
    }
}
