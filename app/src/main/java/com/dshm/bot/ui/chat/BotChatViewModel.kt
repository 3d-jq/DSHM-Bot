package com.dshm.bot.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshm.bot.data.remote.BotFeedEvent
import com.dshm.bot.data.remote.DsBotParser
import com.dshm.bot.domain.model.ChatApproval
import com.dshm.bot.data.remote.DELIVERABLES_PRESENTED_EVENT
import com.dshm.bot.data.remote.presentedFilesOf
import com.dshm.bot.data.remote.DshRemoteApi
import com.dshm.bot.data.remote.parseChatPage
import com.dshm.bot.data.remote.RemoteDshApi
import com.dshm.bot.data.remote.ChatCursor
import com.dshm.bot.data.remote.DshClients
import com.dshm.bot.data.remote.DshClient
import com.dshm.bot.data.remote.DshSessionRepository
import com.dshm.bot.data.remote.DshMuxHub
import com.dshm.bot.data.remote.DshMux
import com.dshm.bot.data.remote.DshMuxLog
import com.dshm.bot.data.remote.DshHostEvent
import com.dshm.bot.data.remote.DshQuestionRequest
import com.dshm.bot.data.remote.DshQuestionAnswer
import com.dshm.bot.data.remote.answersToJson
import com.dshm.bot.data.remote.BotApproval
import com.dshm.bot.data.remote.parseBotApprovals
import com.dshm.bot.data.remote.RemoteSpeechRepository
import com.dshm.bot.data.remote.SpeechRepository
import com.dshm.bot.data.runtime.RuntimeEndpoint
import com.dshm.bot.ui.home.RoomMemberUi
import com.dshm.bot.ui.home.TeamRoomUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

/** 对话里的一行：用户消息、Bot 消息、系统事件、产物卡或浏览器缩略图卡。 */
enum class ChatEntryKind { USER, BOT, EVENT, ARTIFACTS, BROWSER_SHOT }

/**
 * 一轮浏览器操作结束后贴在对话里的页面缩略图。
 *
 * [path] 是应用私有目录里的 PNG（聊天页自己画的），不是引擎工作区里那份。
 */
data class ChatBrowserShot(
    val path: String,
    val url: String,
    val title: String,
    val timeMs: Long,
)

data class ChatEntry(
    val id: String,
    val kind: ChatEntryKind,
    val text: String,
    val author: String? = null,
    val botId: String? = null,
    /** 本地待发/刚发的附件名；历史回读没有附件详情时为空。 */
    val attachments: List<String> = emptyList(),
    /** 本地发过的附件（带缓存路径）：气泡里用来画缩略图 / 文件卡。 */
    val attachmentItems: List<ChatAttachmentItem> = emptyList(),
    /** 消息时间（毫秒）。 */
    val timeMs: Long? = null,
    /** 群聊里这条回复的耗时（毫秒）。 */
    val durationMs: Long? = null,
    /** 群聊/单聊事件类型（done/stopped/working/...）。 */
    val eventKind: String? = null,
    val eventCode: String? = null,
    /** 会话事件 seq；用于把 tool call 事件合并回时间线。 */
    val seq: Long? = null,
    /** 一轮结束时交付的文件（`present` 声明）；非空时画成产物卡。 */
    val artifacts: List<ChatArtifact> = emptyList(),
    /** 工具事件的目标（Bot / 群聊名字）；界面用它在团队里认头像。 */
    val target: String? = null,
    /** 产生这条事件的 DS Bot 工具名（`message_bot` / `remember`…），界面据此选图标。 */
    val tool: String? = null,
    /** 浏览器缩略图卡的内容（[ChatEntryKind.BROWSER_SHOT]）。 */
    val shot: ChatBrowserShot? = null,
)

/** 待发送的本地附件。 */
data class ChatAttachmentDraft(
    val name: String,
    val bytes: ByteArray,
    val isImage: Boolean,
    /** 缓存的本地路径：对话气泡里的缩略图 / 文件卡按它读（历史回显时字节已经不在内存里）。 */
    val cachePath: String? = null,
)

/** 对话气泡里的一个附件（图片画缩略图，文件画文件卡）。 */
data class ChatAttachmentItem(
    val name: String,
    val path: String,
    val isImage: Boolean,
)

private val FOLLOW_TOOLS = setOf(
    "message_bot", "stop_bot", "create_bot", "update_bot",
    "create_group", "update_group", "delete_group", "post_to_group",
    "read_group_chat",
    // 「看了一眼」类的动作（看团队 / 看密钥 / 翻自己的记录）、浏览器动作、更新记忆
    // 都不进对话时间线：活动状态栏已经在显示，每来一行只会把对话冲乱。
)

/** 密钥卡答完后在 dock 里多留一会儿，让「已保存 / 已允许 / 已取消」看得见。 */
private const val secretCardLingerMs = 20_000L

/** DS Bot `secrets.js` 的 `MIN_VALUE`：一把钥匙至少 4 个字符。 */
private const val SECRET_MIN_LENGTH = 4

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
    /** 输入框上方 dock 里的待办卡：DS Bot 的 `ask_user` 提问。 */
    val pendingQuestions: List<ChatQuestionCard> = emptyList(),
    /** 输入框上方 dock 里的待办卡：DS Bot 的 `show_view` 视图。 */
    val pendingViews: List<ChatViewCard> = emptyList(),
    /** 输入框上方 dock 里的密钥卡：DS Bot 的 `request_secret`（答过的只多留一小会儿）。 */
    val pendingSecrets: List<ChatSecretRequest> = emptyList(),
    /** 正在提交一张待办卡（按钮禁用，避免重复点）。 */
    val answeringPending: Boolean = false,
    /** 还有更早的历史（游标非空）。 */
    val hasEarlier: Boolean = false,
    /** 正在往前翻一页。 */
    val loadingEarlier: Boolean = false,
    /**
     * 团队成员的显示身份（会话 id → 头像/名字）。
     *
     * 事件行要认「事件来自谁」：单聊里 relay 事件来自另一个 Bot，
     * 光靠本对话的 Bot 会认错人（DS Bot 用 roster 认）。
     */
    val teamMembers: List<RoomMemberUi> = emptyList(),
    /** 团队的群聊（事件行「阅读群聊」要画成员头像堆叠）。 */
    val teamRooms: List<TeamRoomUi> = emptyList(),
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
            // 打开对话到 `chat` 回来之间就是「在加载」：骨架屏靠它，不能等请求回来才置上。
            loading = true,
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
    private var mux: DshMux? = null
    private var followStreamId: String? = null
    private var followSessionId: String? = null
    private val toolEvents = LinkedHashMap<Long, ChatEntry>()
    /** 每个 turn 的交付声明（按声明顺序，路径去重）：turn → 文件。 */
    private val presentedByTurn = LinkedHashMap<Int, MutableList<ChatArtifact>>()
    /** 声明所在的 seq：这一轮还没结束（没有 turn/end）时用它占位。 */
    private val presentedSeq = HashMap<Int, Long>()
    /** 每个 turn 的 `turn/end` seq —— 产物卡按官方口径贴在一轮结束处。 */
    private val turnEndSeq = HashMap<Int, Long>()
    /**
     * DSH 的会话运行状态（mux 的 `api-session/status`）：会话 id → 这一轮还在跑吗。
     *
     * 这是「回合结束」的权威信号：DS Bot 网页端的活动行同样读它
     * （`useSessionStatus(map.get(sessionId)?.running)`）。只看 DS Bot 的 `state.work`
     * 会在它没清干净时把停止键和活动行一直挂在界面上。
     */
    private val sessionRunningById = HashMap<String, Boolean>()
    /** 群聊这一轮：有活跃 round 吗、当前谁在发言（`room-chat` 的 `running` / `speaking`）。 */
    private var groupRoundRunning = false
    private var groupSpeakingId: String? = null
    /** `room-progress` 连续读失败的次数（读不到时别一直抱着上一次的「在跑」）。 */
    private var groupProgressMisses = 0
    /**
     * 本地乐观：刚发出消息、还没等到主机回执。
     *
     * 对齐 dsh-Go（`PendingPrompt` / `_dropEchoed`）：乐观状态**等主机的回执就撤**，
     * 不用时间窗口 —— 单聊的会话状态事件、群聊读到 `room-progress`、回合轮询的结论，
     * 任何一个到了就撤掉它，界面才不会「模型早答完了还挂着状态行」。
     */
    private var optimisticRun = false
    /** 会话事件里最近一次 `turn/start` / `turn/end` 的 seq：最后的边界是哪一边。 */
    private var lastTurnStartSeq = 0L
    private var lastTurnEndSeq = 0L
    private val pendingTools = LinkedHashMap<String, PendingTool>()

    private data class PendingTool(
        val name: String,
        val args: JSONObject,
        val callId: String,
        val time: Long,
    )
    /** 更早历史的游标；null 表示没有更早的了。 */
    private var cursor: ChatCursor? = null
    private var feedJob: Job? = null
    private var reloadJob: Job? = null
    private var statusJob: Job? = null
    private var runningWatchJob: Job? = null
    private var memoryNoticeJob: Job? = null
    private var streamingAttempt: String? = null
    private var streamingText = ""
    private var loadedOnce = false
    /** 这一轮浏览器操作结束后贴的缩略图卡（只活在本次对话里）。 */
    private val browserShots = mutableListOf<ChatEntry>()
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
                    val hub = DshMuxHub.start(runtime)
                    mux = hub
                    sessionId?.let { ensureFollow(it) }
                    hub.hostEvents.collect { event -> handleHostEvent(event) }
                }
            }
        }
    }

    /** 单聊订阅会话事件，把 DS Bot 网页端画在消息流里的 tool/call 事件补回来。 */
    private fun ensureFollow(session: String) {
        if (isGroup) return
        if (followSessionId == session && followStreamId != null) return
        followStreamId?.let { mux?.closeStream(it) }
        followSessionId = session
        toolEvents.clear()
        clearPresented()
        pendingTools.clear()
        lastTurnStartSeq = 0L
        lastTurnEndSeq = 0L
        val args = JSONObject()
            .put(
                // 网关的 `session/follow` 只认 `{request: {...}}`（dsh-Go 的
                // `SessionEventStream.follow` 也是这么包的）；平铺会把整条流打回
                // `gateway/arguments-invalid`，工具事件和产物卡就全都收不到。
                "request",
                JSONObject()
                    .put("address", JSONObject().put("kind", "session").put("sessionId", session))
                    // 快照窗口要够长：交付声明（`present`）只活在会话事件里，DS Bot 的 `chat`
                    // 历史不带它。窗口太短会让几轮之前的产物卡查不到（dsh-Go 的对话窗口也是 200 起）。
                    .put("maxMessages", 200),
            )
        followStreamId = mux?.openStream(
            endpoint = "session/follow",
            args = args,
            onFrame = { frame -> handleFollowFrame(frame) },
            onEnd = { error ->
                DshMuxLog.log("session/follow 结束：${error ?: "closed"}")
                followStreamId = null
            },
        )
    }

    private fun handleFollowFrame(frame: JSONObject) {
        when (frame.optString("type")) {
            "snapshot" -> {
                toolEvents.clear()
                clearPresented()
                pendingTools.clear()
                lastTurnStartSeq = 0L
                lastTurnEndSeq = 0L
                frame.optJSONArray("records")?.let { records ->
                    for (index in 0 until records.length()) {
                        val record = records.optJSONObject(index) ?: continue
                        if (record.optString("type") == "event") {
                            handleSessionEvent(record.optJSONObject("event"))
                        }
                    }
                }
                mergeSessionEvents()
            }
            "event" -> {
                handleSessionEvent(frame.optJSONObject("event"))
                mergeSessionEvents()
            }
        }
    }

    private fun handleSessionEvent(event: JSONObject?) {
        event ?: return
        val type = event.optString("type")
        val data = event.optJSONObject("data") ?: return
        val seq = event.optLong("seq", -1L).takeIf { it >= 0L } ?: return
        val time = event.optLong("time", 0L)
        when (type) {
            "tool/call" -> {
                val name = data.optString("name")
                if (name !in FOLLOW_TOOLS) return
                val callId = data.optString("callId").ifEmpty {
                    data.optJSONObject("message")?.optString("toolCallId").orEmpty()
                }
                if (callId.isEmpty()) return
                pendingTools[callId] = PendingTool(name, toolArguments(data), callId, time)
            }
            "tool/result" -> {
                val message = data.optJSONObject("message")
                val callId = message?.optString("toolCallId").orEmpty().ifEmpty {
                    data.optString("toolCallId").ifEmpty { data.optString("callId") }
                }
                val pending = pendingTools.remove(callId) ?: return
                val isError = data.optBoolean("isError") ||
                    message?.optBoolean("isError") == true ||
                    data.optBoolean("error")
                val resultText = textContent(message?.opt("content"))
                val line = toolEventLine(pending.name, pending.args, resultText, isError, botName) ?: return
                toolEvents[seq] = ChatEntry(
                    id = "tool:$seq",
                    kind = ChatEntryKind.EVENT,
                    text = line.label,
                    seq = seq,
                    timeMs = time,
                    target = line.target,
                    tool = pending.name,
                )
            }
            DELIVERABLES_PRESENTED_EVENT -> {
                val presented = presentedFilesOf(event) ?: return
                if (presented.isEmpty()) return
                val turn = presented.first().turn
                val files = presentedByTurn.getOrPut(turn) { mutableListOf() }
                val known = files.mapTo(HashSet()) { it.path }
                presented.forEach { file ->
                    if (known.add(file.path)) {
                        files += ChatArtifact(path = file.path, description = file.description)
                    }
                }
                presentedSeq.putIfAbsent(turn, seq)
            }
            // 一轮的结束点：产物卡贴在这里（官方网页版也是画在一轮结束处，而不是
            // `present` 调用发生的位置 —— 后面往往还有一段收尾文字）。
            "turn/start" -> lastTurnStartSeq = maxOf(lastTurnStartSeq, seq)
            "turn/end" -> {
                lastTurnEndSeq = maxOf(lastTurnEndSeq, seq)
                val turn = data.optInt("turn", -1)
                if (turn >= 0) turnEndSeq[turn] = seq
            }
        }
    }

    private fun toolArguments(data: JSONObject): JSONObject {
        data.optJSONObject("arguments")?.let { return it }
        val raw = data.optString("arguments")
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    private fun textContent(value: Any?): String {
        val array = value as? JSONArray ?: return (value as? String).orEmpty()
        return buildString {
            for (index in 0 until array.length()) {
                val block = array.optJSONObject(index) ?: continue
                if (block.optString("type") == "text") append(block.optString("text"))
            }
        }.trim()
    }

    /** 每个 turn 的产物卡：有 `turn/end` 就贴在那一轮结束处，否则留在声明的位置。 */
    private fun presentedEntries(): List<ChatEntry> = presentedByTurn.mapNotNull { (turn, files) ->
        if (files.isEmpty()) return@mapNotNull null
        val seq = turnEndSeq[turn] ?: presentedSeq[turn] ?: return@mapNotNull null
        ChatEntry(
            id = "present:$turn",
            kind = ChatEntryKind.ARTIFACTS,
            text = "",
            seq = seq,
            artifacts = files.toList(),
        )
    }

    private fun clearPresented() {
        presentedByTurn.clear()
        presentedSeq.clear()
        turnEndSeq.clear()
    }

    /**
     * 一轮浏览器操作结束后，把页面缩略图贴进对话。
     *
     * 只在内存里留最近 [MAX_BROWSER_SHOTS] 张：它不是历史内容，重新进对话就不该再出现。
     */
    fun addBrowserShot(shot: ChatBrowserShot) {
        browserShots += ChatEntry(
            id = "shot:${shot.timeMs}",
            kind = ChatEntryKind.BROWSER_SHOT,
            text = "",
            timeMs = shot.timeMs,
            shot = shot,
        )
        while (browserShots.size > MAX_BROWSER_SHOTS) browserShots.removeAt(0)
        mergeSessionEvents()
    }

    private fun mergeSessionEvents() {
        if (toolEvents.isEmpty() && presentedByTurn.isEmpty() && browserShots.isEmpty()) return
        _uiState.update { state ->
            val base = state.entries.filterNot {
                it.id.startsWith("tool:") ||
                    it.id.startsWith("present:") ||
                    it.id.startsWith("shot:")
            }
            // follow 的快照覆盖整个会话；只把「已经加载出来的这段历史」里的事件并进去，
            // 否则列表顶部会堆一堆比最早一条消息还老的事件行。
            val oldest = base.mapNotNull { it.seq }.minOrNull()
            fun loaded(entry: ChatEntry) = oldest == null || (entry.seq ?: Long.MIN_VALUE) >= oldest
            // 不能整表排序：本地刚发出的用户气泡还没有 seq（服务端回显会被 `seen` 去重，
            // 要等下一次重载才有真 seq），按 seq 排序会把它甩到列表最后 ——
            // 倒序列表里就是最下面，表现成「大模型的回复跑到了问题上面」。
            // 所以只把事件插进「有 seq 的那段历史」的正确位置，插不进去就放末尾。
            val incoming = (toolEvents.values + presentedEntries()).filter(::loaded)
            // 缩略图卡不属于历史事件（没有 seq），一律排在当前时间线之后：
            // 一张「刚看完的页面」贴在最新一条消息下面才对。
            state.copy(entries = mergeTimeline(base, incoming) + browserShots)
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

            // 会话状态：一轮开始 / 会话回到空闲（dsh-Go `SessionStatusEvent`）。
            is DshHostEvent.Emit -> handleEmitEvent(event)

            else -> Unit
        }
    }

    /**
     * `$events` 里我们关心的广播事件。
     *
     * `api-session/status [sessionId, running]` 是本对话「还在跑吗」的权威答案；
     * `api-session/added` 带整份会话摘要（含 `running`），用来给刚连上时补状态。
     */
    private fun handleEmitEvent(event: DshHostEvent.Emit) {
        val args = event.args
        when (event.event) {
            "api-session/status" -> {
                val id = args.optString(0)
                if (id.isEmpty()) return
                val running = args.optBoolean(1, false)
                sessionRunningById[id] = running
                // 主机说话了：本地乐观到此为止，之后以会话状态为准。
                val mine = sessionId?.takeIf { it.isNotEmpty() } ?: botId
                if (id == mine) optimisticRun = false
                if (id == sessionId) applySessionRunning(running)
            }

            "api-session/added" -> {
                val summary = args.optJSONObject(0) ?: return
                val id = summary.optString("sessionId")
                if (id.isEmpty()) return
                sessionRunningById[id] = summary.optBoolean("running", false)
                if (id == sessionId) applySessionRunning(sessionRunningById.getValue(id))
            }
        }
    }

    /** 会话状态变了：跑起来就刷状态行，停下了立刻收掉停止键和活动行。 */
    private fun applySessionRunning(running: Boolean) {
        if (running) {
            _uiState.update { if (it.running) it else it.copy(running = true, stopVisible = true) }
            refreshBotStatus()
            return
        }
        runningWatchJob?.cancel()
        clearStreaming()
        _uiState.update {
            if (!it.running && !it.stopVisible && it.activity == null) {
                it
            } else {
                it.copy(
                    running = false,
                    stopVisible = false,
                    activity = null,
                    waitingForReply = false,
                )
            }
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
        optimisticRun = true
        val bubble = ChatEntry(
            id = "user:$requestId",
            kind = ChatEntryKind.USER,
            text = text,
            attachments = attachments.map { it.name },
            attachmentItems = attachments.mapNotNull { draft ->
                draft.cachePath?.let { path ->
                    ChatAttachmentItem(draft.name, path, draft.isImage)
                }
            },
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
        _uiState.update { state ->
            state.copy(
                // 没有内容时重新拉：又回到加载态（骨架屏）；已有内容就静默刷新。
                loading = state.entries.isEmpty(),
                error = if (initial) state.error else null,
            )
        }
        viewModelScope.launch {
            runCatching {
                connection.bot(
                    endpoint = if (isGroup) "room-chat" else "chat",
                    payload = JSONObject().put(if (isGroup) "roomId" else "botId", botId),
                )
            }.onSuccess { value ->
                value.optString("sessionId").takeIf { it.isNotEmpty() }?.let {
                    sessionId = it
                    ensureFollow(it)
                }
                // 群聊的 `room-chat` 直接给这一轮的状态：在跑吗、谁在发言
                // （`mobile.js roomChat`：`running` 有活跃 round，`speaking` 是当前发言人）。
                if (isGroup) {
                    applyGroupRound(value)
                    // 打开时这一轮在跑：开启轮询，round 结束后停止键和状态行要自己收。
                    if (groupRoundRunning) watchForRunEnd()
                }
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
                mergeSessionEvents()
                // 刚学到这个 Bot 当前用的 DSH 会话 id：事件流里的请求靠它认领。
                recomputeApprovals()
                refreshBotStatus()
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
                    // 翻到更早的一页后，那一页对应的事件行（工具调用/产物卡）也要补进来。
                    mergeSessionEvents()
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
    /** 记下群聊这一轮的状态（`room-chat` 每次刷新都会带上）。 */
    private fun applyGroupRound(value: JSONObject) {
        groupRoundRunning = value.optBoolean("running", false)
        groupSpeakingId = value.optString("speaking").takeIf { it.isNotEmpty() }
    }

    /**
     * 群聊这一轮的实时状态：`room-progress`（DS Bot 自己的 `GroupReplies` 也每秒读它）。
     *
     * 返回「有 round 在跑吗」。空数组是权威的「没有」——`room-chat` 的 `running` 和它同源，
     * 但只在加载/`room` 事件时才刷新，round 结束后没人再读，界面就会一直停在停止键。
     */
    private suspend fun refreshGroupProgress(connection: DshRemoteApi): Boolean {
        val value = runCatching {
            connection.botValue("room-progress", JSONObject().put("roomId", botId))
        }.getOrNull()
        val parsed = value?.let(::parseGroupProgress)
        if (parsed == null) {
            // 读不到就别一直抱着上一次的「在跑」：连读三次拿不到，按「没在跑」处理。
            groupProgressMisses += 1
            if (groupProgressMisses >= 3) {
                groupRoundRunning = false
                optimisticRun = false
            }
            return groupRoundRunning
        }
        groupProgressMisses = 0
        // 读到引擎的 round 状态了：本地乐观到此为止，之后以它为准。
        optimisticRun = false
        val (running, speaking) = parsed
        groupRoundRunning = running
        groupSpeakingId = speaking ?: groupSpeakingId
        return groupRoundRunning
    }

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
            val workRunning = if (isGroup) {
                // 群聊只认 `room-progress` / `room-chat.running`（两者同源），
                // `work.rooms` 曾经没清干净，会把停止键钉在界面上。
                groupRoundRunning
            } else {
                work?.optJSONObject("bots")?.has(botId) == true
            }
            val running = runningNow(workRunning)
            // 审批：DS Bot 的 state 就是卡片身份；引擎事件流只补提问。
            botApprovals = parseBotApprovals(state).filter { entry ->
                if (isGroup) entry.roomId == botId else entry.botId == botId
            }
            // 群聊等审批时 `room-progress.speaking` 是空的：用等审批的那个成员认头像。
            val groupSpeaker = groupSpeakingId ?: botApprovals.firstOrNull()?.botId
            val activity = parseChatActivity(
                state = state,
                botId = botId,
                sessionId = sessionId,
                isGroup = isGroup,
                running = running,
                groupSpeakingId = groupSpeaker,
            )
            val team = DsBotParser.teamMembers(state)
            val rooms = DsBotParser.teamRooms(state)
            // 输入框上方的待办卡：Bot 自己的提问 / 视图（群聊里 DS Bot 只允许在私聊用）。
            val cardScope = sessionId?.takeIf { it.isNotEmpty() } ?: botId
            val questions = if (isGroup) emptyList() else parseChatQuestions(state, cardScope)
            val views = if (isGroup) emptyList() else parseChatViews(state, cardScope)
            // 密钥卡只在 Bot 自己的对话里出现（DS Bot：只有私聊能弹这张卡）。
            // 答过的留在 dock 里一小会儿，让「保存了 / 允许了 / 取消了」有个落点。
            val secrets = if (isGroup) {
                emptyList()
            } else {
                val now = System.currentTimeMillis()
                parseChatSecrets(state, botId).filter { request ->
                    request.pending || (request.settledAt > 0L && now - request.settledAt < secretCardLingerMs)
                }
            }
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
            _uiState.update {
                it.copy(
                    botStatus = role.takeIf { name -> name.isNotEmpty() }
                        ?: if (isMain) "主 Bot" else "空闲",
                    botIsMain = isMain,
                    running = running,
                    stopVisible = running,
                    activity = activity,
                    teamMembers = team,
                    teamRooms = rooms,
                    pendingQuestions = questions,
                    pendingViews = views,
                    pendingSecrets = secrets,
                )
            }
            recomputeApprovals()
        }
    }

    /**
     * 这一轮还在跑吗。
     *
     * 优先用 DSH 的会话状态（权威）；还没收到过就退回 DS Bot 的 `work`；
     * 刚发出消息的一小段时间内本地的乐观状态压过一切（别让按钮闪）。
     */
    private fun runningNow(workRunning: Boolean): Boolean {
        if (isGroup) return groupRunIsRunning(optimisticRun, groupRoundRunning)
        val key = sessionId?.takeIf { it.isNotEmpty() } ?: botId
        return botRunIsRunning(
            optimistic = optimisticRun,
            // 群聊没有单独的 sessionId（群的会话 id 就是 room id）。
            sessionStatus = sessionRunningById[key],
            // 会话事件里最后一条回合边界是 turn/end → 这一轮已经结束。
            lastTurnEnded = lastTurnEndSeq > lastTurnStartSeq,
            workRunning = workRunning,
        )
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

    /**
     * 回答 dock 里的提问卡（DS Bot `ask_user`）：`answer-question`。
     *
     * 答案是「用户下一条消息」——DS Bot 会把它作为 followup 发进会话。
     */
    fun answerChatQuestion(
        card: ChatQuestionCard,
        selected: List<String>,
        custom: String = "",
        onDone: (String?) -> Unit = {},
    ) {
        val answer = JSONObject()
            .put("selected", JSONArray().apply { selected.forEach(::put) })
            .also { if (custom.isNotBlank()) it.put("custom", custom.trim()) }
        submitPendingCard(
            endpoint = "answer-question",
            payload = JSONObject()
                .put("sessionId", sessionId ?: botId)
                .put("questionId", card.id)
                .put("answer", answer),
            onDone = onDone,
        )
        // 提交成功就本地收卡，别等下一次 state。
        _uiState.update { it.copy(pendingQuestions = it.pendingQuestions.filterNot { q -> q.id == card.id }) }
    }

    /** 提交一张视图卡：`view-reply {sessionId, viewId, action, values}`。 */
    fun answerChatView(
        card: ChatViewCard,
        action: String,
        values: JSONObject = JSONObject(),
        onDone: (String?) -> Unit = {},
    ) {
        submitPendingCard(
            endpoint = "view-reply",
            payload = JSONObject()
                .put("sessionId", sessionId ?: botId)
                .put("viewId", card.id)
                .put("action", action)
                .put("values", values),
            onDone = onDone,
        )
        _uiState.update { it.copy(pendingViews = it.pendingViews.filterNot { v -> v.id == card.id }) }
    }

    /** 关掉一张不用答的视图卡（DS Bot `dismiss-view`）。 */
    fun dismissChatView(card: ChatViewCard) {
        val connection = client ?: return
        _uiState.update { it.copy(pendingViews = it.pendingViews.filterNot { v -> v.id == card.id }) }
        viewModelScope.launch {
            runCatching {
                connection.bot(
                    "dismiss-view",
                    JSONObject().put("sessionId", sessionId ?: botId),
                )
            }
            refreshBotStatus()
        }
    }

    /**
     * 填一把密钥：`secret-set {requestId, value, scope}`。
     *
     * 值只往引擎送一次，界面上不留、也不回显（DS Bot 连模型都看不到）。
     * [onlyThisBot] 对应 DS Bot 的 `scope: 'bot'`，否则是 `'all'`。
     */
    fun submitChatSecret(
        request: ChatSecretRequest,
        value: String,
        onlyThisBot: Boolean,
        onDone: (String?) -> Unit = {},
    ) {
        val trimmed = value.trim()
        if (trimmed.length < SECRET_MIN_LENGTH) {
            onDone("密钥至少 $SECRET_MIN_LENGTH 个字符")
            return
        }
        val connection = client ?: return
        _uiState.update { it.copy(answeringPending = true) }
        viewModelScope.launch {
            val error = runCatching {
                connection.bot(
                    "secret-set",
                    JSONObject()
                        .put("requestId", request.id)
                        .put("value", trimmed)
                        .put("scope", if (onlyThisBot) "bot" else "all"),
                )
            }.exceptionOrNull()?.let { describeChatError(it) }
            _uiState.update { it.copy(answeringPending = false) }
            refreshBotStatus()
            onDone(error)
        }
    }

    /** 允许这个 Bot 用已经存过的密钥：`secret-allow {requestId}`。 */
    fun allowChatSecret(request: ChatSecretRequest, onDone: (String?) -> Unit = {}) {
        val connection = client ?: return
        _uiState.update { it.copy(answeringPending = true) }
        viewModelScope.launch {
            val error = runCatching {
                connection.bot("secret-allow", JSONObject().put("requestId", request.id))
            }.exceptionOrNull()?.let { describeChatError(it) }
            _uiState.update { it.copy(answeringPending = false) }
            refreshBotStatus()
            onDone(error)
        }
    }

    /** 撤回一张密钥卡：`secret-cancel {requestId}`（Bot 会收到一条「用户取消了」）。 */
    fun cancelChatSecret(request: ChatSecretRequest, onDone: (String?) -> Unit = {}) {
        val connection = client ?: return
        _uiState.update { it.copy(answeringPending = true) }
        viewModelScope.launch {
            val error = runCatching {
                connection.bot("secret-cancel", JSONObject().put("requestId", request.id))
            }.exceptionOrNull()?.let { describeChatError(it) }
            _uiState.update { it.copy(answeringPending = false) }
            refreshBotStatus()
            onDone(error)
        }
    }

    private fun submitPendingCard(endpoint: String, payload: JSONObject, onDone: (String?) -> Unit) {
        val connection = client ?: return
        _uiState.update { it.copy(answeringPending = true) }
        viewModelScope.launch {
            val error = runCatching { connection.bot(endpoint, payload) }
                .exceptionOrNull()
                ?.let { describeChatError(it) }
            _uiState.update { it.copy(answeringPending = false) }
            refreshBotStatus()
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
        optimisticRun = false
        // 本地先认「已经停下」：引擎的会话状态事件随后会确认，不让停止键闪回来。
        sessionId?.let { sessionRunningById[it] = false }
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
            var lastRunning: Boolean? = null
            var lastStatusAt = 0L
            while (idlePolls < 2) {
                delay(1_000)
                // 群聊只读 `room-progress`（很小）；`state` 太重，不能每秒拉 ——
                // 手机上的引擎被高频轮询拖垮过，round 反而推不动。
                val running = if (isGroup) {
                    refreshGroupProgress(connection)
                } else {
                    val state = runCatching { connection.bot("state") }.getOrNull() ?: continue
                    runningNow(state.optJSONObject("work")?.optJSONObject("bots")?.has(botId) == true)
                }
                if (running) {
                    idlePolls = 0
                    // 状态行（工具文案/计时）不需要每秒刷；3 秒一次够了。
                    val now = System.currentTimeMillis()
                    if (lastRunning != true || now - lastStatusAt >= 3_000L) {
                        lastStatusAt = now
                        refreshBotStatus()
                    }
                } else {
                    idlePolls += 1
                }
                lastRunning = running
            }
            optimisticRun = false
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
        val seqValue = item.optLong("seq", -1L).takeIf { it >= 0L }
        return when (item.optString("kind")) {
            "user" -> text.takeIf { it.isNotBlank() }
                ?.let {
                    ChatEntry(
                        id = if (rpcId != null) "user:$rpcId" else id,
                        kind = ChatEntryKind.USER,
                        text = it,
                        timeMs = time,
                        seq = seqValue,
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
                        seq = seqValue,
                    )
                }

            "answer" -> text.takeIf { it.isNotBlank() }
                ?.let { ChatEntry(id, ChatEntryKind.BOT, it, botName, timeMs = time, seq = seqValue) }

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
                    seq = seqValue,
                )
            }

            else -> null
        }
    }

    override fun onCleared() {
        feedJob?.cancel()
        reloadJob?.cancel()
        followStreamId?.let { mux?.closeStream(it) }
    }

    companion object {
        /** 事件流缓存的上限：同时等应答的提问一般就一个对话，多出来的一定是漏掉的 cancel。 */
        private const val HOST_PENDING_CAP = 32
        /** 对话里最多留几张浏览器缩略图卡（只活在本次对话）。 */
        private const val MAX_BROWSER_SHOTS = 30
        private const val STOP_GRACE_NS = 2_000_000_000L
        /** 发送确认前先用本地 running 顶住状态行；之后完全交给 state.work。 */
        /** 群聊里刚发出消息后，等 round 建起来的最长时间（超过就不再乐观显示）。 */

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
