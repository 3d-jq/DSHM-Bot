package com.dshm.bot.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshm.bot.data.remote.BotApproval
import com.dshm.bot.data.remote.BotFeedEvent
import com.dshm.bot.data.remote.DshRemoteApi
import com.dshm.bot.data.remote.parseChatPage
import com.dshm.bot.data.remote.RemoteDshApi
import com.dshm.bot.data.remote.ChatCursor
import com.dshm.bot.data.remote.DshClients
import com.dshm.bot.data.remote.parseBotApprovals
import com.dshm.bot.data.remote.RemoteSpeechRepository
import com.dshm.bot.data.remote.SpeechRepository
import com.dshm.bot.data.runtime.RuntimeEndpoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 对话里的一行：用户消息、Bot 消息、或系统事件。 */
enum class ChatEntryKind { USER, BOT, EVENT }

data class ChatEntry(
    val id: String,
    val kind: ChatEntryKind,
    val text: String,
    val author: String? = null,
)

data class BotChatUiState(
    val loading: Boolean = true,
    val engineOff: Boolean = false,
    val error: String? = null,
    val entries: List<ChatEntry> = emptyList(),
    val sending: Boolean = false,
    /** 消息已发出，等 Bot 的第一段回复；此时显示思考动画。 */
    val waitingForReply: Boolean = false,
    val botModel: String? = null,
    val botWorkDir: String? = null,
    /** 引擎报告的活动：更新记忆 / 思考 / 等待确认。 */
    val botStatus: String? = null,
    val botIsMain: Boolean = false,
    /** DS Bot 的短暂「更新记忆」提示（3.2 秒后收回）。 */
    val memoryNotice: Boolean = false,
    /** 正在流式输出的回复；非空时在列表末尾显示一个临时气泡。 */
    val streaming: String = "",
    /** 这个 Bot（或它所在的群）正在等的审批。 */
    val approvals: List<BotApproval> = emptyList(),
    /** 还有更早的历史（游标非空）。 */
    val hasEarlier: Boolean = false,
    /** 正在往前翻一页。 */
    val loadingEarlier: Boolean = false,
)

/**
 * 一个 Bot（或群聊）的真实对话：
 * - 历史：`/api/bot` 的 `chat`（群聊用 `room-chat`）
 * - 发送：`send`，sessionId 用 Bot id（群聊用 room id）
 * - 实时：`/api/bot/feed` 的 `message` / `stream` / `room`
 *
 * 数据形状对齐 ds-bot 的 `parts.js`（displayItems）与 `feed.js`。
 */
class BotChatViewModel(
    private val botId: String,
    private val botName: String,
    private val isGroup: Boolean,
    private val endpoint: RuntimeEndpoint?,
) : ViewModel() {

    private val client: DshRemoteApi? = endpoint?.let { RemoteDshApi(DshClients.of(it)) }
    /** 语音输入：页面/输入框只依赖接口。 */
    val speechRepository: SpeechRepository? = endpoint?.let { RemoteSpeechRepository(DshClients.of(it)) }
    private val _uiState = MutableStateFlow(
        BotChatUiState(
            loading = endpoint == null,
            engineOff = endpoint == null,
        ),
    )
    val uiState: StateFlow<BotChatUiState> = _uiState.asStateFlow()

    private val seen = LinkedHashSet<String>()
    private var sessionId: String? = null
    /** 更早历史的游标；null 表示没有更早的了。 */
    private var cursor: ChatCursor? = null
    private var feedJob: Job? = null
    private var reloadJob: Job? = null
    private var statusJob: Job? = null
    private var memoryNoticeJob: Job? = null
    private var streamingAttempt: String? = null
    private var streamingText = ""
    private var loadedOnce = false

    init {
        if (client != null) {
            // 先读历史，别让补模型/探测目录的 state 调用挡住对话首屏。
            load(initial = true)
            viewModelScope.launch { repairModelIfNeeded() }
            refreshBotStatus()
            feedJob = viewModelScope.launch {
                client.feed().collect { event -> handleFeed(event) }
            }
        }
    }

    /**
     * 新 Bot 在建好时可能还没有可用模型（创建那一刻模型目录为空），之后 DSH 会拿
     * 一个已经下线的默认模型跑回合，表现为消息发出但没有回复。这里用当前默认模型
     * 给它补一次，已有有效模型的 Bot 不动。
     */
    private suspend fun repairModelIfNeeded() {
        val connection = client ?: return
        if (isGroup) return
        val state = runCatching { connection.bot("state") }.getOrNull() ?: return
        val default = state.optString("defaultModel")
        if (default.isEmpty()) return
        val served = buildSet {
            val models = state.optJSONArray("models") ?: return@buildSet
            for (index in 0 until models.length()) {
                models.optJSONObject(index)?.optString("ref")?.takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
        if (served.isEmpty()) return
        val bots = state.optJSONArray("bots") ?: return
        val bot = (0 until bots.length())
            .mapNotNull { bots.optJSONObject(it) }
            .firstOrNull { it.optString("id") == botId }
            ?: return
        val current = bot.optString("model").ifEmpty { bot.optString("appliedModel") }
        val modelName = buildString {
            val models = state.optJSONArray("models")
            if (models != null) {
                for (index in 0 until models.length()) {
                    val item = models.optJSONObject(index) ?: continue
                    if (item.optString("ref") == current) {
                        append(item.optString("name").ifEmpty { current })
                        break
                    }
                }
            }
            if (isEmpty() && current.isNotEmpty()) append(current)
        }.takeIf { it.isNotEmpty() }
        _uiState.update {
            it.copy(
                botModel = modelName,
                botWorkDir = bot.optString("workDir").takeIf { dir -> dir.isNotEmpty() },
            )
        }
        if (current.isNotEmpty() && current in served) return
        runCatching {
            connection.bot(
                endpoint = "update-bot",
                payload = JSONObject()
                    .put("id", botId)
                    .put("model", default),
            )
        }
    }

    fun retry() {
        if (client == null) return
        if (feedJob?.isActive != true) {
            feedJob = viewModelScope.launch {
                client.feed().collect { event -> handleFeed(event) }
            }
        }
        load(initial = false)
    }

    /**
     * 发一条消息。
     *
     * **不做本地气泡**（照 DeepSearch）：用户消息等服务端回显。
     * 本地先画一条会让消息「卡在输入框下面」—— 回显时还要再替换它，
     * 顺序一乱就会看到两条。
     */
    fun send(raw: String) {
        val connection = client ?: return
        val text = raw.trim()
        if (text.isEmpty() || _uiState.value.sending) return
        _uiState.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            runCatching {
                connection.bot(
                    endpoint = "send",
                    payload = JSONObject()
                        .put("sessionId", botId)
                        .put("text", text),
                )
            }.onSuccess {
                _uiState.update { it.copy(sending = false, waitingForReply = true) }
            }.onFailure { failure ->
                _uiState.update { state ->
                    state.copy(
                        sending = false,
                        waitingForReply = false,
                        error = describeChatError(failure),
                    )
                }
            }
        }
    }

    private fun load(initial: Boolean) {
        val connection = client ?: return
        if (!initial) _uiState.update { it.copy(error = null) }
        viewModelScope.launch {
            runCatching {
                connection.bot(
                    endpoint = if (isGroup) "room-chat" else "chat",
                    payload = JSONObject().put(if (isGroup) "roomId" else "botId", botId),
                )
            }.onSuccess { value ->
                value.optString("sessionId").takeIf { it.isNotEmpty() }?.let { sessionId = it }
                val items = value.optJSONArray("items")
                val scope = sessionId ?: botId
                val entries = buildList {
                    if (items != null) {
                        for (index in 0 until items.length()) {
                            val item = items.optJSONObject(index) ?: continue
                            parseItem(item, scope, "history:$index")?.let(::add)
                        }
                    }
                }
                seen.clear()
                entries.forEach { seen.add(it.id) }
                cursor = parseChatPage(value).cursor
                _uiState.update { state ->
                    state.copy(
                        loading = false,
                        engineOff = false,
                        error = null,
                        entries = entries,
                        hasEarlier = cursor?.hasMore == true,
                    )
                }
                clearStreaming()
                loadedOnce = true
            }.onFailure { failure ->
                _uiState.update { state ->
                    state.copy(
                        loading = false,
                        error = if (!initial && loadedOnce) state.error else describeChatError(failure),
                    )
                }
            }
        }
    }

    /**
     * 往前翻一页更早的历史。
     *
     * 引擎的 `chat` 只给最新 60 条（`EARLIER_PAGE`），要看到更早的得带游标再问一次；
     * 新的一页插到列表**前面**（对齐 dsh-Go 的 `loadEarlier`）。
     */
    fun loadEarlier() {
        val connection = client ?: return
        val current = cursor ?: return
        if (_uiState.value.loadingEarlier) return
        _uiState.update { it.copy(loadingEarlier = true, error = null) }
        viewModelScope.launch {
            val payload = JSONObject().put(if (isGroup) "roomId" else "botId", botId)
            current.toJson()?.let { payload.put("cursor", it) }
            runCatching { connection.bot(endpoint = if (isGroup) "room-chat" else "chat", payload = payload) }
                .onSuccess { value ->
                    val items = value.optJSONArray("items")
                    val scope = sessionId ?: botId
                    val earlier = buildList {
                        if (items != null) {
                            for (index in 0 until items.length()) {
                                val item = items.optJSONObject(index) ?: continue
                                parseItem(item, scope, "earlier:$index")?.let(::add)
                            }
                        }
                    }
                    cursor = parseChatPage(value).cursor
                    _uiState.update { state ->
                        state.copy(
                            loadingEarlier = false,
                            // 更早的插在前面；已经见过的不要重复。
                            entries = earlier.filterNot { it.id in seen } + state.entries,
                            hasEarlier = cursor?.hasMore == true,
                        )
                    }
                    earlier.forEach { seen.add(it.id) }
                }
                .onFailure { failure ->
                    _uiState.update { it.copy(loadingEarlier = false, error = describeChatError(failure)) }
                }
        }
    }

    private fun scheduleReload() {
        if (client == null) return
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            delay(400)
            load(initial = false)
        }
    }

    private fun handleFeed(event: BotFeedEvent) {
        val data = runCatching { JSONObject(event.data) }.getOrNull() ?: return
        when (event.event) {
            "message" -> {
                val belongs = data.optString("botId") == botId || data.optString("sessionId") == sessionId
                if (!belongs) return
                val item = data.optJSONObject("item") ?: return
                val scope = data.optString("sessionId").ifEmpty { sessionId ?: botId }
                val entry = parseItem(item, scope, "feed:${System.nanoTime()}") ?: return
                if (!seen.add(entry.id)) return
                // 最终消息已经进列表，立即撤掉流式临时气泡，避免同一段回复出现两遍。
                if (entry.kind == ChatEntryKind.BOT) clearStreaming()
                _uiState.update { state ->
                    state.copy(
                        // 用户消息没有本地气泡，直接追加服务端回显的那条。
                        entries = state.entries + entry,
                        waitingForReply = if (entry.kind == ChatEntryKind.USER) state.waitingForReply else false,
                    )
                }
            }

            "stream" -> {
                if (data.optString("botId") != botId) return
                when (data.optString("phase")) {
                    "start" -> {
                        streamingAttempt = data.optString("attemptId").ifEmpty { "stream" }
                        streamingText = ""
                        _uiState.update { it.copy(streaming = "") }
                    }

                    "end" -> {
                        val attempt = data.optString("attemptId")
                        viewModelScope.launch {
                            // 最终 message 通常先到并已清掉流式气泡；这里只兜底。
                            delay(200)
                            if (streamingAttempt == attempt) {
                                clearStreaming()
                                _uiState.update { it.copy(waitingForReply = false) }
                            }
                        }
                    }

                    else -> {
                        val chunk = data.optString("text")
                        if (chunk.isNotEmpty()) {
                            streamingText += chunk
                            _uiState.update { it.copy(streaming = streamingText) }
                        }
                    }
                }
            }

            // 审批请求会变成一条 notice（kind = "approval"）——
            // 收到就去重读状态，把审批卡片显示出来。
            "notice" -> {
                val kind = data.optString("kind")
                val belongs = data.optString("botId") == botId ||
                    data.optString("roomId") == botId
                if (kind == "approval" && belongs) refreshBotStatus()
            }

            "room" -> {
                if (isGroup && data.optString("roomId") == botId) scheduleReload()
            }

            "hello", "revision" -> scheduleReload()
            "live" -> refreshBotStatus()
        }
    }

    /** 读活动/记忆新闻，给名字胶囊下面那行状态用。 */
    private fun refreshBotStatus() {
        val connection = client ?: return
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            delay(350)
            val state = runCatching { connection.bot("state") }.getOrNull() ?: return@launch
            val bots = state.optJSONArray("bots")
            val bot = bots?.let { array ->
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                    .firstOrNull { it.optString("id") == botId }
            }
            val role = bot?.optString("role").orEmpty().trim()
            val mainIds = buildSet {
                state.optJSONArray("mainBotIds")?.let { array ->
                    for (index in 0 until array.length()) {
                        array.optString(index).takeIf { it.isNotEmpty() }?.let(::add)
                    }
                }
            }
            val isMain = botId in mainIds
            val news = state.optJSONObject("memoryNews")?.optJSONObject(botId)
            val newsAt = news?.optLong("at", 0L) ?: 0L
            val recentNews = newsAt > 0L && System.currentTimeMillis() - newsAt < 5 * 60_000L
            if (recentNews) {
                _uiState.update { it.copy(memoryNotice = true) }
                memoryNoticeJob?.cancel()
                memoryNoticeJob = viewModelScope.launch {
                    delay(3_200)
                    _uiState.update { it.copy(memoryNotice = false) }
                }
            }
            // 审批：DSH 弹的审批卡片，DS Bot 这边也要能看到并应答。
            val approvals = parseBotApprovals(state).filter { entry ->
                if (isGroup) entry.roomId == botId else entry.botId == botId
            }
            _uiState.update {
                it.copy(
                    botStatus = role.takeIf { name -> name.isNotEmpty() }
                        ?: if (isMain) "主 Bot" else "空闲",
                    botIsMain = isMain,
                    approvals = approvals,
                )
            }
        }
    }

    /**
     * 应答一条审批：允许一次 / 拒绝。
     *
     * 应答后立刻重读状态 —— 审批卡片是 DSH 那边等的，答完就没了。
     */
    fun answerApproval(id: String, allow: Boolean, onDone: (String?) -> Unit = {}) {
        val connection = client ?: return
        viewModelScope.launch {
            val error = runCatching {
                connection.bot(
                    "approval-answer",
                    JSONObject().put("id", id).put("choice", if (allow) "allow" else "reject"),
                )
            }.exceptionOrNull()?.let { describeChatError(it) }
            if (error == null) refreshBotStatus()
            onDone(error)
        }
    }

    private fun clearStreaming() {
        streamingAttempt = null
        streamingText = ""
        if (_uiState.value.streaming.isNotEmpty()) {
            _uiState.update { it.copy(streaming = "") }
        }
    }

    private fun parseItem(item: JSONObject, scope: String, fallbackId: String): ChatEntry? {
        val seq = item.optLong("seq", -1L)
        val id = item.optString("id").ifEmpty {
            if (seq >= 0L) "$scope:$seq" else fallbackId
        }
        val text = item.optString("text")
        return when (item.optString("kind")) {
            "user" -> text.takeIf { it.isNotBlank() }
                ?.let { ChatEntry(id, ChatEntryKind.USER, it) }

            "bot" -> text.takeIf { it.isNotBlank() }
                ?.let {
                    ChatEntry(
                        id = id,
                        kind = ChatEntryKind.BOT,
                        text = it,
                        author = item.optString("name").ifEmpty { botName },
                    )
                }

            "answer" -> text.takeIf { it.isNotBlank() }
                ?.let { ChatEntry(id, ChatEntryKind.BOT, it, botName) }

            "event" -> text.takeIf { it.isNotBlank() }
                ?.let { ChatEntry(id, ChatEntryKind.EVENT, it) }

            else -> null
        }
    }

    override fun onCleared() {
        feedJob?.cancel()
        reloadJob?.cancel()
    }

    companion object {
        fun factory(
            botId: String,
            botName: String,
            isGroup: Boolean,
            endpoint: RuntimeEndpoint?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { BotChatViewModel(botId, botName, isGroup, endpoint) }
        }
    }
}
