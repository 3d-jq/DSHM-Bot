package com.dshm.bot.ui.chat

import org.json.JSONArray
import org.json.JSONObject

/** 状态行的活动类型，对齐 DS Bot 的 BotMark state。 */
enum class ChatActivityKind {
    THINKING,
    SEARCHING,
    WORKING,
    SENDING,
    ALERT,
}

/**
 * DS Bot `state.work` 里这一轮正在做什么。
 *
 * `startedAtMs` 是从引擎拿到的回合开始时间；为 null 时界面用本地发送时间兜底。
 */
data class ChatActivity(
    val kind: ChatActivityKind,
    val label: String?,
    val startedAtMs: Long?,
    val waitingForApproval: Boolean = false,
    /** 群聊时当前正在发言的成员 id；单 Bot 为 null。 */
    val memberId: String? = null,
)

/** DS Bot `ActivityIndicator` 的状态行文案与最短换词间隔。 */
const val CHAT_ACTIVITY_PHRASE_HOLD_MS = 800L

/**
 * 单聊这一轮在跑吗（按优先级，顺序就是几年前踩过的坑）：
 *
 * 1. [sessionStatus]：引擎的会话状态（`api-session/status`），权威 —— 主机说了算；
 * 2. [optimistic]：还没收到过会话状态时的本地乐观（刚发出消息、等回执）；
 * 3. [lastTurnEnded]：会话事件里最后一条回合边界是 `turn/end`；
 * 4. [workRunning]：DS Bot 的 `state.work`（兜底）。
 */
fun botRunIsRunning(
    optimistic: Boolean,
    sessionStatus: Boolean?,
    lastTurnEnded: Boolean,
    workRunning: Boolean,
): Boolean = when {
    sessionStatus != null -> sessionStatus
    optimistic -> true
    lastTurnEnded -> false
    else -> workRunning
}

/**
 * 群聊这一轮在跑吗：本地乐观 或 引擎的 round 状态（`room-progress` / `room-chat.running`）。
 *
 * 群会话的 `api-session/status` 和 `state.work.rooms` 都出现过没清干净、把停止键和
 * 状态行钉在界面上的情况，所以都不看。
 */
fun groupRunIsRunning(optimistic: Boolean, roundRunning: Boolean): Boolean =
    optimistic || roundRunning

private val THINKING_PHRASES = listOf(
    "想一想",
    "权衡一下",
    "理一下思路",
    "梳理细节",
)

private val WORKING_PHRASES = listOf(
    "在做了",
    "正在处理",
    "推进中",
    "处理中",
    "有进展了",
)

private val TOOL_PHRASES = listOf(
    Regex("web_?search|search_?web", RegexOption.IGNORE_CASE) to "上网搜索",
    Regex("fetch|browse|browser|web", RegexOption.IGNORE_CASE) to "阅读网页",
    Regex("shell|bash|pwsh|powershell|exec|command|terminal", RegexOption.IGNORE_CASE) to "运行命令",
    Regex("^(read|view|cat)\\b|read_", RegexOption.IGNORE_CASE) to "打开文件",
    Regex("grep|glob|list_dir|^ls$|find|search", RegexOption.IGNORE_CASE) to "查找文件",
    Regex("write|edit|patch|apply|replace", RegexOption.IGNORE_CASE) to "写入修改",
    Regex("image|photo|draw", RegexOption.IGNORE_CASE) to "处理图片",
)

private val GROUP_PHRASES = mapOf(
    "create_group" to "创建群聊",
    "update_group" to "更新群聊",
    "delete_group" to "删除群聊",
    "post_to_group" to "在群里发言",
)

private val SEARCH_TOOLS = Regex(
    "search|fetch|browse|browser|read|grep|glob|list|recall|list_bots|list_secrets|read_group_chat|read_own_chat",
    RegexOption.IGNORE_CASE,
)

private val SEND_TOOLS = Regex(
    "message_bot|stop_bot|create_bot|update_bot|create_group|update_group|delete_group|post_to_group",
    RegexOption.IGNORE_CASE,
)

/** 当前状态行显示哪一句；`phraseIndex` 让每次状态切换可稳定换词。 */
fun chatActivityLabel(activity: ChatActivity?, phraseIndex: Int): String {
    if (activity == null) return ""
    activity.label?.let { return "$it…" }
    return when (activity.kind) {
        ChatActivityKind.THINKING -> "${THINKING_PHRASES[phraseIndex % THINKING_PHRASES.size]}…"
        ChatActivityKind.WORKING -> "${WORKING_PHRASES[phraseIndex % WORKING_PHRASES.size]}…"
        ChatActivityKind.SEARCHING -> "查找中…"
        ChatActivityKind.SENDING -> "正在发送…"
        ChatActivityKind.ALERT -> "等你批准…"
    }
}

/** 计时标签：对齐 DS Bot 的 `elapsedLabel`，最短显示 1s。 */
fun chatActivityElapsed(elapsedMs: Long): String {
    val total = (elapsedMs.coerceAtLeast(1_000L) / 1_000L).toInt()
    val hours = total / 3_600
    val minutes = (total / 60) % 60
    val seconds = total % 60
    return buildString {
        if (hours > 0) append(hours).append("h ")
        if (total >= 60) append(minutes).append("m ")
        append(seconds).append('s')
    }
}

/**
 * 从 `/api/bot/state` 的 `work` 字段解析当前对话的活动状态。
 *
 * 对齐 DS Bot `src/client/activity-row.js`：
 * - 有审批/提问时优先显示等待；
 * - 工具调用按工具名给具体文案；
 * - 只有回合在跑、没有别的状态时随机给一组思考短语；
 * - 计时优先用 `work.bots[botId].since` / `work.turns[sessionId]`。
 */
fun parseChatActivity(
    state: JSONObject,
    botId: String,
    sessionId: String?,
    isGroup: Boolean,
    running: Boolean,
    /** 群聊里 `room-chat` 报的当前发言人：`work.rooms` 里没有时用它认人。 */
    groupSpeakingId: String? = null,
): ChatActivity? {
    if (!running) return null
    val work = state.optJSONObject("work") ?: JSONObject()
    val approvals = work.optJSONArray("approvals")
    val waiting = ourApproval(approvals, botId, isGroup, sessionId)
    val doing = if (isGroup) {
        groupDoing(work.optJSONObject("rooms")?.optJSONArray(botId))
    } else {
        work.optJSONObject("bots")?.optJSONObject(botId)
    }
    val tool = doing?.optString("tool").orEmpty()
    val label = when {
        waiting -> "等你批准"
        doing?.optBoolean("switching") == true ->
            if (doing.optBoolean("folder")) "正在切换工作文件夹" else "整理记忆，换到新对话"
        else -> toolLabel(doing)
    }
    val kind = when {
        waiting || tool == "ask_user_question" -> ChatActivityKind.ALERT
        SEARCH_TOOLS.containsMatchIn(tool) || tool == "list_secrets" || tool == "recall" ->
            ChatActivityKind.SEARCHING
        SEND_TOOLS.containsMatchIn(tool) -> ChatActivityKind.SENDING
        tool.isNotEmpty() -> ChatActivityKind.WORKING
        else -> ChatActivityKind.THINKING
    }
    val startedAt = doing?.optLong("since")?.takeIf { it > 0L }
        ?: work.optJSONObject("turns")?.let { turns ->
            sequenceOf(sessionId.orEmpty(), botId)
                .filter { it.isNotEmpty() }
                .mapNotNull { key -> turns.optLong(key).takeIf { value -> value > 0L } }
                .firstOrNull()
        }
    return ChatActivity(
        kind = kind,
        label = label,
        startedAtMs = startedAt,
        waitingForApproval = waiting,
        memberId = if (isGroup) {
            doing?.optString("botId")?.takeIf { it.isNotEmpty() } ?: groupSpeakingId
        } else {
            null
        },
    )
}

private fun ourApproval(
    approvals: JSONArray?,
    botId: String,
    isGroup: Boolean,
    sessionId: String?,
): Boolean {
    if (approvals == null) return false
    for (index in 0 until approvals.length()) {
        val entry = approvals.optJSONObject(index) ?: continue
        val belongs = if (isGroup) {
            entry.optString("roomId") == botId
        } else {
            entry.optString("botId") == botId || entry.optString("sessionId") == sessionId
        }
        if (belongs) return true
    }
    return false
}

private fun groupDoing(members: JSONArray?): JSONObject? {
    if (members == null) return null
    return (0 until members.length())
        .mapNotNull { members.optJSONObject(it) }
        .lastOrNull { it.optBoolean("speaking") }
        ?: (0 until members.length()).mapNotNull { members.optJSONObject(it) }.lastOrNull()
}

private fun toolLabel(doing: JSONObject?): String? {
    if (doing == null) return null
    val own = listOf("said", "note")
        .firstNotNullOfOrNull { key -> doing.optString(key).takeIf { it.isNotBlank() } }
        ?.trim()
        ?.trimEnd('.', '。', '…')
        ?.takeIf { it.isNotEmpty() }
    if (own != null) return own
    val tool = doing.optString("tool")
    if (tool.isEmpty()) return null
    val label = when (tool) {
        "list_secrets" -> "查看密钥"
        "ask_user_question" -> "等你回答"
        "message_bot" -> "询问其他 Bot"
        "stop_bot" -> "叫停其他 Bot"
        "create_bot" -> "创建 Bot"
        "update_bot" -> "更新 Bot"
        "list_bots" -> "查看团队"
        "read_group_chat" -> "阅读群聊"
        "read_own_chat" -> "翻看聊天记录"
        "remember" -> "正在记下"
        "forget" -> "正在更新记忆"
        "recall" -> "正在查看记忆"
        "read_browser" -> "正在读网页"
        else -> when {
            // 浏览器动作只在活动状态栏显示，不进对话时间线。
            tool.startsWith("browser_") -> "正在操作浏览器"
            else -> GROUP_PHRASES[tool]
                ?: TOOL_PHRASES.firstOrNull { (pattern, _) -> pattern.containsMatchIn(tool) }?.second
                ?: "在做了"
        }
    }
    val target = doing.optString("target").takeIf { it.isNotBlank() }
    return if (target != null) "$label · $target" else label
}
