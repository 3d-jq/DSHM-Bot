package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeMap

/**
 * 四期 4a：durable Session 事件与客户端事件日志（对齐 dsh-Go `transcript.dart`）。
 *
 * 为什么要有这一层：客户端此前消费的是宿主投影后的「行」，行的 seq 是显示序、
 * **不连续**（divider 无 seq、群聊 relay 行 seq=null、part 之间独立），所以用不了
 * dsh-Go 的铁律 `seq == lastSeq + 1`，只能靠 `from/bufferFrom` 软信号 + 各种兜底，
 * 反复出「用着用着乱、冷启动才好」。
 *
 * 这里把**原始 durable 事件的连续 seq** 作为完整性判据：重复丢弃、连续追加、
 * 缺口补洞（宿主 `events` 端点）→ 补不齐就等下一次全量重读。4a 只建立这层地基，
 * 渲染仍走现有行管线；4b 再把折叠搬到客户端。
 *
 * 纯 JVM（无 Android 依赖），可直接单测。
 */

/** 一条 durable Session 事件。`data` 只对折叠需要的类型携带（其余为 null 占位）。 */
data class SessionEvent(
    val seq: Long,
    val type: String,
    val timeMs: Long?,
    val data: JSONObject?,
)

/** feed 的 `session` 帧：哪条会话的哪条事件。 */
data class SessionEventFrame(
    val sessionId: String,
    val event: SessionEvent,
)

private fun optionalLong(json: JSONObject, name: String): Long? {
    if (!json.has(name) || json.isNull(name)) return null
    val value = json.optLong(name, Long.MIN_VALUE)
    return value.takeIf { it >= 0L }
}

/** 解析 feed 的 `session` 增量帧；缺 sessionId / seq 时返回 null（形状不对就丢）。 */
fun parseSessionEventFrame(data: JSONObject?): SessionEventFrame? {
    if (data == null) return null
    val sessionId = data.optString("sessionId")
    if (sessionId.isEmpty()) return null
    val seq = optionalLong(data, "seq") ?: return null
    return SessionEventFrame(
        sessionId = sessionId,
        event = SessionEvent(
            seq = seq,
            type = data.optString("type"),
            timeMs = optionalLong(data, "time"),
            data = if (data.has("data") && !data.isNull("data")) data.optJSONObject("data") else null,
        ),
    )
}

/** 解析 `events` 端点应答的 `events` 数组（升序）。 */
fun parseSessionEventsResponse(json: JSONObject?): List<SessionEvent> {
    val array: JSONArray = json?.optJSONArray("events") ?: return emptyList()
    val out = ArrayList<SessionEvent>(array.length())
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val seq = optionalLong(item, "seq") ?: continue
        out += SessionEvent(
            seq = seq,
            type = item.optString("type"),
            timeMs = optionalLong(item, "time"),
            data = if (item.has("data") && !item.isNull("data")) item.optJSONObject("data") else null,
        )
    }
    return out.sortedBy { it.seq }
}

/**
 * 每会话一条按 `seq` 排序的 durable 事件日志 + **连续游标**。
 *
 * 不变量：`cursor` 是「从种子开始、一路没有洞」的最后一个 seq。
 * - 重复/旧事件（`seq <= cursor`）只覆盖内容，**不推进**游标；
 * - 正好接上（`seq == cursor + 1`）→ 写入并推进游标（继续吃掉已缓存的后续 seq）；
 * - 跳号（`seq > cursor + 1`）→ 写入但**不推进**，`put` 返回缺口区间，调用方去补洞。
 *
 * 与 dsh-Go 的差异：dsh-Go 的 watch 层保证「投递前已无洞」，所以 transcript 可以
 * 直接 `cursor = seq`；我们的事件是宿主转发的，可能丢帧，所以游标必须由「连续覆盖」
 * 决定，而不是「见过的最大 seq」。
 */
class SessionEventLog {

    private val events = TreeMap<Long, SessionEvent>()

    /** 从种子开始连续覆盖到的最后一个 seq；-1 表示还没有种子。 */
    var cursor: Long = -1L
        private set

    val size: Int get() = events.size

    fun isEmpty(): Boolean = events.isEmpty()

    fun oldestSeq(): Long? = events.keys.firstOrNull()

    fun newestSeq(): Long? = events.keys.lastOrNull()

    /**
     * 幂等写入一条事件。
     *
     * @return 需要补洞的区间（含端点）；没有缺口返回 null。
     */
    fun put(event: SessionEvent): LongRange? {
        events[event.seq] = event
        if (cursor < 0L) cursor = event.seq - 1L
        advance()
        return if (event.seq > cursor + 1L) (cursor + 1L)..(event.seq - 1L) else null
    }

    /** 合并一批事件（补洞结果 / 快照窗口），然后尽量推进连续游标。 */
    fun merge(list: List<SessionEvent>) {
        if (list.isEmpty()) return
        for (event in list) events[event.seq] = event
        if (cursor < 0L) cursor = events.keys.first() - 1L
        advance()
    }

    /** 全量重设（换段 / 重新同步）：清空后以这批事件为种子。 */
    fun reset(list: List<SessionEvent>) {
        events.clear()
        cursor = -1L
        merge(list)
    }

    /** `[from, through]` 内已缓存的事件（升序），供 4b 折叠/诊断。 */
    fun eventsIn(from: Long, through: Long?): List<SessionEvent> {
        val head = events.ceilingKey(from) ?: return emptyList()
        val tail = if (through == null) events.lastKey() else events.floorKey(through) ?: return emptyList()
        if (head > tail) return emptyList()
        return events.subMap(head, true, tail, true).values.toList()
    }

    fun clear() {
        events.clear()
        cursor = -1L
    }

    private fun advance() {
        while (true) {
            val next = cursor + 1L
            if (!events.containsKey(next)) return
            cursor = next
        }
    }
}
