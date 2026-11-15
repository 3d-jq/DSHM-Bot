package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.SessionEvent
import com.dshm.bot.data.remote.SessionEventLog
import com.dshm.bot.data.remote.TimelineRow
import com.dshm.bot.data.remote.TimelineRowPayload

/**
 * 四期 4c 影子双轨：把「客户端自折叠」与「宿主行投影」**同一批事件**上逐行比对。
 *
 * 为什么要影子：4c 要把显示从宿主行投影切到客户端自折叠。直接切 = 拿真机当试验品。
 * 影子模式让两套实现**同时跑、只记差异**，真机跑一天都能看到差异日志——
 * 全绿了才切（这正是 dsh-Go 用 oracle 差分锁住「增量 == 全量」的同一思路）。
 *
 * 比对口径：
 * - **同一批事件**：宿主快照回 `eventFrom/eventTo`（这批行覆盖的事件区间），
 *   客户端据此从自己的事件日志里取**同一段**；否则比的不是同一批事件，差异全是假的。
 * - 只比**私聊**的行类型（user/bot/tool/event/changes/artifact）；inbox/divider/view
 *   不在客户端折叠范围内（排队行来自 agent inbox，不是事件；分隔行来自 part 边界）。
 * - 按 **key** 对齐（行身份），逐个比 payload 的关键字段。
 */
internal object ShadowDiff {

    /** 比对结果；空 = 完全一致。 */
    data class Result(
        /** 宿主有、客户端折叠没有的 key。 */
        val missingInClient: List<String>,
        /** 客户端折叠有、宿主没有的 key。 */
        val extraInClient: List<String>,
        /** key 相同但内容不同的（key → 差异说明）。 */
        val payloadMismatch: Map<String, String>,
    ) {
        val identical: Boolean
            get() = missingInClient.isEmpty() && extraInClient.isEmpty() && payloadMismatch.isEmpty()

        fun summary(): String = buildString {
            append("影子比对：")
            if (identical) {
                append("一致")
                return@buildString
            }
            if (missingInClient.isNotEmpty()) append("宿主有客户端无=[${missingInClient.take(5).joinToString(",")}] ")
            if (extraInClient.isNotEmpty()) append("客户端有宿主无=[${extraInClient.take(5).joinToString(",")}] ")
            payloadMismatch.forEach { (key, why) -> append("$key:$why ") }
        }
    }

    /** 比对的行类型（私聊折叠覆盖的那些）。 */
    private val COMPARED_KINDS = setOf(
        TimelineRow.KIND_USER,
        TimelineRow.KIND_BOT,
        TimelineRow.KIND_TOOL,
        TimelineRow.KIND_EVENT,
        TimelineRow.KIND_CHANGES,
        TimelineRow.KIND_ARTIFACT,
        // 这三类曾经折不出来、被排除在比对之外，于是「oracle 全绿」掩盖了它们整类
        // 丢失（真机看不到「你回答过的问题」/ 只读视图卡 / Bot 互答行）。折叠补齐后
        // 必须纳入比对，否则同样的问题还会再犯。
        TimelineRow.KIND_VIEW,
        TimelineRow.KIND_ANSWERED,
    )

    /**
     * 比对宿主行与客户端折叠行。
     *
     * @param hostRows 宿主行投影的结果（同一批事件）。
     * @param clientRows 客户端自折叠的结果（同一批事件）。
     */
    fun compare(hostRows: List<TimelineRow>, clientRows: List<TimelineRow>): Result {
        val host = hostRows.filter { it.kind in COMPARED_KINDS }.associateBy { it.key }
        val client = clientRows.filter { it.kind in COMPARED_KINDS }.associateBy { it.key }
        val missing = host.keys.filter { it !in client }
        val extra = client.keys.filter { it !in host }
        val mismatch = LinkedHashMap<String, String>()
        for ((key, hostRow) in host) {
            val clientRow = client[key] ?: continue
            val why = differenceOf(hostRow, clientRow)
            if (why != null) mismatch[key] = why
        }
        return Result(missing, extra, mismatch)
    }

    /** 同一 key 的两行差在哪（返回 null = 一致）。 */
    private fun differenceOf(host: TimelineRow, client: TimelineRow): String? {
        if (host.seq != client.seq) return "seq ${host.seq}≠${client.seq}"
        if (host.turn != client.turn) return "turn ${host.turn}≠${client.turn}"
        if (host.kind != client.kind) return "kind ${host.kind}≠${client.kind}"
        return when (val hp = host.payload) {
            is TimelineRowPayload.User -> {
                val cp = client.payload as? TimelineRowPayload.User
                    ?: return "载荷类型不同（${client.payload::class.java.simpleName}）"
                if (hp.text != cp.text) return "text 不一致"
                if (hp.images != cp.images) return "images ${hp.images}≠${cp.images}"
                if (hp.requestId != cp.requestId) return "requestId ${hp.requestId}≠${cp.requestId}"
                null
            }

            is TimelineRowPayload.Bot -> {
                val cp = client.payload as? TimelineRowPayload.Bot
                    ?: return "载荷类型不同（${client.payload::class.java.simpleName}）"
                if (hp.text != cp.text) return "text 不一致"
                null
            }

            is TimelineRowPayload.Tool -> {
                val cp = client.payload as? TimelineRowPayload.Tool
                    ?: return "载荷类型不同（${client.payload::class.java.simpleName}）"
                if (hp.state != cp.state) return "state ${hp.state}≠${cp.state}"
                if (hp.label != cp.label) return "label「${hp.label}」≠「${cp.label}」"
                if (hp.target != cp.target) return "target ${hp.target}≠${cp.target}"
                null
            }

            is TimelineRowPayload.Event -> {
                val cp = client.payload as? TimelineRowPayload.Event
                    ?: return "载荷类型不同（${client.payload::class.java.simpleName}）"
                if (hp.eventKind != cp.eventKind) return "eventKind ${hp.eventKind}≠${cp.eventKind}"
                if (hp.code != cp.code) return "code ${hp.code}≠${cp.code}"
                val hostText = hp.raw.optString("text")
                val clientText = cp.raw.optString("text")
                if (hostText != clientText) return "text「$hostText」≠「$clientText」"
                null
            }

            is TimelineRowPayload.Changes -> null

            is TimelineRowPayload.Artifact -> {
                val cp = client.payload as? TimelineRowPayload.Artifact
                    ?: return "载荷类型不同（${client.payload::class.java.simpleName}）"
                if (hp.delivered != cp.delivered) return "delivered ${hp.delivered}≠${cp.delivered}"
                val hostFiles = hp.files.map { it.path }
                val clientFiles = cp.files.map { it.path }
                if (hostFiles != clientFiles) return "文件 ${hostFiles}≠${clientFiles}"
                null
            }

            is TimelineRowPayload.View -> {
                val cp = client.payload as? TimelineRowPayload.View
                    ?: return "载荷类型不同（${client.payload::class.java.simpleName}）"
                if (hp.view.optString("title") != cp.view.optString("title")) return "view.title 不一致"
                if (hp.view.optString("callId") != cp.view.optString("callId")) return "view.callId 不一致"
                if (hp.view.optJSONObject("view")?.toString() != cp.view.optJSONObject("view")?.toString()) {
                    return "view 内容不一致"
                }
                null
            }

            is TimelineRowPayload.Answered -> {
                val cp = client.payload as? TimelineRowPayload.Answered
                    ?: return "载荷类型不同（${client.payload::class.java.simpleName}）"
                if (hp.answered.toString() != cp.answered.toString()) return "answered 内容不一致"
                null
            }

            else -> null
        }
    }

    /**
     * 从事件日志取**宿主那批事件**对应的区间（影子比对用）。
     *
     * 区间不完整（本地日志缺这段）时返回 null —— 那就不比（比了也是假的差异）。
     */
    fun eventsForRange(log: SessionEventLog, from: Long, to: Long): List<SessionEvent>? {
        val events = log.eventsIn(from, to)
        if (events.isEmpty()) return null
        // 区间必须**连续**且首尾对上，否则本地日志有洞，比出来的差异是假的。
        if (events.first().seq != from || events.last().seq != to) return null
        for (index in 1 until events.size) {
            if (events[index].seq != events[index - 1].seq + 1) return null
        }
        return events
    }
}
