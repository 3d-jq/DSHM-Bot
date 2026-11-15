package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.TimelineRow
import java.util.TreeMap

/**
 * 时间线的**唯一事实源**：按 (part, seq) 有序的会话行日志。
 *
 * 沿袭 dsh-Go `transcript.dart` 的 `SplayTreeMap<int, SessionLogEvent>` 模型：
 * - 幂等写入：同 seq 覆盖，迟到/乱序由 TreeMap 自动归位，不依赖帧到达顺序
 * - 显示永远从日志全量重算（`rows()`），而不是「收一行存一行」的插入序
 * - 任何一次通信（快照 / 增量 / 重连 / earlier）把某行带回来，写进日志就出现在正确位置
 *
 * 与 dsh-Go 的差异：**我们有 part**（Bot 换段后 seq 空间独立，见 `ChatCursor`）。
 * 所以日志按 part 分段，段内按 seq 升序，earlier 页整页前插、不跨 part 排序。
 *
 * 纯 Kotlin、无 Android 依赖，方便 JVM 单测锁住一致性语义。
 */
internal class TimelineEventLog {

    /** 每个 part（会话 id）一份按 seq 升序的日志；LinkedHashMap 保留 part 的先后。 */
    private val parts = LinkedHashMap<String, TreeMap<Long, TimelineRow>>()

    /** 无 seq 的行（inbox / divider 等）按宿主给的位置序挂在所属 part 的尾部。 */
    private val tails = LinkedHashMap<String, LinkedHashMap<String, TimelineRow>>()

    /** 当前（最新）part 的 key：增量帧写这里。 */
    private var currentPart: String = DEFAULT_PART

    /**
     * 本地撤回的行 key（对齐 dsh-Go `_withdrawn`）：撤回的排队项即便被重放带回来也不显示；
     * 只有服务端**重新**给出该行（again put 同 key）时才算撤回失效，恢复显示。
     */
    private val withdrawn = HashSet<String>()

    /** earliest 之前前插的页：整页保持宿主给的顺序，排在所有 part 之前。 */
    private val earlierPages = ArrayList<List<TimelineRow>>()

    /** 非 resync 快照：清空重建（对齐 dsh-Go 的 `_log.clear()`）。 */
    fun reset(rows: List<TimelineRow>, part: String = DEFAULT_PART) {
        parts.clear()
        tails.clear()
        earlierPages.clear()
        currentPart = part
        merge(rows, part)
    }

    /**
     * 快照 / since 重放：**按 seq 幂等合并**，不清空已收到的行。
     * 这是「丢帧后重放补齐」的关键 —— 重放回来的行与本地已有行合并，不会重复或倒退。
     */
    fun merge(rows: List<TimelineRow>, part: String = currentPart) {
        if (rows.isEmpty()) return
        currentPart = part
        for (row in rows) put(part, row)
    }

    /** 增量帧的一行：幂等写入当前 part。 */
    fun put(row: TimelineRow) = put(currentPart, row)

    /** 本地撤回一行（排队项撤回 / 乐观摘行）：rebuild 时不再显示。 */
    fun withdraw(key: String) {
        withdrawn += key
        parts.values.forEach { log -> log.values.removeIf { it.key == key } }
        tails.values.forEach { tail -> tail.remove(key) }
    }

    /** 显式指定 part 的幂等写入（快照/重放用）。 */
    fun put(part: String, row: TimelineRow) {
        // 服务端又重新给出这一行：撤回失效，恢复显示。
        withdrawn.remove(row.key)
        val seq = row.seq
        if (seq == null) {
            val tail = tails.getOrPut(part) { LinkedHashMap() }
            tail[row.key] = row
            return
        }
        // 同 seq 覆盖（幂等）；TreeMap 自动按 seq 排序，迟到/乱序都归位。
        parts.getOrPut(part) { TreeMap() }[seq] = row
        // 该行可能此前被当成无 seq 行挂在 tail（例如 late echo），清掉避免重复。
        tails[part]?.remove(row.key)
    }

    /**
     * earlier 分页：整页前插，保持宿主给的显示序。
     * 不按 seq 跨 part 排序（part 之间 seq 空间独立），也不与现有 part 合并去重——
     * 宿主按 part 边界切页，重复页由调用方 `cursor` 防重入。
     */
    fun prependPage(rows: List<TimelineRow>) {
        if (rows.isEmpty()) return
        earlierPages.add(0, rows)
    }

    /**
     * 全量重算：earlier 页 → 各 part（段内按 seq 升序）→ 各 part 的无 seq 尾部行。
     * 调用方每次状态变化都从这里重新派生 entries，不再维护独立的显示缓冲顺序。
     */
    fun rows(): List<TimelineRow> {
        val out = ArrayList<TimelineRow>()
        for (page in earlierPages) out.addAll(page)
        for ((part, log) in parts) {
            out.addAll(log.values)
            tails[part]?.values?.let(out::addAll)
        }
        // 只有 tail 没有 seq 行的 part（例如纯 inbox 页）也要出。
        for ((part, tail) in tails) {
            if (part !in parts) out.addAll(tail.values)
        }
        return if (withdrawn.isEmpty()) out else out.filterNot { it.key in withdrawn }
    }

    /** 当前 part 的最大 seq（since 重放游标用；earlier 页不计入，它们是更早段）。 */
    fun maxSeq(): Long? = parts[currentPart]?.lastKey()

    /** 当前行数（诊断/测试用）。 */
    fun size(): Int = rows().size

    /** 清空（引擎换端点 / 会话切换）。 */
    fun clear() {
        parts.clear()
        tails.clear()
        earlierPages.clear()
        withdrawn.clear()
        currentPart = DEFAULT_PART
    }

    private companion object {
        const val DEFAULT_PART = "current"
    }
}
