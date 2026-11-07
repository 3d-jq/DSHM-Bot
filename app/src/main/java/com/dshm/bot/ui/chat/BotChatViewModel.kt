package com.dshm.bot.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshm.bot.data.remote.BotFeedEvent
import com.dshm.bot.domain.model.ChatApproval
import com.dshm.bot.data.remote.DshRemoteApi
import com.dshm.bot.data.remote.parseChatPage
import com.dshm.bot.data.remote.RemoteDshApi
import com.dshm.bot.data.remote.ChatCursor
import com.dshm.bot.data.remote.DshClients
import com.dshm.bot.data.remote.DshClient
import com.dshm.bot.data.remote.DshSessionRepository
import com.dshm.bot.data.remote.DshMuxHub
import com.dshm.bot.data.remote.DshHostEvent
import com.dshm.bot.data.remote.DshQuestionRequest
import com.dshm.bot.data.remote.DshQuestionAnswer
import com.dshm.bot.data.remote.answersToJson
import com.dshm.bot.data.remote.BotApproval
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
import java.util.UUID

/** 对话里的一行：用户消息、Bot 消息、或系统事件。 */
enum class ChatEntryKind { USER, BOT, EVENT }

data class ChatEntry(
    val id: String,
    val kind: ChatEntryKind,
    val text: String,
    val author: String? = null,
    val botId: String? = null,
    /** 本地待发/刚发的附件名；历史回读没有附件详情时为空。 */
    val attachments: List<String> = emptyList(),
    /** 消息时间（毫秒）。 */
    val timeMs: Long? = null,
    /** 群聊里这条回复的耗时（毫秒）。 */
    val durationMs: Long? = null,
    /** 群聊/单聊事件类型（done/stopped/working/...）。 */
    val eventKind: String? = null,
    val eventCode: String? = null,
)

/** 待发送的本地附件。 */
data class ChatAttachmentDraft(
    val name: String,
    val bytes: ByteArray,
    val isImage: Boolean,
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
    /** DS Bot 活动状态行：当前工具/思考文案、头像状态、回合起始时间。 */
    val activity: ChatActivity? = null,
    /** DS Bot 的短暂「更新记忆」提示（3.2 秒后收回）。 */
    val memoryNotice: Boolean = false,
    /** 正在流式输出的回复；非空时在列表末尾显示一个临时气泡。 */
    val streaming: String = "",
    /** Bot 正在跑一回合：输入框空时显示停止键。 */
    val running: Boolean = false,
    /** 停止键的稳定显示状态：只随回合开始/稳定结束切换，不跟瞬时 work 抖动。 */
    val stopVisible: Boolean = false,
    /** 这个 Bot（或它所在的群）正在等的审批 / 提问。 */
    val approvals: List<ChatApproval> = emptyList(),
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
    private val rawClient: DshClient? = endpoint?.let { DshClients.of(it) }
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
    /** 最近一次 `state.work.approvals` 里属于本对话的条目 —— 卡片的显示身份。 */
    private var botApprovals: List<BotApproval> = emptyList()
    /** 引擎在等用户回应的提问（eventId → 请求）。 */
    private val hostQuestions = LinkedHashMap<String, DshQuestionRequest>()
    /** 当前 mux 连接的 clientId；反问作答要带回它。 */
    private var hostClientId: String? = null
    /** 更早历史的游标；null 表示没有更早的了。 */
    private var cursor: ChatCursor? = null
    private var feedJob: Job? = null
    private var reloadJob: Job? = null
    private var statusJob: Job? = null
    private var runningWatchJob: Job? = null
    private var memoryNoticeJob: Job? = null
    private var streamingAttempt: String? = null
    private var streamingText = ""
    private var runStartedAt = 0L
    private var runFinishedAt = 0L
    private var loadedOnce = false
    /** 本屏发出的用户气泡；服务端回显用 `requestId` 认出同一条，不画第二条。 */
    private val localUserEntries = LinkedHashMap<String, ChatEntry>()

    init {
        if (client != null) {
            // 先读历史，别让补模型/探测目录的 state 调用挡住对话首屏。
            load(initial = true)
            viewModelScope.launch { repairModelIfNeeded() }
            refreshBotStatus()
            feedJob = viewModelScope.launch {
                client.feed().collect { event -> handleFeed(event) }
            }
            // 主机事件流（`$events`）只用来显示 Bot 反问用户；审批已归 DS Bot。
            endpoint?.let { runtime ->
                viewModelScope.launch {
                    val mux = DshMuxHub.start(runtime)
                    mux.hostEvents.collect { event -> handleHostEvent(event) }
                }
            }
        }
    }

    /**
     * 处理主机事件（`$events`）。
     *
     * 这里只维护「引擎在等什么」的缓存，卡片一律由 [recomputeApprovals] 重算：
     * 显示身份归 DS Bot 的 state，事件流只补 Bot 反问用户。
     */
    private fun handleHostEvent(event: DshHostEvent) {
        when (event) {
            // 新一代连接：没答完的请求会带着同样的 eventId 重新投递，
            // 所以先清掉旧的缓存，别攒下别人已经答完的幽灵卡片。
            is DshHostEvent.Ready -> {
                hostClientId = event.clientId
                hostQuestions.clear()
                recomputeApprovals()
            }

            is DshHostEvent.Waterfall -> {
                event.question?.let { request ->
                    hostQuestions[request.eventId] = request
                    trimOldest(hostQuestions)
                }
                recomputeApprovals()
            }

            // 任何一端答完（或请求被撤回）都会收到 cancel。
            is DshHostEvent.Cancelled -> {
                hostQuestions.remove(event.eventId)
                recomputeApprovals()
            }

            else -> Unit
        }
    }

    /**
     * 用缓存重算卡片。事件流和 state 谁先到都不影响结果：
     * 审批只认 DS Bot 的 `answerable`，事件流只补提问。
     */
    private fun recomputeApprovals() {
        val merged = mergeChatApprovals(
            botEntries = botApprovals,
            hostQuestions = hostQuestions.values.toList(),
            isGroup = isGroup,
            botId = botId,
            sessionId = sessionId,
        )
        _uiState.update { if (it.approvals == merged) it else it.copy(approvals = merged) }
    }

    /** 缓存要有上限：漏掉一次 cancel 不该让卡片永远挂着。 */
    private fun <T> trimOldest(pending: LinkedHashMap<String, T>) {
        while (pending.size > HOST_PENDING_CAP) {
            val oldest = pending.keys.firstOrNull() ?: return
            pending.remove(oldest)
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

    /** 发一条消息：用户气泡立刻上屏，服务端回显按 requestId 跳过。 */
    fun send(raw: String, attachments: List<ChatAttachmentDraft> = emptyList()) {
        val connection = client ?: return
        val text = raw.trim()
        if ((text.isEmpty() && attachments.isEmpty()) || _uiState.value.sending) return
        val requestId = UUID.randomUUID().toString()
        runStartedAt = System.nanoTime()
        val bubble = ChatEntry(
            id = "user:$requestId",
            kind = ChatEntryKind.USER,
            text = text,
            attachments = attachments.map { it.name },
        )
        localUserEntries[requestId] = bubble
        seen.add(bubble.id)
        // 点击那一帧就画出来，不等 HTTP / SSE 回显。
        _uiState.update { state ->
            state.copy(
                sending = true,
                error = null,
                entries = state.entries + bubble,
            )
        }
        viewModelScope.launch {
            runCatching {
                if (attachments.isEmpty()) {
                    connection.bot(
                        endpoint = "send",
                        payload = JSONObject()
                            .put("sessionId", botId)
                            .put("requestId", requestId)
                            .put("text", text),
                    )
                } else {
                    val session = sessionId ?: botId
                    val receipts = attachments.map { draft ->
                        (rawClient ?: error("本机引擎未运行"))
                            .uploadAttachment(session, draft.name, draft.bytes)
                    }
                    DshSessionRepository(rawClient ?: error("本机引擎未运行")).prompt(
                        sessionId = session,
                        text = text,
                        requestId = requestId,
                        fileReceiptIds = receipts.map { it.receiptId },
                    )
                }
            }.onSuccess {
                runStartedAt = System.nanoTime()
                _uiState.update {
                    it.copy(
                        sending = false,
                        waitingForReply = true,
                        running = true,
                        stopVisible = true,
                    )
                }
                watchForRunEnd()
            }.onFailure { failure ->
                runFinishedAt = System.nanoTime()
                localUserEntries.remove(requestId)
                seen.remove(bubble.id)
                _uiState.update { state ->
                    state.copy(
                        sending = false,
                        waitingForReply = state.waitingForReply,
                        running = state.running,
                        entries = state.entries.filterNot { it.id == bubble.id },
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
                // 历史还没追上这次发送时，本地气泡也不能被刷新弄丢。
                val withLocal = entries + localUserEntries.values.filterNot { local ->
                    entries.any { it.id == local.id }
                }
                seen.clear()
                withLocal.forEach { seen.add(it.id) }
                cursor = parseChatPage(value).cursor
                _uiState.update { state ->
                    state.copy(
                        loading = false,
                        engineOff = false,
                        error = null,
                        entries = withLocal,
                        hasEarlier = cursor?.hasMore == true,
                    )
                }
                clearStreaming()
                loadedOnce = true
                // 刚学到这个 Bot 当前用的 DSH 会话 id：事件流里的请求靠它认领。
                recomputeApprovals()
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
                // 但一条 bot 文字 / 一个 stream 段不等于整回合结束：DS Bot 还能继续
                // 调工具、换目录、等待审批。running/activity 只信 state.work。
                if (entry.kind == ChatEntryKind.BOT) {
                    clearStreaming()
                }
                _uiState.update { state ->
                    state.copy(
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
                        runStartedAt = System.nanoTime()
                        _uiState.update {
                            it.copy(
                                streaming = "",
                                running = true,
                                stopVisible = true,
                                waitingForReply = false,
                                activity = it.activity?.copy() ?: ChatActivity(
                                    kind = ChatActivityKind.THINKING,
                                    label = null,
                                    startedAtMs = System.currentTimeMillis(),
                                ),
                            )
                        }
                    }

                    "end" -> {
                        val attempt = data.optString("attemptId")
                        viewModelScope.launch {
                            // 最终 message 通常先到并已清掉流式气泡；这里只兜底。
                            // stream/end 只代表这一段文字写完，不代表整回合结束。
                            delay(200)
                            if (streamingAttempt == attempt) {
                                clearStreaming()
                                refreshBotStatus()
                            }
                        }
                    }

                    else -> {
                        val chunk = data.optString("text")
                        if (chunk.isNotEmpty()) {
                            streamingText += chunk
                            runStartedAt = System.nanoTime()
                            _uiState.update {
                                it.copy(
                                    streaming = streamingText,
                                    running = true,
                                    stopVisible = true,
                                    activity = it.activity ?: ChatActivity(
                                        kind = ChatActivityKind.THINKING,
                                        label = null,
                                        startedAtMs = System.currentTimeMillis(),
                                    ),
                                )
                            }
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
            val work = state.optJSONObject("work")
            val stateRunning = if (isGroup) {
                val rooms = work?.optJSONObject("rooms")
                (rooms?.optJSONArray(botId)?.length() ?: 0) > 0
            } else {
                work?.optJSONObject("bots")?.has(botId) == true
            }
            val running = stateRunning || (
                runStartedAt > runFinishedAt &&
                    System.nanoTime() - runStartedAt < RUNNING_OPTIMISTIC_NS
                )
            val activity = parseChatActivity(
                state = state,
                botId = botId,
                sessionId = sessionId,
                isGroup = isGroup,
                running = running,
            )
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
            // 审批：DS Bot 的 state 就是卡片身份；引擎事件流只补提问。
            botApprovals = parseBotApprovals(state).filter { entry ->
                if (isGroup) entry.roomId == botId else entry.botId == botId
            }
            _uiState.update {
                it.copy(
                    botStatus = role.takeIf { name -> name.isNotEmpty() }
                        ?: if (isMain) "主 Bot" else "空闲",
                    botIsMain = isMain,
                    running = running,
                    stopVisible = running,
                    activity = activity,
                )
            }
            recomputeApprovals()
        }
    }

    /** 应答一条审批：允许一次 / 拒绝。统一走 DS Bot 的 `approval-answer`。 */
    fun answerApproval(approval: ChatApproval, allow: Boolean, onDone: (String?) -> Unit = {}) {
        val connection = client ?: return
        viewModelScope.launch {
            val id = approval.key.removePrefix("bot:")
            val error = runCatching {
                connection.bot(
                    "approval-answer",
                    JSONObject().put("id", id).put("choice", if (allow) "allow" else "reject"),
                )
            }.exceptionOrNull()?.let { describeChatError(it) }
            if (error == null) {
                // 应答完先本地撤卡：引擎随后刷新 state，但界面不该等它。
                botApprovals = botApprovals.filterNot { entry -> entry.id == id }
                recomputeApprovals()
                refreshBotStatus()
            }
            onDone(error)
        }
    }

    /** 回答 Bot 的反问：把选中项按 DSH `userQuestions/answer` 回执。 */
    fun answerQuestion(
        approval: ChatApproval,
        selected: List<String>,
        onDone: (String?) -> Unit = {},
    ) {
        val clientId = hostClientId
        val eventId = approval.eventId
        val questionId = approval.questionId
        val repo = rawClient
        if (clientId == null || eventId == null || questionId == null || repo == null || selected.isEmpty()) {
            onDone("这条提问暂时无法作答")
            return
        }
        viewModelScope.launch {
            val answers = answersToJson(selected.map { DshQuestionAnswer(questionId, it) })
            val error = runCatching {
                DshSessionRepository(repo).answerQuestion(clientId, eventId, answers)
            }.exceptionOrNull()?.let { describeChatError(it) }
            if (error == null) {
                hostQuestions.remove(eventId)
                recomputeApprovals()
                refreshBotStatus()
            }
            onDone(error)
        }
    }

    /** 停止当前对话正在执行的回合：按钮点下去立刻收起，再让引擎取消。 */
    fun stopRunning(onDone: (String?) -> Unit = {}) {
        val connection = client ?: return
        runFinishedAt = System.nanoTime()
        clearStreaming()
        runningWatchJob?.cancel()
        _uiState.update {
            it.copy(
                running = false,
                waitingForReply = false,
                activity = null,
                stopVisible = false,
            )
        }
        viewModelScope.launch {
            val error = runCatching {
                connection.bot("stop", JSONObject().put("sessionId", botId))
            }.exceptionOrNull()?.let { describeChatError(it) }
            refreshBotStatus()
            onDone(error)
        }
    }

    /**
     * 一轮工作可能跨过多段文字 / 工具调用 / 文件夹切换。feed 没有「整回合结束」
     * 事件，所以发送后按 DS Bot 自己的 `work` 轻量轮询：它空了才收状态行。
     */
    private fun watchForRunEnd() {
        val connection = client ?: return
        runningWatchJob?.cancel()
        runningWatchJob = viewModelScope.launch {
            var idlePolls = 0
            while (idlePolls < 2) {
                delay(1_000)
                val state = runCatching { connection.bot("state") }.getOrNull() ?: continue
                val work = state.optJSONObject("work")
                val running = if (isGroup) {
                    (work?.optJSONObject("rooms")?.optJSONArray(botId)?.length() ?: 0) > 0
                } else {
                    work?.optJSONObject("bots")?.has(botId) == true
                }
                if (running) {
                    idlePolls = 0
                    refreshBotStatus()
                } else {
                    idlePolls += 1
                }
            }
            runFinishedAt = System.nanoTime()
            _uiState.update { it.copy(running = false, activity = null, stopVisible = false) }
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
        val rpcId = item.optString("rpcId").takeIf { it.isNotEmpty() }
        val time = item.optLong("time", -1L).takeIf { it > 0L }
        val duration = item.optLong("duration", -1L).takeIf { it > 0L }
        return when (item.optString("kind")) {
            "user" -> text.takeIf { it.isNotBlank() }
                ?.let {
                    ChatEntry(
                        id = if (rpcId != null) "user:$rpcId" else id,
                        kind = ChatEntryKind.USER,
                        text = it,
                        timeMs = time,
                    )
                }

            "bot" -> text.takeIf { it.isNotBlank() }
                ?.let {
                    ChatEntry(
                        id = id,
                        kind = ChatEntryKind.BOT,
                        text = it,
                        author = item.optString("name").ifEmpty { botName },
                        botId = item.optString("botId").takeIf { value -> value.isNotEmpty() },
                        timeMs = time,
                        durationMs = duration,
                    )
                }

            "answer" -> text.takeIf { it.isNotBlank() }
                ?.let { ChatEntry(id, ChatEntryKind.BOT, it, botName, timeMs = time) }

            "event" -> {
                val eventKind = item.optString("event").ifEmpty { item.optString("role") }
                val name = item.optString("name").ifEmpty { botName }
                ChatEntry(
                    id = id,
                    kind = ChatEntryKind.EVENT,
                    text = text,
                    author = name,
                    botId = item.optString("botId").takeIf { value -> value.isNotEmpty() },
                    timeMs = time,
                    eventKind = eventKind.takeIf { it.isNotEmpty() },
                    eventCode = item.optString("code").takeIf { it.isNotEmpty() },
                )
            }

            else -> null
        }
    }

    override fun onCleared() {
        feedJob?.cancel()
        reloadJob?.cancel()
    }

    companion object {
        /** 事件流缓存的上限：同时等应答的提问一般就一个对话，多出来的一定是漏掉的 cancel。 */
        private const val HOST_PENDING_CAP = 32
        private const val STOP_GRACE_NS = 2_000_000_000L
        /** 发送确认前先用本地 running 顶住状态行；之后完全交给 state.work。 */
        private const val RUNNING_OPTIMISTIC_NS = 30_000_000_000L

        fun factory(
            botId: String,
            botName: String,
            isGroup: Boolean,
            endpoint: RuntimeEndpoint?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BotChatViewModel(botId, botName, isGroup, endpoint)
            }
        }
    }
}
