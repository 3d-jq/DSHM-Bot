package com.dshm.bot.ui.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dshm.bot.R
import com.dshm.bot.data.remote.BotFeedEvent
import com.dshm.bot.data.remote.DsBotCapabilities
import com.dshm.bot.data.remote.DsBotParser
import com.dshm.bot.domain.model.ChatApproval
import com.dshm.bot.data.remote.DELIVERABLES_PRESENTED_EVENT
import com.dshm.bot.data.remote.presentedFilesOf
import com.dshm.bot.data.remote.isArtifactPath
import com.dshm.bot.data.remote.ChatTransport
import com.dshm.bot.data.remote.DshRemoteApi
import com.dshm.bot.data.remote.parseChatPage
import com.dshm.bot.data.remote.ChatCursor
import com.dshm.bot.data.remote.DshMuxHub
import com.dshm.bot.data.remote.DshMux
import com.dshm.bot.data.remote.DshMuxLog
import com.dshm.bot.data.remote.DshHostEvent
import com.dshm.bot.data.remote.DshQuestionRequest
import com.dshm.bot.data.remote.DshQuestionAnswer
import com.dshm.bot.data.remote.answersToJson
import com.dshm.bot.data.remote.BotApproval
import com.dshm.bot.data.remote.ChangesSummary
import com.dshm.bot.data.remote.parseChangesSummary
import com.dshm.bot.data.remote.parseBotApprovals
import com.dshm.bot.data.remote.SpeechRepository
import com.dshm.bot.data.remote.SessionEventLog
import com.dshm.bot.data.remote.parseSessionEventFrame
import com.dshm.bot.data.remote.TimelineRequest
import com.dshm.bot.data.remote.TimelineRow
import com.dshm.bot.data.remote.TimelineFeedFrame
import com.dshm.bot.data.remote.TimelineRowPayload
import com.dshm.bot.data.remote.TimelineSnapshot
import com.dshm.bot.data.remote.parseTimelineFeedFrame
import com.dshm.bot.data.runtime.RuntimeEndpoint
import com.dshm.bot.data.notify.ChatNotifier
import com.dshm.bot.data.settings.SharedPrefsSettingsRepository
import com.dshm.bot.domain.model.DshGoPreferences
import com.dshm.bot.service.PhoneControlSession
import com.dshm.bot.service.PhoneOverlay
import com.dshm.bot.service.DshmAccessibilityService
import com.dshm.bot.ui.home.RoomMemberUi
import com.dshm.bot.ui.home.TeamRoomUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

/**
 * 对话里的一行。对齐 DS Bot 网页版 `chat` items 的全部 kind
 * （`parts.js displayItems`：user / bot / answer / view / event / divider）。
 */
enum class ChatEntryKind {
    USER, BOT, EVENT, ARTIFACTS, BROWSER_SHOT,

    /** `divider`：一段会话的起点（换对话/换目录后），画一条时间分隔线。 */
    DIVIDER,

    /** `view`：只读视图卡（`show_view` 的非交互回放），网页版用 `WidgetCell` 画。 */
    VIEW,

    /** `answer`：用户回答过的提问（`AnsweredCell`：问题 + 选了哪些）。 */
    ANSWERED,

    /**
     * `inbox` 行（timeline 投影，Phase 1b）：排队（`next-turn`）/ 插话（`next-step`）
     * 中的消息，可撤回 / 改插话（`session/updateQueue`）。认领后由 `user` 行接替。
     */
    PENDING,

    /**
     * `changes` 行（timeline 投影，Phase 2）：这一轮改动的文件卡。摘要不在行里，
     * 由 ViewModel 按 `(sessionId, seq)` 走 DSH `/api/changes.summary` 按需拉取，
     * 拉到才出行（[ChatEntry.changes]）。
     */
    CHANGES,
}

/** 历史里「用户回答过的提问」。 */
data class ChatAnsweredQuestion(
    val question: String,
    /** 选中的选项文案（含自定义输入）。 */
    val answers: List<String>,
)

/**
 * 历史里「用户回答过的视图卡」（`answer` item 的 `views` + `answers`）。
 *
 * 网页版 `AnsweredView` → `WidgetCard(settled = true, answer)`：把那张卡连**当时的填写**
 * 只读地回放一遍。[values] 是插件校验后存下来的提交值：
 * `selected`（选项文案）/ `fields`（表单字段）/ `confirmed`（确认与否）/ `inputs`（卡内输入）。
 */
data class ChatAnsweredView(
    val card: ChatViewCard,
    /** 提交按钮的组件 id（`options` / `form` / `confirm` / `submit` 之一）。 */
    val action: String,
    val values: JSONObject,
)

/**
 * 一条过长的 Bot 消息：正文被折起，完整内容在 `whole.file` 里。
 * 网页版用「显示全部」按 `file-text` 读出来（`channel.js` 的 `file-text`）。
 */
data class ChatWholePost(
    val name: String,
    val length: Int,
    val file: JSONObject,
)

/**
 * 群聊暂停时被「寄存」的一次中转（`state.rooms[roomId].paused`）。
 *
 * 群聊里 Bot 互相喊的次数超过上限后，这一轮会停在半路等用户：插件把等待的中转记在
 * `room.paused` 里，只有 `room-continue` 会放行（`groups.js continueRoom`）。
 */
data class ChatPausedMember(
    val id: String,
    val name: String,
    val note: String? = null,
)

/** 一个可选的模型（`state.models[]`）。 */
data class ChatModelChoice(
    val ref: String,
    val name: String,
    val provider: String? = null,
    val needsKey: Boolean = false,
)

/**
 * 「先给我选个模型」提示（网页版 `ModelChoiceDock`）。
 *
 * DS Bot 在 Bot 还没有模型、或原来的模型已经不在模型列表里时，会在输入框上方
 * 出一张卡：Bot 说一句话 + 一排模型 chip，点一下就是 `update-bot {model}`。
 * 我们的新 Bot 如果没有走详情页选模型，之前会一直卡着没人干活。
 */
data class ChatModelPrompt(
    /** `pick`（还没选过）/ `lost`（模型没了）/ `key`（缺 API Key）/ `empty`（一个模型都没有）。 */
    val kind: String,
    val message: String,
    val choices: List<ChatModelChoice> = emptyList(),
)

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
    /** 这些产物是 `present` 声明的（「交付了」）还是兜底推出来的（「生成了」）。 */
    val artifactsDelivered: Boolean = false,
    /** 工具事件的目标（Bot / 群聊名字）；界面用它在团队里认头像。 */
    val target: String? = null,
    /** 产生这条事件的 DS Bot 工具名（`message_bot` / `remember`…），界面据此选图标。 */
    val tool: String? = null,
    /** 浏览器缩略图卡的内容（[ChatEntryKind.BROWSER_SHOT]）。 */
    val shot: ChatBrowserShot? = null,
    /** 只读视图卡（[ChatEntryKind.VIEW]）。 */
    val view: ChatViewCard? = null,
    /** 回答过的提问（[ChatEntryKind.ANSWERED]）。 */
    val answered: List<ChatAnsweredQuestion> = emptyList(),
    /** 回答过的视图卡（[ChatEntryKind.ANSWERED]）。 */
    val answeredView: ChatAnsweredView? = null,
    /** 用户消息里的图片数量（历史回读只有计数，没有图）。 */
    val images: Int = 0,
    /** 长文消息（`whole`）：正文折起，完整内容在附件里。 */
    val whole: ChatWholePost? = null,
    /** [ChatEntryKind.PENDING] 行的队列槽位（`next-turn` 排队 / `next-step` 插话）。 */
    val pendingSlot: String? = null,
    /** [ChatEntryKind.PENDING] 行对应的发送 requestId（撤回 / 改插话回传给 `session/updateQueue`）。 */
    val pendingRequestId: String? = null,
    /**
     * [ChatEntryKind.PENDING] 行的来源：`user` = 用户自己排的（可撤回）；
     * 群聊里成员/系统项为其它值（只显示、不给按钮）。
     */
    val pendingSourceKind: String? = null,
    /** [ChatEntryKind.PENDING] 行在群聊里属于谁（显示用）。 */
    val pendingName: String? = null,
    /** 群聊 live 行（round 进行中的回复占位）：`live===true` 画「发言中」态。 */
    val live: Boolean = false,
    /**
     * [ChatEntryKind.CHANGES]：这一轮改动的摘要素材（宿主 `GET /api/changes.summary`）。
     * null 表示还没拉到（此行也不会出现在界面上，等拉到重建后再出）。
     */
    val changes: ChangesSummary? = null,
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
    // 交付声明进对话时间线：轮内只有这一行表示「正在交付/已交付」
    // （产物卡本身等轮闭合才出，对齐官方网页版）。
    "present",
    // 「看了一眼」类的动作（看团队 / 看密钥 / 翻自己的记录）、浏览器动作、更新记忆
    // 都不进对话时间线：活动状态栏已经在显示，每来一行只会把对话冲乱。
)

private data class PendingArtifactCall(
    val name: String,
    val args: JSONObject,
    val turn: Int,
    val seq: Long,
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
    /** 群聊被中转上限暂停：等用户点「继续」（`state.rooms[].paused`）。 */
    val roomPaused: List<ChatPausedMember> = emptyList(),
    /** 群成员的实时状态（`state.work`）：输入框上方的成员 dock 和成员行用它。 */
    val memberStatus: Map<String, RoomMemberStatus> = emptyMap(),
    /** 正在提交「继续」：按钮禁用，避免重复点。 */
    val roomContinuing: Boolean = false,
    /** 需要用户先选模型（`ModelChoiceDock`）；不需要时为 null。 */
    val modelPrompt: ChatModelPrompt? = null,
    /**
     * 已进入宿主队列、还没被认领的消息（`inbox` 行）：排在输入框上方的队列条里，
     * **不进时间线**（官方 web 的时间线也不出现排队行）。
     */
    val pendingQueue: List<ChatEntry> = emptyList(),
    /** 一次性的轻提示（例如「已让 X 停下」）：几秒后自动消失。 */
    val actionNotice: String? = null,
)

/**
 * 一个 Bot（或群聊）的真实对话：
 * - 历史：`/api/bot` 的 `chat`（群聊用 `room-chat`）
 * - 发送：`send`，sessionId 用 Bot id（群聊用 room id）
 * - 实时：`/api/bot/feed` 的 `message` / `stream` / `room`
 *
 * 数据形状对齐 ds-bot 的 `parts.js`（displayItems）与 `feed.js`。
 */
class BotChatViewModel internal constructor(
    private val botId: String,
    private val botName: String,
    private val isGroup: Boolean,
    private val endpoint: RuntimeEndpoint?,
    appContext: Context?,
    private val dependencies: BotChatDependencies,
) : ViewModel() {

    /** 生产入口：依赖全部按端点从 `DshClients` 造。 */
    constructor(
        botId: String,
        botName: String,
        isGroup: Boolean,
        endpoint: RuntimeEndpoint?,
        appContext: Context? = null,
    ) : this(botId, botName, isGroup, endpoint, appContext, BotChatDependencies.production(endpoint))

    private val notifyContext: Context? = appContext
    private val settingsRepository = notifyContext?.let { SharedPrefsSettingsRepository.getInstance(it) }
    private var notifyEnabled = true
    private var notifyPrefs = DshGoPreferences()

    init {
        settingsRepository?.let { repo ->
            viewModelScope.launch {
                combine(
                    repo.observeNotificationsEnabled(),
                    repo.observeDshGoPreferences(),
                ) { enabled, prefs -> enabled to prefs }
                    .collect { (enabled, prefs) ->
                        notifyEnabled = enabled
                        notifyPrefs = prefs
                    }
            }
        }
    }

    private val client: DshRemoteApi? = dependencies.remote
    private val transport: ChatTransport? = dependencies.transport
    /** 语音输入：页面/输入框只依赖接口。 */
    val speechRepository: SpeechRepository? = dependencies.speech
    /** 自己发过的附件（`rpcId → 本地文件`）：认领后把缩略图贴回去（附件字节缓存保留）。 */
    private val attachmentCache = appContext?.let { ChatAttachmentCache(it, botId) }
    private val sentAttachments = java.util.concurrent.ConcurrentHashMap<String, List<ChatAttachmentItem>>(
        attachmentCache?.load().orEmpty(),
    )
    private val _uiState = MutableStateFlow(
        BotChatUiState(
            // 行管线首屏：等 timeline 快照到达（宿主投影是唯一来源）。
            loading = true,
            engineOff = endpoint == null,
        ),
    )
    val uiState: StateFlow<BotChatUiState> = _uiState.asStateFlow()

    /**
     * 流式回复走独立侧通道（对齐 DeepSearch `_streamingById`）：
     * 高频 token 不写 `_uiState`，只有流式气泡那一小棵树订阅它，
     * 顶栏 / 输入框 / 历史气泡不会因为每个 chunk 重组。
     */
    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    /** 双路 flush：时间节流 + 换行快车道；状态跨 token 保留，不随重组重置。 */
    private data class StreamFlush(
        var lastFlushMs: Long = 0L,
        var lastFlushedLen: Int = 0,
        var pending: String? = null,
        var trailingJob: Job? = null,
    )
    private val streamFlush = StreamFlush()

    private var sessionId: String? = null
    /** 最近一次 `state.work.approvals` 里属于本对话的条目 —— 卡片的显示身份。 */
    private var botApprovals: List<BotApproval> = emptyList()
    /** 引擎在等用户回应的提问（eventId → 请求）。 */
    private val hostQuestions = LinkedHashMap<String, DshQuestionRequest>()
    /** 当前 mux 连接的 clientId；反问作答要带回它。 */
    private var hostClientId: String? = null
    private var mux: DshMux? = null

    // region ---- timeline 行管线（唯一管线：快照 + feed 增量 + 本地乐观 echo）----
    // entries 经 TimelineRowMapping 从行缓冲映射而来（TimelineRowMapping.kt）。
    // 旧管线五路拼装（load/feed message/follow/事件行拼装/历史缓存）已于 2026-11-14 删除。
    private var parityTimelineJob: Job? = null
    /** 已见到的最大服务端 seq（feed 断线重连的 since 重放游标）。 */
    private var paritySeq = 0L
    private var paritySnapshot: TimelineSnapshot? = null
    private var paritySessionId: String? = null
    /** 服务端投影的行缓冲（key → row）；群聊保持宿主位置序，单聊按 seq 归位。 */
    /**
     * 时间线**唯一事实源**（对齐 dsh-Go `_log`）：按 (part, seq) 有序、幂等写入，
     * 显示永远从它全量重算 —— 不再靠「帧到达顺序」维护显示缓冲。
     */
    private val parityLog = TimelineEventLog()
    /**
     * 四期 4a：当前会话的 **durable 事件日志**（原始事件 + 连续 seq 游标）。
     *
     * 这是完整性判据的地基：宿主把每个 `session/event` 连同连续 seq 转发过来，
     * 这里按 dsh-Go 的规则判定重复/连续/缺口；缺口就向宿主 `events` 端点补洞。
     * 4a 不改渲染（仍走 [parityLog] 行管线），只保证「事件层」永远能自愈；
     * 4b 再把折叠搬到客户端，让它成为显示的唯一事实源。
     */
    private val sessionEvents = SessionEventLog()
    /** 正在补洞（防同一缺口并发重复请求）。 */
    private var sessionEventsFilling = false
    /** 影子比对「一致」的限频时刻（不一致每次都记）。 */
    private var lastShadowOkAt = 0L
    /** 影子比对「跳过」的限频时刻（跳过也要记，否则分不清「跳过」与「失败」）。 */
    private var lastShadowSkipAt = 0L
    /** earlier 分页游标（timeline 响应的 cursor），null = 没有更早的了。 */
    private var parityCursor: JSONObject? = null
    private var parityLoadingEarlier = false
    /**
     * 渲染键映射（数据键 → 渲染键）：user 行认领本地 echo 后渲染键保留 `p:<requestId>`，
     * LazyColumn 元素原地过渡不换 key（设计 §2 规则 1）。
     */
    private val parityRenderKeys = HashMap<String, String>()
    /** 本地乐观 echo 行（key = `p:<requestId>`）；认领（user 行）/ 失败时摘掉。 */
    private val pendingEchoRows = LinkedHashMap<String, TimelineRow>()
    /** echo 行的本地附件（缓存路径），认领后搬给 user 行，图片预览不闪。 */
    private val pendingEchoAttachments = HashMap<String, List<ChatAttachmentItem>>()
    /** 群聊帧的上一帧 running（true→false 边沿 = 本轮收口，触发快照重拉对齐新行）。 */
    private var parityRoundRunning = false
    /** 诊断日志限频：同一会话两次「行应用」日志的最小间隔。 */
    private var lastParityLogAt = 0L

    /**
     * 每轮改动摘要缓存（`seq` → 摘要）。键是 `changes` 行的 seq（即通告事件的
     * seq），与 `GET /api/changes.summary?seq=` 的坐标一致。
     *
     * 只缓存**成功**的结果：以前失败也写 null 并「同一 seq 只拉一次」，那一轮的卡
     * 就再也不出现，只有重开界面（新 VM）才好——用户实测的「代码产物卡不持久化」。
     * 现在失败按 seq 限次重试（[CHANGES_SUMMARY_ATTEMPTS]）。
     */
    private val changesSummaries = HashMap<Long, ChangesSummary>()
    /** 正在拉取的 seq（防同一 seq 并发重复请求）。 */
    private val changesFetching = HashSet<Long>()
    /** 每个 seq 已经尝试过几次（成功即停；失败限次，避免一直打端点）。 */
    private val changesAttempts = HashMap<Long, Int>()
    // endregion

    private val sessionRunningById = HashMap<String, Boolean>()
    /** 群聊这一轮：有活跃 round 吗、当前谁在发言（帧 `running`/`speaking` + room-progress 兜底）。 */
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
    /** 这一轮是不是用户按了停止：结束通知要算「被停下」，不是「回复好了」。 */
    private var userStoppedRun = false

    private var feedJob: Job? = null
    private var statusJob: Job? = null
    private var runningWatchJob: Job? = null
    private var memoryNoticeJob: Job? = null
    /** 一次性轻提示（[BotChatUiState.actionNotice]）的自动收回任务。 */
    private var actionNoticeJob: Job? = null
    private var streamingAttempt: String? = null
    private var streamBuffer = ""
    /** 这一轮浏览器操作结束后贴的缩略图卡（只活在本次对话里）。 */
    private val browserShots = mutableListOf<ChatEntry>()
    private val browserShotStore = appContext?.let { BrowserShotStore(it, botId) }
    private var browserShotSessionId: String? = null

    /**
     * 发出了但还没等到行流认领的 requestId。
     *
     * user 行（带 requestId）认领时摘掉；一轮结束时还有剩，说明行流漏投了 ——
     * 重放一次快照把正主换上来。
     */
    private val unclaimedRequests = LinkedHashSet<String>()

    init {
        if (client != null) {
            // 宿主投影是唯一管线：hello 的 StateFlow 会先发一次当前值（通常 null），
            // 这一发就是首屏快照；之后 hello 变化（换会话/引擎重启）时重拉一次。
            viewModelScope.launch { DsBotCapabilities.hello.collect { startTimelinePipeline() } }
            viewModelScope.launch { repairModelIfNeeded() }
            refreshBotStatus()
            feedJob = viewModelScope.launch {
                client.feed().collect { event -> handleFeed(event) }
            }
            // 主机事件流（`$events`）只用来显示审批/提问/密钥卡（宿主级交互状态，
            // 不走会话行流，设计 §5）。注入的 mux（JVM 单测）优先；生产走 DshMuxHub。
            val injectedMux = dependencies.mux
            if (injectedMux != null) {
                viewModelScope.launch {
                    injectedMux.start()
                    mux = injectedMux
                    injectedMux.hostEvents.collect { event -> handleHostEvent(event) }
                }
            } else {
                endpoint?.let { runtime ->
                    viewModelScope.launch {
                        val hub = DshMuxHub.start(runtime)
                        mux = hub
                        hub.hostEvents.collect { event -> handleHostEvent(event) }
                    }
                }
            }
        }
    }

    /**
     * 一轮浏览器操作结束后，把页面缩略图贴进对话。
     *
     * 只在内存里留最近 [MAX_BROWSER_SHOTS] 张：它不是历史内容，重新进对话就不该再出现。
     */
    fun addBrowserShot(shot: ChatBrowserShot) {
        browserShots += ChatEntry(
            id = "shot:${shot.timeMs}:${shot.url.hashCode()}",
            kind = ChatEntryKind.BROWSER_SHOT,
            text = "",
            timeMs = shot.timeMs,
            shot = shot,
        )
        browserShotStore?.add(browserShotSessionId ?: sessionId ?: botId, shot, null)
        while (browserShots.size > MAX_BROWSER_SHOTS) browserShots.removeAt(0)
        // 缩略图拼在行管线 entries 的尾部（1c 换贴 turntail 行 seq）。
        rebuildParityEntries()
    }

    private fun loadBrowserShots(currentSession: String) {
        if (browserShotSessionId == currentSession) return
        browserShotSessionId = currentSession
        browserShots.clear()
        browserShotStore?.load(currentSession)?.forEach { stored ->
            browserShots += ChatEntry(
                id = "shot:${stored.shot.timeMs}:${stored.shot.url.hashCode()}",
                kind = ChatEntryKind.BROWSER_SHOT,
                text = "",
                timeMs = stored.shot.timeMs,
                seq = stored.seq,
                shot = stored.shot,
            )
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
    private fun canNotify(kind: ChatNotifier.Kind): Boolean = notifyEnabled && when (kind) {
        ChatNotifier.Kind.DONE -> notifyPrefs.notifyTurnDone
        ChatNotifier.Kind.ERROR -> notifyPrefs.notifyError
        ChatNotifier.Kind.APPROVAL -> notifyPrefs.notifyApproval
        ChatNotifier.Kind.QUESTION -> notifyPrefs.notifyQuestion
    }

    private fun pushNotification(kind: ChatNotifier.Kind, title: String, text: String) {
        val context = notifyContext ?: return
        if (!canNotify(kind)) return
        ChatNotifier.notify(context, botId, kind, title, text)
    }

    private fun notificationPreview(text: String): String {
        val clean = text.replace('\n', ' ').trim()
        return if (clean.length <= 120) clean else clean.take(120) + "…"
    }

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
        refreshTimelineSnapshot(reset = false)
    }

    /** 发一条消息：用户气泡立刻上屏，服务端回显按 requestId 跳过。 */
    /**
     * @param steer true = 手机操控悬浮条「插话」：消息插进**当前正在跑的回合**
     *   （`session/prompt` 的 `mode=steer`），而不是排队等下一轮。
     *   注意不能走 [PhoneControlSession.beginTurn]，否则会清掉用户刚按的「停止」。
     */
    fun send(raw: String, attachments: List<ChatAttachmentDraft> = emptyList(), steer: Boolean = false) {
        val connection = client ?: return
        val text = raw.trim()
        if ((text.isEmpty() && attachments.isEmpty()) || _uiState.value.sending) return
        // 新一轮指令：清掉上一轮手机操控留下的“停止/暂停”状态和回合标记。
        // 插话不算新一轮，不能清「停止」。
        if (!steer) {
            val phoneOwner = notifyContext?.getSharedPreferences("dshm_settings", Context.MODE_PRIVATE)
                ?.getString("phone_control_bot_id", null)
            if (phoneOwner == null || phoneOwner == botId) PhoneControlSession.beginTurn()
        }
        val requestId = UUID.randomUUID().toString()
        optimisticRun = true
        userStoppedRun = false
        unclaimedRequests.add(requestId)
        // 乐观 echo 行：数据键 `p:<requestId>`（设计 §4），本地附件字节预览留在
        // echo 行上（官方「本地字节直到回显」）。认领由 user 行的 requestId 完成
        // （渲染键保留 p: 前缀，元素原地过渡）。
        val attachmentItems = attachments.mapNotNull { draft ->
            draft.cachePath?.let { path -> ChatAttachmentItem(draft.name, path, draft.isImage) }
        }
        if (attachmentItems.isNotEmpty()) {
            // 引擎历史只给「N 张附件」，靠这张表才能把缩略图贴回去（认领后仍生效）。
            sentAttachments[requestId] = attachmentItems
            attachmentCache?.save(sentAttachments)
        }
        pendingEchoRows[echoKeyOf(requestId)] = echoRowOf(requestId, text, attachmentItems, attachments)
        pendingEchoAttachments[requestId] = attachmentItems
        _uiState.update { state -> state.copy(sending = true, error = null) }
        rebuildParityEntries()
        viewModelScope.launch {
            runCatching {
                if (attachments.isEmpty()) {
                    connection.bot(
                        endpoint = "send",
                        payload = JSONObject()
                            .put("sessionId", botId)
                            .put("requestId", requestId)
                            .put("text", text)
                            .apply { if (steer) put("mode", "steer") },
                    )
                } else {
                    val session = sessionId ?: botId
                    val channel = transport ?: error("本机引擎未运行")
                    val receipts = attachments.map { draft ->
                        channel.uploadAttachment(session, draft.name, draft.bytes)
                    }
                    channel.prompt(
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
                unclaimedRequests.remove(requestId)
                pendingEchoRows.remove(echoKeyOf(requestId))
                pendingEchoAttachments.remove(requestId)
                rebuildParityEntries()
                _uiState.update { state ->
                    state.copy(
                        sending = false,
                        waitingForReply = state.waitingForReply,
                        running = state.running,
                        error = describeChatError(failure),
                    )
                }
            }
        }
    }

    /**
     * 往前翻一页更早的历史：timeline cursor（`beforeSeq`）分页。单聊行按 seq 排序
     * 自然插前；群聊是位置序，新页的行前插进行缓冲（key=身份，重放幂等）。
     */
    fun loadEarlier() {
        val connection = client ?: return
        val cursor = parityCursor ?: return
        if (parityLoadingEarlier) return
        parityLoadingEarlier = true
        rebuildParityEntries()
        viewModelScope.launch {
            val request = TimelineRequest(
                botId = botId.takeIf { !isGroup },
                roomId = botId.takeIf { isGroup },
                cursor = cursor,
            )
            runCatching { connection.timeline(request) }
                .onSuccess { snapshot ->
                    if (snapshot == null) {
                        parityLoadingEarlier = false
                        rebuildParityEntries()
                        return@onSuccess
                    }
                    parityCursor = snapshot.cursor
                    // 更早的一页整页前插（顺序由宿主给的显示序决定，不跨 part 排序）。
                    parityLog.prependPage(snapshot.rows)
                    snapshot.rows.forEach { row -> absorbEchoRelationsOf(row) }
                    parityLoadingEarlier = false
                    rebuildParityEntries(hasEarlier = snapshot.cursor != null)
                    logRowApplication("earlier", snapshot.rows)
                }
                .onFailure {
                    parityLoadingEarlier = false
                    rebuildParityEntries()
                }
        }
    }

    /**
     * feed 的 room/hello/revision 触发的重拉 = seq 游标重放：断线重连丢的增量
     * 由宿主行缓冲按 since 补齐（设计 §3 的断线重连协议）。
     */
    private fun scheduleReload() {
        if (client == null) return
        refreshTimelineSnapshot(reset = false)
    }

    private fun handleFeed(event: BotFeedEvent) {
        val data = runCatching { JSONObject(event.data) }.getOrNull() ?: return
        when (event.event) {
            "stream" -> {
                if (data.optString("botId") != botId) return
                when (data.optString("phase")) {
                    "start" -> {
                        streamingAttempt = data.optString("attemptId").ifEmpty { "stream" }
                        streamBuffer = ""
                        streamFlush.trailingJob?.cancel()
                        streamFlush.trailingJob = null
                        streamFlush.lastFlushMs = 0L
                        streamFlush.lastFlushedLen = 0
                        streamFlush.pending = null
                        _streamingText.value = ""
                        _uiState.update {
                            it.copy(
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
                            streamBuffer += chunk
                            flushStreamingText()
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
                if (!belongs) return
                when (kind) {
                    "approval" -> refreshBotStatus()
                    // 后台完成 / 新卡片：状态与行流都刷一下，别静默。
                    "done", "question", "view", "group" -> {
                        refreshBotStatus()
                        refreshTimelineSnapshot(reset = false)
                    }
                    else -> Unit
                }
            }

            "room" -> {
                if (isGroup && data.optString("roomId") == botId) scheduleReload()
            }

            // 宿主投影的增量帧：行管线的第二路输入（快照之外的所有新行）。
            "timeline" -> handleParityTimelineFeed(data)
            // 四期 4a：原始 durable 事件（带连续 seq），进客户端事件日志。
            "session" -> handleSessionEventFrame(data)

            "hello", "revision" -> scheduleReload()
            "live" -> refreshBotStatus()
        }
    }

    // region ---- timeline 主管线（Phase 1b/1c：parity）----

    /** 增量帧的会话归属：单聊按 botId、群聊按 roomId。 */
    private fun frameBelongsToSession(frame: TimelineFeedFrame): Boolean {
        // 身份不明的帧一律不接受：帧只能按 botId / roomId 归属，缺了它就没有归属依据，
        // 会被**任何打开着的聊天**接受（群聊成员的行漏进私聊）。宿主每帧都带身份。
        if (frame.botId == null && frame.roomId == null) return false
        frame.botId?.takeIf { it != botId }?.let { return false }
        frame.roomId?.takeIf { it != botId }?.let { return false }
        return true
    }

    /** 本地 echo 行的数据键（设计 §4：`p:<requestId>`）。 */
    private fun echoKeyOf(requestId: String): String = "p:$requestId"

    /** 构建本地乐观 echo 行（发送中态）：附件块在前文本在后，与官方 content 顺序一致。 */
    private fun echoRowOf(
        requestId: String,
        text: String,
        items: List<ChatAttachmentItem>,
        drafts: List<ChatAttachmentDraft>,
    ): TimelineRow = TimelineRow(
        key = echoKeyOf(requestId),
        kind = TimelineRow.KIND_USER,
        seq = null,
        turn = null,
        timeMs = System.currentTimeMillis(),
        payload = TimelineRowPayload.User(
            text = text,
            blocks = drafts.map { draft ->
                JSONObject().put("type", if (draft.isImage) "image" else "file").put("name", draft.name)
            },
            images = drafts.count { it.isImage },
            requestId = requestId,
            live = false,
        ),
    )

    /**
     * 启动/重放行管线（唯一管线，无开关）。
     *
     * 宿主投影（`timeline` 快照 + feed 增量）就是 entries 的来源；旧管线已于
     * 2026-11-14 删除。hello 变化（换会话）时重拉一次快照。
     */
    private fun startTimelinePipeline() {
        if (client == null) return
        refreshTimelineSnapshot(reset = true)
    }

    /**
     * 拉一次 timeline 快照/重放：`reset` 全量重建行缓冲；否则带 `since` 做 feed 断线
     * 重连的 seq 游标重放（设计 §3）。parity 下这是 entries 的唯一服务端来源。
     */
    private fun refreshTimelineSnapshot(reset: Boolean, sinceOverride: Long? = null, resync: Boolean = false) {
        val connection = client ?: return
        // 显式快照：launch 是异步的，不能让它在执行时再读可变的 paritySeq，
        // 否则断档重放会拿到「已推进到当前帧」的 seq，把丢掉那段跳过。
        val since = sinceOverride ?: paritySeq.takeIf { it > 0L }
        parityTimelineJob?.cancel()
        parityTimelineJob = viewModelScope.launch {
            val request = TimelineRequest(
                botId = botId.takeIf { !isGroup },
                roomId = botId.takeIf { isGroup },
                // resync：拉**完整最新窗口**（不带 since），用它对当前 part 做权威重读；
                // reset：首屏全量 / since 重放：断点续传。
                since = if (reset || resync) null else since,
            )
            val snapshot = runCatching { connection.timeline(request) }.getOrNull() ?: return@launch
            paritySnapshot = snapshot
            // 单聊快照带 sessionId：审批卡/附件发送的会话寻址跟着对齐。
            snapshot.sessionId?.takeIf { it.isNotEmpty() }?.let { sessionId = it }
            snapshot.seq?.let { if (it > paritySeq) paritySeq = it }
            // 四期 4a：首屏/重连后给 durable 事件日志播个种子（只播一次）。
            seedSessionEvents()
            // 分页游标只在「全量快照」或「宿主明确给了新游标」时更新。
            if (reset || snapshot.cursor != null) parityCursor = snapshot.cursor
            if (resync) {
                // 权威重读：清除当前 part 里宿主已不再给的行（已落定 live 回复、被顶掉的
                // 队列项、滚出窗口的行），但保留更早的分页/其它 part。修复「越用越乱、
                // 冷启动才好」（群聊每轮快照只是最新窗口，盲目合并会让残留永远清除不掉）。
                parityLog.resyncCurrent(snapshot.rows)
                // 丢掉的行的渲染键一并清掉，避免将来同 key 复用旧的认领键。
                val live = parityLog.rows().mapTo(HashSet()) { it.key }
                parityRenderKeys.keys.removeAll { it !in live }
            } else {
                if (reset) {
                    // 首屏全量快照：日志本来就是空的，直接清空重建。
                    parityLog.clear()
                    parityRenderKeys.clear()
                }
                snapshot.rows.forEach { row -> absorbParityRow(row) }
            }
            rebuildParityEntries(
                loading = false,
                hasEarlier = if (reset || snapshot.cursor != null) snapshot.cursor != null else null,
            )
            if (reset || resync) recomputeApprovals()
            logRowApplication(if (reset) "snapshot" else if (resync) "resync" else "replay", snapshot.rows)
            // 四期 4c 影子双轨：同一批事件上比对两套折叠（只记差异，不动 UI）。
            shadowCompare(snapshot)
        }
    }

    /**
     * 吸收一行进行缓冲。
     *
     * 认领语义（设计 §2 规则 1 + §4）：`user` 行带 `requestId` 且本地有对应 echo 行 →
     * 渲染键保留 `p:<requestId>`（元素原地过渡），本地附件预览搬过去；`inbox` 行带
     * `requestId` → 替换掉同 requestId 的 echo 行（发送中 → 排队/插话）。
     */
    private fun absorbParityRow(row: TimelineRow) {
        // 幂等写日志：同 seq 覆盖、乱序由 TreeMap 归位、迟到行落回正确位置。
        parityLog.put(row)
        // `changes` 行：摘要不在行里，按 seq 按需拉一次（缓存，不阻塞重建）。
        if (row.payload is TimelineRowPayload.Changes) requestChangesSummary(row.seq)
        val requestId = when (val payload = row.payload) {
            is TimelineRowPayload.User -> payload.requestId
            is TimelineRowPayload.Inbox -> payload.requestId
            else -> null
        } ?: return
        val echoKey = "p:$requestId"
        if (row.kind == TimelineRow.KIND_USER) {
            // 认领（设计 §4）：user 行接替发送中（p:）/排队/插话（inbox）行。
            // echo 可能已被 inbox 行顶掉，所以本地归属用 pendingEchoAttachments 判定
            // （send 记、失败清），两条来路都收口。
            val hadEcho = pendingEchoRows.remove(echoKey) != null
            if (hadEcho || pendingEchoAttachments.containsKey(requestId)) {
                dropPendingInboxRowsOf(requestId)
                parityRenderKeys[row.key] = echoKey
                unclaimedRequests.remove(requestId)
            }
        } else if (row.kind == TimelineRow.KIND_INBOX) {
            // 排队/插话行到达：替换掉同 requestId 的发送中 echo 行。
            if (pendingEchoRows.remove(echoKey) != null) {
                unclaimedRequests.remove(requestId)
            }
        }
    }

    /**
     * 行应用诊断日志（限频）：每次快照/增量应用时记 rows 数量与 key 摘要。
     *
     * 真机追「产物卡/行不即时」时配合宿主侧的 delta 日志一起看（DshMuxLog 出口，
     * 每 [PARITY_LOG_MIN_INTERVAL_MS] 最多一条，避免行流高频刷屏）。
     */
    private fun logRowApplication(source: String, rows: List<TimelineRow>) {
        if (rows.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastParityLogAt < PARITY_LOG_MIN_INTERVAL_MS) return
        lastParityLogAt = now
        val keys = rows.take(6).joinToString(",") { it.key }
        val more = if (rows.size > 6) "…+${rows.size - 6}" else ""
        val cardKeys = rows.filter { it.kind == TimelineRow.KIND_ARTIFACT }.joinToString(",") { it.key }
        // 卡帧绕过限频：诊断「产物卡不及时」时，客户端应用的时刻必须有证据。
        val cardLog = cardKeys.isNotEmpty()
        val throttled = now - lastParityLogAt < PARITY_LOG_MIN_INTERVAL_MS
        if (!cardLog && throttled) return
        lastParityLogAt = now
        DshMuxLog.log("timeline $source t=$now：${rows.size} 行 [$keys$more] seq=$paritySeq 缓冲=${parityLog.size()}${if (cardLog) " CARDS=[$cardKeys]" else ""}")
    }

    /**
     * `changes` 行的摘要按需拉取（`docs/parity-checklist.md` §2 P1）。
     *
     * 行的轻载荷只有 `{turn}`；摘要按 `(sessionId, seq)` 走 DSH
     * `GET /api/changes.summary`（裸 JSON，解析见 [parseChangesSummary]）。同一 seq
     * 只拉一次，无论成败（失败静默、只记一条 DshMuxLog）；拉到/拉完都触发一次全量
     * 重建。这里只发协程、不等结果 —— 绝不阻塞行应用。
     */
    private fun requestChangesSummary(seq: Long?) {
        if (seq == null) return
        if (changesSummaries.containsKey(seq) || seq in changesFetching) return
        val attempt = (changesAttempts[seq] ?: 0) + 1
        if (attempt > CHANGES_SUMMARY_ATTEMPTS) return
        val connection = client ?: return
        changesFetching.add(seq)
        changesAttempts[seq] = attempt
        val target = sessionId?.takeIf { it.isNotEmpty() } ?: botId
        viewModelScope.launch {
            val result = runCatching { connection.changesSummary(target, seq) }
            val summary = result.getOrNull()?.let(::parseChangesSummary)
            changesFetching.remove(seq)
            if (summary != null) {
                changesSummaries[seq] = summary
            } else if (attempt >= CHANGES_SUMMARY_ATTEMPTS) {
                // 只在放弃的那一次落一条日志（前几次失败可能是快照/端点还没就绪）。
                val failure = result.exceptionOrNull()
                DshMuxLog.log(
                    "changes.summary seq=$seq 没有摘要（第 $attempt 次，放弃）" +
                        (failure?.let { "（${it.message ?: it.javaClass.simpleName}）" } ?: "（宿主已释放或该 seq 无记录）"),
                )
            }
            rebuildParityEntries()
        }
    }

    /** 摘掉同 requestId 的排队/插话行（认领或撤回后它们就没有意义了）。 */
    private fun dropPendingInboxRowsOf(requestId: String) {
        parityLog.rows()
            .filter { (it.payload as? TimelineRowPayload.Inbox)?.requestId == requestId }
            .map { it.key }
            .forEach { key ->
                parityLog.withdraw(key)
                parityRenderKeys.remove(key)
            }
    }

    /**
     * 行缓冲 → entries 全量重建（唯一写 entries 的地方）。
     *
     * **渲染顺序 = 日志顺序**（`parityLog`：(part, seq) 有序）：宿主按显示序
     * 返回 rows（首屏单 part 内 seq 升序、更早的 part 整页前插、增量追加尾部），
     * 客户端不再按 seq 全局排序 —— part 之间 seq 空间独立（每个 part 是独立 session），
     * 按 seq 排序会在跨 part 时把老 part 的行插错位置。
     *
     * 本地 echo（`p:<requestId>`）与浏览器缩略图排在尾部（乐观位置 / 客户端本地功能）。
     * `inbox` 行（已进宿主队列、还没被认领）**不进时间线**，单独进 [BotChatUiState.pendingQueue]
     * 交给输入框上方的队列条（官方 web 的时间线也不出现排队行）。
     */
    private fun rebuildParityEntries(loading: Boolean? = null, hasEarlier: Boolean? = null) {
        // 全量重算：顺序由日志（part 序 + 段内 seq）决定，不看帧到达顺序。
        val timelineRows = parityLog.rows()
        // 同一条消息已经在时间线上是 `user` 气泡了 → 它的 `inbox`（排队/插话）行是
        // 同一句话的第二份显示。队列条里留着它，就会每次刷新都把「撤回」又摆出来
        // （服务端再给一次即撤销本地 withdraw）。消息还没成气泡时队列行保留（可撤回）。
        val claimedRequests = timelineRows.mapNotNullTo(HashSet()) { row ->
            (row.payload as? TimelineRowPayload.User)?.requestId
        }
        val mapped = ArrayList<ChatEntry>(timelineRows.size + pendingEchoRows.size + browserShots.size)
        for (row in timelineRows) {
            if (row.kind == TimelineRow.KIND_INBOX) {
                val requestId = (row.payload as? TimelineRowPayload.Inbox)?.requestId
                if (requestId != null && requestId in claimedRequests) continue
            }
            val renderKey = parityRenderKeys[row.key]
            val echoRequestId = (row.payload as? TimelineRowPayload.User)?.requestId
            val attachmentItems = echoRequestId?.let { pendingEchoAttachments[it] } ?: emptyList()
            // `changes` 行：把按 seq 缓存好的摘要贴给映射器（没拉到就为 null，映射后不出行）。
            val changesSummary = (row.payload as? TimelineRowPayload.Changes)
                ?.let { row.seq?.let { seq -> changesSummaries[seq] } }
            chatEntryOf(
                row = row,
                botName = botName,
                renderKey = renderKey,
                attachmentItems = attachmentItems,
                changesSummary = changesSummary,
            )?.let(mapped::add)
        }
        // 本地 echo 行：乐观位置，排在尾部。
        for (echoRow in pendingEchoRows.values) {
            chatEntryOf(echoRow, botName)?.let(mapped::add)
        }
        // 浏览器缩略图：客户端本地功能，拼在最后（1c 换贴 turntail 行 seq）。
        for (shotEntry in browserShots) mapped.add(shotEntry)
        // 队列行与时间线分开：inbox 行只进队列条，不画进对话。
        val queue = mapped.filter { it.kind == ChatEntryKind.PENDING }
        val timeline = mapped.filterNot { it.kind == ChatEntryKind.PENDING }
        _uiState.update { state ->
            state.copy(
                entries = timeline,
                pendingQueue = queue,
                loading = loading ?: state.loading,
                engineOff = false,
                hasEarlier = hasEarlier ?: state.hasEarlier,
                loadingEarlier = parityLoadingEarlier,
            )
        }
    }

    /** feed `timeline` 增量（parity 主路径）：吸收行 + 重建 entries；断档重拉快照。 */
    private fun handleParityTimelineFeed(data: JSONObject) {
        val frame = parseTimelineFeedFrame(data) ?: return
        if (!frameBelongsToSession(frame)) return
        if (frame.reset) {
            // 宿主：「你的游标太旧，重读快照」。做**权威重读**——用快照重建当前 part、
            // 清掉宿主已不再给的行（已落定/已移除的残留），同时保留更早的历史。
            refreshTimelineSnapshot(reset = false, resync = true)
            return
        }
        // 断档检测（2026-11-15 真机日志实锤的根治）：宿主 timeline 增量帧是
        // 「一次性消费」的，SSE 断线/重连窗口里丢掉的帧**不会重发**。帧自带
        // `from`（本次覆盖的 seq 边界）—— 若本地 paritySeq 落后于它，说明中间
        // 有帧丢了（产物卡帧最容易在这一刻丢，表现为「卡要重进/冷启动才出」）。
        // 立刻带 since 重放快照把缺失的行补回来，再正常应用当前帧。
        val gapFrom = frame.from
        val gapBufferFrom = frame.bufferFrom
        val behind = (gapFrom != null && gapFrom > paritySeq + 1) ||
            (gapBufferFrom != null && paritySeq in 1 until gapBufferFrom - 1)
        if (behind) {
            DshMuxLog.log(
                "timeline 增量断档：本地 seq=$paritySeq，帧 from=$gapFrom bufferFrom=$gapBufferFrom，带 since 重放补齐",
            )
            // 显式传断档前的 seq：重放是异步 launch，不能让它执行时再读 paritySeq。
            refreshTimelineSnapshot(reset = false, sinceOverride = paritySeq.takeIf { it > 0L })
        }
        frame.rows.forEach { row -> absorbParityRow(row) }
        frame.seq?.let { if (it > paritySeq) paritySeq = it }
        rebuildParityEntries()
        logRowApplication("increment", frame.rows)
        // 群聊帧带 round 状态：活动状态行实时刷新（旧 room-progress 轮询保留为兜底，
        // 设计 §5）。running 的 true→false 边沿 = 本轮收口：全量重拉一次快照，
        // 把这一轮的落定行（relay 回复从 live 换 settled 等）对齐。
        if (isGroup && frame.running != null) {
            val wasRunning = parityRoundRunning
            noteGroupRound(frame.running)
            frame.speaking?.let { groupSpeakingId = it }
            if (wasRunning && !frame.running) {
                // 轮收口：权威重读快照。群聊快照只是「最新一页」，这里用它对当前 part
                // 收敛——清掉已落定的 live 回复、被顶掉的队列项等残留，同时保留更早历史。
                // 旧实现 reset=true 会把整片历史清掉；改成合并后又会积残留；resync 居中。
                refreshTimelineSnapshot(reset = false, resync = true)
            }
            parityRoundRunning = frame.running
        }
    }

    // region ---- 四期 4a：durable 事件日志（完整性判据）----

    /**
     * feed `session` 帧：原始 durable 事件（带连续 seq）。对齐 dsh-Go `_onFrame`：
     * 重复丢弃、连续追加、跳号 → 向宿主 `events` 端点补洞（等价 `_fill`）。
     *
     * 4a 只维护日志与完整性，不参与渲染，也不影响 [parityLog] 的行管线。
     */
    private fun handleSessionEventFrame(data: JSONObject) {
        val frame = parseSessionEventFrame(data) ?: return
        if (!sessionEventBelongs(frame.sessionId)) return
        val gap = sessionEvents.put(frame.event)
        if (gap != null) fillSessionEvents(gap)
    }

    /** 事件日志的会话归属：群聊 = roomId（即 botId），单聊 = 当前 part 的 sessionId。 */
    private fun sessionEventBelongs(id: String): Boolean {
        if (isGroup) return id == botId
        val current = sessionId
        // 首帧快照还没到、sessionId 未知时先收下（快照到达后按会话 id 校验）。
        return current.isNullOrEmpty() || id == current
    }

    /** 缺口补洞：`events{from, through}`（对齐 dsh-Go `_fill`）。补完由日志推进游标。 */
    private fun fillSessionEvents(gap: LongRange) {
        if (sessionEventsFilling) return
        val connection = client ?: return
        val session = sessionId?.takeIf { it.isNotEmpty() } ?: if (isGroup) botId else return
        sessionEventsFilling = true
        viewModelScope.launch {
            runCatching { connection.events(session, gap.first, gap.last) }
                .onSuccess { list ->
                    sessionEvents.merge(list)
                    DshMuxLog.log(
                        "session-events 补洞 ${gap.first}..${gap.last}：收到 ${list.size} 条，cursor=${sessionEvents.cursor}",
                    )
                }
                .onFailure {
                    DshMuxLog.log(
                        "session-events 补洞失败 ${gap.first}..${gap.last}：${it.message ?: it.javaClass.simpleName}",
                    )
                }
            sessionEventsFilling = false
        }
    }

    /** 首屏/重连种子：拉最近一段 durable 事件，让日志从一开始就连续（只播一次）。 */    private fun seedSessionEvents() {
        if (!sessionEvents.isEmpty()) return
        val connection = client ?: return
        val session = if (isGroup) botId else sessionId?.takeIf { it.isNotEmpty() } ?: return
        viewModelScope.launch {
            runCatching { connection.events(session, from = 1, max = 300) }
                .onSuccess { list ->
                    if (list.isEmpty()) return@onSuccess
                    sessionEvents.reset(list)
                    DshMuxLog.log(
                        "session-events 种子 ${list.first().seq}..${list.last().seq}（${list.size} 条），cursor=${sessionEvents.cursor}",
                    )
                }
        }
    }

    /** 四期 4a：事件日志连续游标（诊断/测试用；-1 = 还没有种子）。 */
    internal fun sessionEventCursor(): Long = sessionEvents.cursor

    /** 四期 4a：事件日志里已缓存的事件条数（诊断/测试用）。 */
    internal fun sessionEventCount(): Int = sessionEvents.size

    /** 影子比对「跳过」的限频记录（跳过也要留痕：否则分不清「跳过」与「失败」）。 */
    private fun shadowSkip(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastShadowSkipAt < SHADOW_SKIP_LOG_MS) return
        lastShadowSkipAt = now
        DshMuxLog.log("影子比对：跳过（$reason）")
    }

    /**
     * 四期 4c **影子双轨**：拿「同一批事件」上，客户端自折叠与宿主行投影逐行比对，
     * **只记差异日志，不动 UI**。     *
     * 这是 4c 切换前的安全网：真机跑一天，差异日志全空才敢切显示。差异非空时
     * 说明两套实现已经开始分叉——那时还没切 UI，代价为零。
     *
     * 比对前先向宿主 `events` 端点**取齐同一段事件**（确定性，不依赖 feed 时机）：
     * 快照到达时事件日志未必已收到那批事件，直接比会得到一堆假差异。
     */
    private fun shadowCompare(snapshot: TimelineSnapshot) {
        if (isGroup) {
            // 群聊是**服务端条目模型**（产物卡来自成员会话，不在群会话事件里），
            // 客户端不折叠它 —— 记一条，免得「没跑」和「失败」分不清。
            shadowSkip("群聊：服务端条目模型，不做客户端折叠比对")
            return
        }
        val from = snapshot.eventFrom
        val to = snapshot.eventTo
        if (from == null || to == null || to < from) {
            shadowSkip("快照没有事件区间（eventFrom/eventTo），无从比对")
            return
        }
        val connection = client ?: return
        viewModelScope.launch {
            // 1) 取齐这段事件（本地没有就问宿主要；补齐后写进日志，事件层也自愈）。
            if (ShadowDiff.eventsForRange(sessionEvents, from, to) == null) {
                runCatching { connection.events(sessionId ?: botId, from, to, max = 500) }
                    .onSuccess { sessionEvents.merge(it) }
            }
            val events = ShadowDiff.eventsForRange(sessionEvents, from, to) ?: return@launch
            // 2) 客户端自折叠（同一段事件）。
            val clientRows = try {
                TimelineFold.fold(
                    events = events,
                    exchangeTurns = snapshot.exchangeTurns.toSet(),
                    cwd = snapshot.cwd,
                    botName = botName,
                )
            } catch (error: Exception) {
                DshMuxLog.log("影子比对：客户端折叠抛错 ${error.message ?: error.javaClass.simpleName}")
                return@launch
            }
            // 3) 与宿主行投影逐行比（只比本次快照覆盖的行）。
            val hostRows = snapshot.rows.filter { row ->
                val seq = row.seq
                seq == null || (seq >= from && seq <= to)
            }
            val result = ShadowDiff.compare(hostRows, clientRows)
            if (result.identical) {
                // 一致是常态：限频记（否则每次快照都刷屏）。
                val now = System.currentTimeMillis()
                if (now - lastShadowOkAt > SHADOW_OK_LOG_MS) {
                    lastShadowOkAt = now
                    DshMuxLog.log("影子比对：一致（${events.size} 事件 → ${clientRows.size} 行）")
                }
            } else {
                // 不一致是异常：**每次都记**（要抓得住）。
                DshMuxLog.log("影子比对：${result.summary()}")
            }
        }
    }

    // endregion

    /**
     * earlier 分页：timeline cursor 翻页，新的一页**整页前插**进行缓冲
     * （LinkedHashMap 重建保持「新页 rows + 旧 rows」的插入序）。
     *
     * 单聊 / 群聊同一做法：part 之间 seq 空间独立，靠 seq 排序会插错位置；
     * 宿主已按显示序返回每页 rows，客户端只做前插。
     */
    private fun parityLoadEarlier() {
        val connection = client ?: return
        val cursor = parityCursor ?: return
        if (parityLoadingEarlier) return
        parityLoadingEarlier = true
        rebuildParityEntries()
        viewModelScope.launch {
            val request = TimelineRequest(
                botId = botId.takeIf { !isGroup },
                roomId = botId.takeIf { isGroup },
                cursor = cursor,
            )
            runCatching { connection.timeline(request) }
                .onSuccess { snapshot ->
                    if (snapshot == null) {
                        // 接口默认实现 = 不支持（能力/接线缺位），静默收场。
                        parityLoadingEarlier = false
                        rebuildParityEntries()
                        return@onSuccess
                    }
                    parityCursor = snapshot.cursor
                    if (isGroup) {
                        // 位置序：更早的页**整页前插**（不跨 part 按 seq 排序）。
                        parityLog.prependPage(snapshot.rows)
                        snapshot.rows.forEach { row -> absorbEchoRelationsOf(row) }
                    } else {
                        snapshot.rows.forEach { row -> absorbParityRow(row) }
                    }
                    parityLoadingEarlier = false
                    rebuildParityEntries(hasEarlier = snapshot.cursor != null)
                }
                .onFailure {
                    parityLoadingEarlier = false
                    rebuildParityEntries()
                }
        }
    }

    /** earlier 前插时只做行替换与 echo 认领，不走 absorbParityRow（后者会去重排队行）。 */
    private fun absorbEchoRelationsOf(row: TimelineRow) {
        val requestId = when (val payload = row.payload) {
            is TimelineRowPayload.User -> payload.requestId
            is TimelineRowPayload.Inbox -> payload.requestId
            else -> null
        } ?: return
        if (row.kind == TimelineRow.KIND_USER) {
            val echoKey = "p:$requestId"
            val hadEcho = pendingEchoRows.remove(echoKey) != null
            if (hadEcho || pendingEchoAttachments.containsKey(requestId)) {
                dropPendingInboxRowsOf(requestId)
                parityRenderKeys[row.key] = echoKey
                unclaimedRequests.remove(requestId)
            }
        } else if (row.kind == TimelineRow.KIND_INBOX) {
            if (pendingEchoRows.remove("p:$requestId") != null) {
                unclaimedRequests.remove(requestId)
            }
        }
    }

    /**
     * 撤回一条排队项（只有 `sourceKind == "user"` 的项该有按钮）。
     *
     * 单聊走 `session/updateQueue {kind:'remove'}`（契约已锁）；群聊的排队真身
     * 在插件的 roomInbox 里，走 `room-queue {roomId, id, action:'remove'}`。
     * 两条都是「提交即乐观摘行，服务端下一份快照/增量给出真相」。
     */
    fun recallPending(entry: ChatEntry) {
        if (isGroup) recallRoomPending(entry) else recallPrivatePending(entry)
    }

    /** 单聊：`session/updateQueue {kind:'remove'}`。 */
    private fun recallPrivatePending(entry: ChatEntry) {
        val itemId = entry.id.removePrefix("inbox:")
        if (itemId.isEmpty()) return
        val connection = client ?: return
        viewModelScope.launch {
            runCatching {
                connection.rpc(
                    "session/updateQueue",
                    JSONObject().put(
                        "request",
                        JSONObject()
                            .put("sessionId", sessionId ?: botId)
                            .put("itemId", itemId)
                            .put("action", JSONObject().put("kind", "remove")),
                    ),
                )
            }
            // 乐观摘行：写进 withdraw（重放带回来也不显示，服务端真给了才恢复）。
            parityLog.withdraw(entry.id)
            rebuildParityEntries()
        }
    }

    /**
     * 群聊：`room-queue {roomId, id, action:'remove'}`（mutating 端点，不在 READS）。
     * 只撤自己排的（[ChatEntry.pendingSourceKind] == `user`）；成员/系统项不调。
     */
    private fun recallRoomPending(entry: ChatEntry) {
        if (entry.pendingSourceKind != "user") return
        val id = entry.id.removePrefix("inbox:")
        if (id.isEmpty()) return
        val connection = client ?: return
        viewModelScope.launch {
            runCatching {
                connection.botValue(
                    "room-queue",
                    JSONObject().put("roomId", botId).put("id", id).put("action", "remove"),
                )
            }
            // 乐观摘行：宿主下一份快照/增量给出真相。
            parityLog.withdraw(entry.id)
            rebuildParityEntries()
        }
    }

    /**
     * 把一条排队消息改成插话：`session/updateQueue {kind:'steer'}`（契约已锁）。
     *
     * 只对单聊有意义：群队列要等下一个 relay 才 drain，没有「插进当前回合」的语义，
     * 所以群聊不提供这个动作（UI 也不给按钮）。
     */
    fun steerPending(entry: ChatEntry) {
        if (isGroup) return
        val itemId = entry.id.removePrefix("inbox:")
        if (itemId.isEmpty()) return
        val connection = client ?: return
        viewModelScope.launch {
            runCatching {
                connection.rpc(
                    "session/updateQueue",
                    JSONObject().put(
                        "request",
                        JSONObject()
                            .put("sessionId", sessionId ?: botId)
                            .put("itemId", itemId)
                            .put("action", JSONObject().put("kind", "steer")),
                    ),
                )
            }
        }
    }

    // endregion

    /** 读活动/记忆新闻，给名字胶囊下面那行状态用。 */
    /** 记下群聊这一轮的状态（`room-chat` 每次刷新都会带上）。 */
    private fun applyGroupRound(value: JSONObject) {
        noteGroupRound(value.optBoolean("running", false))
        groupSpeakingId = value.optString("speaking").takeIf { it.isNotEmpty() }
    }

    /** 记下群聊这一轮在不在跑（帧里的 running）。 */
    private fun noteGroupRound(running: Boolean) {
        groupRoundRunning = running
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
        noteGroupRound(running)
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
                // 单聊只认「自己会话」的工作：宿主的 `work.bots[botId]` 会把群回合的
                // 工作也挂在同一个 botId 上（带 `roomId`）。带 roomId 的条目属于群，
                // 不能把私聊的头像圈/停止键点亮（官方也是按会话判 running）。
                val entry = work?.optJSONObject("bots")?.opt(botId)
                when (entry) {
                    null -> false
                    is JSONObject -> entry.optString("roomId").isEmpty()
                    // 旧形状（布尔等）：没有 roomId 信息，按「自己在跑」处理。
                    else -> true
                }
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
            // 群聊被中转上限暂停：`state.rooms[].paused` 里是等待放行的中转。
            val paused = if (isGroup) parseRoomPaused(state, botId) else emptyList()
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
                    roomPaused = paused,
                    memberStatus = if (isGroup) parseMemberStatus(state, botId) else emptyMap(),
                    modelPrompt = if (isGroup) null else modelPromptOf(state, bot),
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
            // turn 边界由宿主的行流/状态行承担（旧 follow 的 turn/end 记录已随旧
            // 管线删除）；mux 的 `api-session/status`（sessionStatus）才是权威信号。
            lastTurnEnded = false,
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

    /**
     * 忽略一张提问卡（DS Bot `dismiss-question {sessionId}`）。
     *
     * 官方语义是**按会话关掉整张提问卡**（宿主 `delete state.questions[sessionId]`），
     * 不是只关某一条 —— 所以请求里只带 sessionId，和 [dismissChatView] 同款：
     * 先本地摘卡，再让 `refreshBotStatus` 对齐。
     */
    fun dismissChatQuestion(card: ChatQuestionCard) {
        val connection = client ?: return
        _uiState.update { it.copy(pendingQuestions = it.pendingQuestions.filterNot { q -> q.id == card.id }) }
        viewModelScope.launch {
            runCatching {
                connection.bot(
                    "dismiss-question",
                    JSONObject().put("sessionId", sessionId ?: botId),
                )
            }
            refreshBotStatus()
        }
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
        val channel = transport
        if (clientId == null || eventId == null || questionId == null || channel == null || selected.isEmpty()) {
            onDone("这条提问暂时无法作答")
            return
        }
        viewModelScope.launch {
            val answers = answersToJson(selected.map { DshQuestionAnswer(questionId, it) })
            val error = runCatching {
                channel.answerQuestion(clientId, eventId, answers)
            }.exceptionOrNull()?.let { describeChatError(it) }
            if (error == null) {
                hostQuestions.remove(eventId)
                recomputeApprovals()
                refreshBotStatus()
            }
            onDone(error)
        }
    }

    /**
     * 群聊暂停后点「继续」：`room-continue { roomId }`。
     *
     * 插件把寄存的中转放进新的一轮里跑（`groups.js continueRoom`），
     * 所以提交完要刷一次状态和这一轮的行。
     */
    fun continueRoom(onDone: (String?) -> Unit = {}) {
        val connection = client ?: return
        if (_uiState.value.roomContinuing) return
        _uiState.update { it.copy(roomContinuing = true) }
        viewModelScope.launch {
            val failure = runCatching {
                connection.bot("room-continue", JSONObject().put("roomId", botId))
            }.exceptionOrNull()?.let { describeChatError(it) }
            _uiState.update { it.copy(roomContinuing = false) }
            if (failure == null) {
                refreshBotStatus()
                refreshTimelineSnapshot(reset = false)
            }
            onDone(failure)
        }
    }

    /** 在对话上方选模型：`update-bot {id, model}`（网页版 `ModelChoiceDock` 的 chip）。 */
    fun chooseModel(ref: String, onDone: (String?) -> Unit = {}) {
        val connection = client ?: return
        viewModelScope.launch {
            val failure = runCatching {
                connection.bot("update-bot", JSONObject().put("id", botId).put("model", ref))
            }.exceptionOrNull()?.let { describeChatError(it) }
            if (failure == null) refreshBotStatus()
            onDone(failure)
        }
    }

    /**
     * 群聊里单独停下某个成员的工作：`stop-member-work {roomId, botId}`。
     *
     * 整轮 stop（[stopRunning]）保持不变；这个只停一个成员在群里的那一份工作。
     * 成功后刷状态并给一句轻提示。
     */
    fun stopMemberWork(member: RoomMemberUi, onDone: (String?) -> Unit = {}) {
        val connection = client ?: return
        viewModelScope.launch {
            val error = runCatching {
                connection.botValue(
                    "stop-member-work",
                    JSONObject().put("roomId", botId).put("botId", member.id),
                )
            }.exceptionOrNull()?.let { describeChatError(it) }
            if (error == null) {
                showActionNotice(
                    notifyContext?.getString(R.string.chat_member_stopped, member.name)
                        ?: "已让 ${member.name} 停下",
                )
                refreshBotStatus()
            }
            onDone(error)
        }
    }

    /** 一次性轻提示（几秒后自动消失）；同类提示连续触发只重置计时。 */
    private fun showActionNotice(text: String) {
        _uiState.update { it.copy(actionNotice = text) }
        actionNoticeJob?.cancel()
        actionNoticeJob = viewModelScope.launch {
            delay(2_500)
            _uiState.update { it.copy(actionNotice = null) }
        }
    }

    /** 停止当前对话正在执行的回合：按钮点下去立刻收起，再让引擎取消。 */
    fun stopRunning(onDone: (String?) -> Unit = {}) {
        val connection = client ?: return
        optimisticRun = false
        // 本地先认「已经停下」：引擎的会话状态事件随后会确认，不让停止键闪回来。
        userStoppedRun = true
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
            var failedPolls = 0
            var lastRunning: Boolean? = null
            var lastStatusAt = 0L
            var lastStateAt = 0L
            val watchStartedAt = System.currentTimeMillis()
            val botEntriesAtStart = _uiState.value.entries.count { it.kind == ChatEntryKind.BOT }
            while (idlePolls < 2) {
                delay(1_000)
                val now = System.currentTimeMillis()
                // 群聊只读 `room-progress`（很小）；`state` 太重，不能每秒拉 ——
                // 手机上的引擎被高频轮询拖垮过，round 反而推不动。
                // 单聊同病：拉 `state` 节流到 3 秒一次（状态行本来就是 3 秒一刷），
                // 中间的秒沿用上一次的结论，回合结束的判定最多晚一个节流窗口。
                val running = if (isGroup) {
                    refreshGroupProgress(connection)
                } else if (lastRunning == null || now - lastStateAt >= SINGLE_CHAT_STATE_MIN_MS) {
                    val state = runCatching { connection.bot("state") }.getOrNull()
                    if (state == null) {
                        // 连续拿不到 `state`（引擎下线/超时）：到上限就按「没在跑」收尾，
                        // 别对着已经不在的引擎每秒空转（以前这里 `?: continue` 会永远轮询）。
                        failedPolls += 1
                        if (failedPolls >= RUN_WATCH_MAX_FAILURES) {
                            idlePolls = 2
                            false
                        } else {
                            continue
                        }
                    } else {
                        failedPolls = 0
                        lastStateAt = now
                        runningNow(state.optJSONObject("work")?.optJSONObject("bots")?.has(botId) == true)
                    }
                } else {
                    lastRunning == true
                }
                if (running) {
                    idlePolls = 0
                    // 状态行（工具文案/计时）不需要每秒刷；3 秒一次够了。
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
            // 轮末**无条件**按 since 重放一次快照：宿主增量帧是一次性消费的，若本轮最后一帧
            // （最常见就是产物卡帧）落在断流窗口里丢了，这里幂等合并把它补回来 ——
            // 不再依赖「必须有下一帧」才触发断档检测（那正是此前的漏洞）。
            // 顺带把没等到回显的本地气泡一并自愈（重放会把 user 回显带回来）。
            refreshTimelineSnapshot(reset = false)
            val phoneTaskDone = PhoneControlSession.consumeFor(botId)
            // 这一轮有没有产出：失败行由宿主投影的 `event` 行承担（eventKind=failed），
            // 从本轮的行里取，和「正常做完」区分开（不再另查诊断）。
            val produced = _uiState.value.entries.count { it.kind == ChatEntryKind.BOT } > botEntriesAtStart
            val context = notifyContext
            val last = _uiState.value.entries.lastOrNull { it.kind == ChatEntryKind.BOT }?.text.orEmpty()
            val showPhoneResult = phoneTaskDone && context != null &&
                DshmAccessibilityService.current != null
            val turnFailure = _uiState.value.entries.lastOrNull { entry ->
                entry.kind == ChatEntryKind.EVENT && entry.eventKind == "failed" &&
                    (entry.timeMs ?: 0L) >= watchStartedAt
            }?.text
            if (context != null && !showPhoneResult && turnFailure != null) {
                pushNotification(
                    ChatNotifier.Kind.ERROR,
                    context.getString(R.string.notify_error_title, botName),
                    notificationPreview(turnFailure),
                )
            } else if (context != null && !showPhoneResult && produced && turnFailure == null) {
                pushNotification(
                    ChatNotifier.Kind.DONE,
                    context.getString(R.string.notify_done_title, botName),
                    notificationPreview(last.ifBlank { context.getString(R.string.notify_done_body) }),
                )
            }
            if (phoneTaskDone) {
                if (showPhoneResult) {
                    val result = if (produced) {
                        last.ifBlank { context?.getString(R.string.notify_done_body).orEmpty() }
                    } else {
                        turnFailure ?: "任务已经结束。"
                    }
                    PhoneOverlay.finishWithResult(context, result)
                } else {
                    PhoneOverlay.hideNow()
                }
            }
            userStoppedRun = false
        }
    }

    private fun clearStreaming() {
        streamFlush.trailingJob?.cancel()
        streamFlush.trailingJob = null
        streamFlush.lastFlushMs = 0L
        streamFlush.lastFlushedLen = 0
        streamFlush.pending = null
        streamingAttempt = null
        streamBuffer = ""
        _streamingText.value = ""
    }

    /**
     * 双路 flush（对齐 DeepSearch `updateAssistantMessage`）：
     * - 时间路径：按当前总长度取自适应间隔，短回答 30ms、长回答逐级放宽；
     * - 换行快车道：短文档里新内容满 50 字且带换行就立即推，观感是逐行落字；
     * - 被节流的 tick 记成 `pending`，尾部延迟任务保证最后一段一定落地。
     *
     * 高频更新只写 `streamingText` 侧通道，不碰主 `_uiState`，
     * 所以顶栏 / 输入框 / 历史气泡不会跟着每个 token 重组。
     */
    private fun flushStreamingText() {
        val content = streamBuffer
        val state = streamFlush
        val now = System.currentTimeMillis()
        val elapsed = now - state.lastFlushMs
        val throttle = streamingThrottleMs(content.length)
        val newChunk = if (content.length > state.lastFlushedLen) {
            content.substring(state.lastFlushedLen.coerceAtMost(content.length))
        } else ""
        val unflushed = content.length - state.lastFlushedLen
        val newlineFlush = content.length < NEWLINE_FLUSH_MAX_LEN &&
            newChunk.contains('\n') && unflushed >= NEWLINE_FLUSH_MIN_CHARS

        fun publish(text: String) {
            StreamRenderLog.flush(text.length, throttle)
            _streamingText.value = text
            state.lastFlushMs = System.currentTimeMillis()
            state.lastFlushedLen = text.length
            state.pending = null
        }

        if (state.lastFlushMs == 0L || elapsed >= throttle || newlineFlush) {
            state.trailingJob?.cancel()
            state.trailingJob = null
            publish(content)
        } else {
            state.pending = content
            if (state.trailingJob == null) {
                val wait = (throttle - elapsed).coerceAtLeast(16L)
                state.trailingJob = viewModelScope.launch {
                    delay(wait)
                    state.pending?.let(::publish)
                    state.trailingJob = null
                }
            }
        }
    }

    /** `state.rooms[roomId].paused` → 等待放行的中转（DS Bot `room.paused`）。 */
    private fun parseRoomPaused(state: JSONObject, roomId: String): List<ChatPausedMember> {
        val rooms = state.optJSONArray("rooms") ?: return emptyList()
        val room = (0 until rooms.length())
            .mapNotNull { rooms.optJSONObject(it) }
            .firstOrNull { it.optString("id") == roomId }
            ?: return emptyList()
        val paused = room.optJSONArray("paused") ?: return emptyList()
        return (0 until paused.length()).mapNotNull { index ->
            val entry = paused.optJSONObject(index) ?: return@mapNotNull null
            val name = entry.optString("name").trim()
            if (name.isEmpty()) return@mapNotNull null
            ChatPausedMember(
                id = entry.optString("id"),
                name = name,
                note = entry.optString("note").takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * 该不该让用户先选模型（对齐网页版 `ModelChoiceDock`）。
     *
     * 条件与文案都照抄它：模型还在 → 不提示；模型没了 / 没选过 / 一个可用模型都没有
     * → 出一张卡，点 chip 即 `update-bot {model}`。
     */
    private fun modelPromptOf(state: JSONObject, bot: JSONObject?): ChatModelPrompt? {
        val models = state.optJSONArray("models")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val ref = item.optString("ref")
                if (ref.isEmpty()) return@mapNotNull null
                ChatModelChoice(
                    ref = ref,
                    name = item.optString("name").ifEmpty { ref },
                    provider = item.optString("providerName")
                        .ifEmpty { item.optString("provider") }
                        .takeIf { it.isNotEmpty() },
                    needsKey = item.optBoolean("needsKey", false),
                )
            }
        }.orEmpty()
        val current = bot?.optString("model").orEmpty()
        val usable = models.filterNot { it.needsKey }
        val lost = current.isNotEmpty() && models.none { it.ref == current }
        // 模型还在：什么都不用提示（和网页版一样）。
        if (current.isNotEmpty() && !lost) return null
        if (current.isNotEmpty() && lost && models.isEmpty()) return null
        return when {
            lost -> ChatModelPrompt(
                kind = "lost",
                message = "原来的模型已经不在了，给我换一个吧",
                choices = usable,
            )
            usable.isEmpty() && models.isNotEmpty() -> ChatModelPrompt(
                kind = "key",
                message = "我还需要一个 ${models.first().provider ?: "模型"} 的 API Key 才能开始",
                choices = models,
            )
            usable.isEmpty() -> ChatModelPrompt(
                kind = "empty",
                message = "我还没有模型，你想怎么设置？",
            )
            else -> ChatModelPrompt(
                kind = "pick",
                message = "先给我选一个模型吧，之后可以在详情里改",
                choices = usable,
            )
        }
    }

    override fun onCleared() {
        feedJob?.cancel()
        statusJob?.cancel()
        runningWatchJob?.cancel()
        parityTimelineJob?.cancel()
    }

    companion object {
        /** 事件流缓存的上限：同时等应答的提问一般就一个对话，多出来的一定是漏掉的 cancel。 */
        private const val HOST_PENDING_CAP = 32
        /** `changes` 摘要每个 seq 最多拉几次（失败重试；成功即停）。 */
        private const val CHANGES_SUMMARY_ATTEMPTS = 3
        /** 影子比对「一致」的记日志间隔（不一致每次都记）。 */
        private const val SHADOW_OK_LOG_MS = 30_000L
        /** 影子比对「跳过」的记日志间隔。 */
        private const val SHADOW_SKIP_LOG_MS = 60_000L
        /** 对话里最多留几张浏览器缩略图卡（只活在本次对话）。 */
        private const val MAX_BROWSER_SHOTS = 30
        /** 流式刷新间隔：短回答贴帧、长回答逐级放宽（对齐 DeepSearch `streamingThrottleMs`）。 */
        internal fun streamingThrottleMs(length: Int): Long = when {
            length < 500 -> 30L
            length < 2_000 -> 50L
            length < 32_000 -> 80L
            length < 64_000 -> 120L
            length < 128_000 -> 160L
            else -> 200L
        }
        /** 换行快车道：短文档里新满 50 字且遇到换行就立刻推一行。 */
        private const val NEWLINE_FLUSH_MAX_LEN = 32_000
        private const val NEWLINE_FLUSH_MIN_CHARS = 50
        private const val STOP_GRACE_NS = 2_000_000_000L
        /** 单聊 `state` 拉取的最小间隔：重接口，和状态行的 3 秒刷新对齐。 */
        private const val SINGLE_CHAT_STATE_MIN_MS = 3_000L
        /** 回合监视连续拿不到 `state` 的上限：超过就按「没在跑」收尾，不再空转。 */
        private const val RUN_WATCH_MAX_FAILURES = 5
        /** 行应用诊断日志的限频：同一会话两次日志的最小间隔。 */
        private const val PARITY_LOG_MIN_INTERVAL_MS = 3_000L

        fun factory(
            botId: String,
            botName: String,
            isGroup: Boolean,
            endpoint: RuntimeEndpoint?,
            appContext: Context? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BotChatViewModel(botId, botName, isGroup, endpoint, appContext)
            }
        }
    }
}
