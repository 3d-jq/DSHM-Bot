package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 1a 统一时间线的行模型与解析（schema 见 `docs/phase1-design.md` §2，冻结稿）。
 *
 * 行由宿主插件的 `projection.js` 产出：快照走 `/api/bot` `timeline` 端点、增量走
 * feed SSE 的 `timeline` 事件（§3）。客户端只做解析 + 渲染，投影语义全在宿主。
 *
 * 解析容错的两条纪律：
 * - **缺字段回落**：单个字段缺失/类型不符用默认值顶住（旧插件、字段演进都不崩）；
 * - **形状不对整行丢弃**：payload 存在但不是对象、必缺的关键字段（如 key 为空）这类
 *   整行级残缺直接丢行，绝不抛进渲染管线。未知 kind 一律忽略（向前兼容）。
 */

/** 时间线的一行。 */
data class TimelineRow(
    /** 数据键，稳定、可去重（`user:<seq>` / `present:<turn>` / `inbox:<itemId>` …）。 */
    val key: String,
    /** 行种类；[KINDS] 之外的行解析器直接丢（向前兼容）。 */
    val kind: String,
    /** 会话日志 seq；echo/inbox 行为 null。 */
    val seq: Long?,
    /** 归属轮。 */
    val turn: Int?,
    /** 事件时间；行排序不靠它，只用于显示/分隔线。 */
    val timeMs: Long?,
    val payload: TimelineRowPayload,
) {
    companion object {
        const val KIND_USER = "user"
        const val KIND_BOT = "bot"
        const val KIND_TOOL = "tool"
        const val KIND_ARTIFACT = "artifact"
        const val KIND_EVENT = "event"
        const val KIND_DIVIDER = "divider"
        const val KIND_VIEW = "view"
        const val KIND_ANSWERED = "answered"
        const val KIND_INBOX = "inbox"

        /** 已知的全部 kind；`turntail` / `streaming` 这类 1c 才发的行也先认下（raw 载荷）。 */
        val KINDS = setOf(
            KIND_USER, KIND_BOT, KIND_TOOL, KIND_ARTIFACT, KIND_EVENT, KIND_DIVIDER,
            KIND_VIEW, KIND_ANSWERED, KIND_INBOX, "turntail", "streaming",
        )
    }
}

/** 产物卡里的一个文件。 */
data class TimelineFile(
    val path: String,
    val description: String?,
)

/** 按 kind 定形状的行载荷；未知字段客户端必须忽略（向前兼容）。 */
sealed interface TimelineRowPayload {
    /** 附件块在前文本在后（宪章 ⑤）。 */
    data class User(
        val text: String,
        val blocks: List<JSONObject>,
        val images: Int,
        /** `source.rpcId`：回显认领用（设计 §2 key 规则 1）。 */
        val requestId: String?,
        /** 群聊 live 行（round 进行中的回复，同 key settled 后原位替换）。 */
        val live: Boolean,
    ) : TimelineRowPayload

    data class Bot(
        val text: String,
        val author: String?,
        val botId: String?,
        val durationMs: Long?,
        /** 长文附件（`whole`），原样保留，1b 再映射内部模型。 */
        val whole: JSONObject?,
        /** 群聊 live 行：round 进行中的回复占位，settled 后同 key 原位替换。 */
        val live: Boolean,
    ) : TimelineRowPayload

    /** `state`: running / ok / error。 */
    data class Tool(
        val tool: String,
        val state: String,
        val label: String,
        val target: String?,
        val callId: String?,
    ) : TimelineRowPayload

    data class Artifact(
        val files: List<TimelineFile>,
        val delivered: Boolean,
        val turn: Int?,
    ) : TimelineRowPayload

    data class Event(
        val eventKind: String,
        val code: String?,
        val botId: String?,
        val name: String?,
        /** 载荷原样（text 等扩展字段从这里读，向前兼容）。 */
        val raw: JSONObject,
    ) : TimelineRowPayload

    data class Divider(val timeMs: Long?) : TimelineRowPayload

    /** `view` / `answered` 的载荷原样保留（对齐现有 `ChatViewCard` / `ChatAnsweredQuestion` 形状）。 */
    data class View(val view: JSONObject) : TimelineRowPayload

    data class Answered(val answered: JSONObject) : TimelineRowPayload

    /** 排队/插话行：seq=null，客户端只追加在已知 seq 段的尾部。 */
    data class Inbox(
        val text: String,
        /** `next-turn` / `next-step`。 */
        val slot: String,
        val requestId: String?,
        val itemId: String,
    ) : TimelineRowPayload

    /** 已认识但 1a 不展开的 kind（`turntail` 等）：载荷原样带走，不丢行。 */
    data class Raw(val json: JSONObject) : TimelineRowPayload
}

/** `timeline` 端点的请求（快照 / seq 游标重放共用）。 */
data class TimelineRequest(
    val botId: String? = null,
    val roomId: String? = null,
    /** 增量重放：只回 seq > since 的行。 */
    val since: Long? = null,
    /** earlier 分页游标（对齐 `beforeSeq`）。 */
    val cursor: JSONObject? = null,
    val max: Int? = null,
) {
    fun toPayload(): JSONObject {
        val payload = JSONObject()
        botId?.takeIf { it.isNotEmpty() }?.let { payload.put("botId", it) }
        roomId?.takeIf { it.isNotEmpty() }?.let { payload.put("roomId", it) }
        since?.let { payload.put("since", it) }
        cursor?.let { payload.put("cursor", it) }
        max?.let { payload.put("max", it) }
        return payload
    }
}

/** `timeline` 端点的响应（`docs/phase1-design.md` §3 快照帧）。 */
data class TimelineSnapshot(
    val sessionId: String?,
    val roomId: String?,
    /** 服务端当前最大 seq。 */
    val seq: Long?,
    val rows: List<TimelineRow>,
    /** 还有更早历史的分页游标；null = 没有了。 */
    val cursor: JSONObject?,
    /** 群聊才带：round 在跑吗、谁在发言。 */
    val running: Boolean?,
    val speaking: String?,
)

/** feed `timeline` 增量帧：`{botId|roomId, seq, rows}` 或 `{"reset":true}`。 */
data class TimelineFeedFrame(
    val reset: Boolean,
    val botId: String?,
    val roomId: String?,
    val seq: Long?,
    val rows: List<TimelineRow>,
    /**
     * 群聊帧的 round 状态（单聊缺席）：running 的 true→false 边沿 = 本轮收口，
     * 客户端重拉一次快照把落定行对齐（群聊不画产物卡，2026-11-14 对齐官方）。
     */
    val running: Boolean?,
    val speaking: String?,
)

/** 解析一行；残缺/未知 kind 返回 null（调用方跳过，不抛）。 */
fun parseTimelineRow(json: JSONObject?): TimelineRow? {
    if (json == null) return null
    val key = json.optString("key").trim()
    val kind = json.optString("kind").trim()
    if (key.isEmpty() || kind.isEmpty() || kind !in TimelineRow.KINDS) return null
    // payload 存在但不是对象：形状不对，整行丢弃。
    val payloadJson = if (json.has("payload") && !json.isNull("payload")) {
        json.optJSONObject("payload") ?: return null
    } else {
        JSONObject()
    }
    val payload = runCatching { parseRowPayload(kind, payloadJson) }.getOrNull() ?: return null
    return TimelineRow(
        key = key,
        kind = kind,
        seq = optionalLong(json, "seq"),
        turn = optionalInt(json, "turn"),
        timeMs = optionalLong(json, "timeMs") ?: optionalLong(json, "time"),
        payload = payload,
    )
}

/** 解析行数组；逐行容错，坏行跳过。 */
fun parseTimelineRows(array: JSONArray?): List<TimelineRow> {
    if (array == null) return emptyList()
    val rows = ArrayList<TimelineRow>(array.length())
    for (index in 0 until array.length()) {
        parseTimelineRow(array.optJSONObject(index))?.let(rows::add)
    }
    return rows
}

/** 解析 `timeline` 端点的响应；响应骨架不对返回 null。 */
fun parseTimelineSnapshot(json: JSONObject?): TimelineSnapshot? {
    if (json == null) return null
    return runCatching {
        // rows 存在但不是数组：骨架残缺，整体作废（调用方决定重拉/报错）。
        if (json.has("rows") && !json.isNull("rows") && json.optJSONArray("rows") == null) {
            error("timeline 响应的 rows 不是数组")
        }
        TimelineSnapshot(
            sessionId = json.optString("sessionId").takeIf { it.isNotEmpty() },
            roomId = json.optString("roomId").takeIf { it.isNotEmpty() },
            seq = optionalLong(json, "seq"),
            rows = parseTimelineRows(json.optJSONArray("rows")),
            cursor = json.optJSONObject("cursor"),
            running = if (json.has("running") && !json.isNull("running")) json.optBoolean("running") else null,
            speaking = json.optString("speaking").takeIf { it.isNotEmpty() },
        )
    }.getOrNull()
}

/** 解析 feed 的 `timeline` 增量帧；不是对象或骨架残缺返回 null。 */
fun parseTimelineFeedFrame(data: JSONObject?): TimelineFeedFrame? {
    if (data == null) return null
    return runCatching {
        val reset = data.optBoolean("reset", false)
        if (reset) {
            // reset 帧：行流追不上了，客户端丢缓冲重拉快照；行字段可以缺席。
            TimelineFeedFrame(
                reset = true, botId = null, roomId = null, seq = null,
                rows = emptyList(), running = null, speaking = null,
            )
        } else {
            // 非 reset 帧的 rows 存在但不是数组：骨架残缺，整体作废。
            if (data.has("rows") && !data.isNull("rows") && data.optJSONArray("rows") == null) {
                error("timeline 增量帧的 rows 不是数组")
            }
            TimelineFeedFrame(
                reset = false,
                botId = data.optString("botId").takeIf { it.isNotEmpty() },
                roomId = data.optString("roomId").takeIf { it.isNotEmpty() },
                seq = optionalLong(data, "seq"),
                rows = parseTimelineRows(data.optJSONArray("rows")),
                running = if (data.has("running") && !data.isNull("running")) data.optBoolean("running") else null,
                speaking = data.optString("speaking").takeIf { it.isNotEmpty() },
            )
        }
    }.getOrNull()
}

/** 各 kind 的载荷解析；字段缺失一律回落默认值。 */
private fun parseRowPayload(kind: String, payload: JSONObject): TimelineRowPayload = when (kind) {
    TimelineRow.KIND_USER -> TimelineRowPayload.User(
        text = payload.optString("text"),
        blocks = payload.optJSONArray("blocks").orEmpty().objectList(),
        images = payload.optInt("images", 0),
        requestId = payload.optString("requestId").takeIf { it.isNotEmpty() },
        live = payload.optBoolean("live", false),
    )

    TimelineRow.KIND_BOT -> TimelineRowPayload.Bot(
        text = payload.optString("text"),
        author = payload.optString("author").takeIf { it.isNotEmpty() },
        botId = payload.optString("botId").takeIf { it.isNotEmpty() },
        durationMs = optionalLong(payload, "durationMs"),
        whole = payload.optJSONObject("whole"),
        live = payload.optBoolean("live", false),
    )

    TimelineRow.KIND_TOOL -> TimelineRowPayload.Tool(
        tool = payload.optString("tool"),
        state = payload.optString("state").ifEmpty { "ok" },
        label = payload.optString("label"),
        target = payload.optString("target").takeIf { it.isNotEmpty() },
        callId = payload.optString("callId").takeIf { it.isNotEmpty() },
    )

    TimelineRow.KIND_ARTIFACT -> TimelineRowPayload.Artifact(
        files = payload.optJSONArray("files").orEmpty().fileList(),
        delivered = payload.optBoolean("delivered", false),
        turn = optionalInt(payload, "turn"),
    )

    TimelineRow.KIND_EVENT -> TimelineRowPayload.Event(
        eventKind = payload.optString("eventKind"),
        code = payload.optString("code").takeIf { it.isNotEmpty() },
        botId = payload.optString("botId").takeIf { it.isNotEmpty() },
        name = payload.optString("name").takeIf { it.isNotEmpty() },
        raw = payload,
    )

    TimelineRow.KIND_DIVIDER -> TimelineRowPayload.Divider(
        timeMs = optionalLong(payload, "timeMs") ?: optionalLong(payload, "time"),
    )

    TimelineRow.KIND_VIEW -> TimelineRowPayload.View(payload)
    TimelineRow.KIND_ANSWERED -> TimelineRowPayload.Answered(payload)
    TimelineRow.KIND_INBOX -> TimelineRowPayload.Inbox(
        text = payload.optString("text"),
        slot = payload.optString("slot").ifEmpty { "next-turn" },
        requestId = payload.optString("requestId").takeIf { it.isNotEmpty() },
        itemId = payload.optString("itemId"),
    )

    // turntail / streaming 等 1c 才展开的行：原样保留。
    else -> TimelineRowPayload.Raw(payload)
}

/** 可空的 long 字段：缺席 / null / 负值都回落 null。 */
private fun optionalLong(json: JSONObject, name: String): Long? {
    if (!json.has(name) || json.isNull(name)) return null
    val value = json.optLong(name, Long.MIN_VALUE)
    return value.takeIf { it >= 0L }
}

/** 可空的 int 字段：缺席 / null / 负值都回落 null。 */
private fun optionalInt(json: JSONObject, name: String): Int? =
    optionalLong(json, name)?.toInt()

private fun JSONArray.objectList(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray.fileList(): List<TimelineFile> =
    objectList().mapNotNull { file ->
        val path = file.optString("path").trim()
        if (path.isEmpty()) {
            null
        } else {
            TimelineFile(path = path, description = file.optString("description").takeIf { it.isNotEmpty() })
        }
    }

private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()
