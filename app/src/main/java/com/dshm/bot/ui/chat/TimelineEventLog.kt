package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.TimelineRow

/**
 * 时间线的**唯一事实源**：按 (part, 显示序) 有序的会话行日志。
 *
 * 沿袭 dsh-Go `transcript.dart` 的 `_log` 模型：幂等写入、迟到/乱序自动归位、
 * 显示永远从日志全量重算（`rows()`），不依赖帧到达顺序。
 *
 * ⚠️ **排序规则（2026-11-15 修正）**：宿主的行序列**本身带显示序**——
 * - 单聊：有 seq 的行严格升序，`divider` 等无 seq 行插在中间；
 * - 群聊：**位置序**（`user:<seq>` 有 seq，relay 回复行 `bot:relay-…` **seq=null**）。
 *
 * 所以不能「有 seq 的按 seq 排、无 seq 的统一扔尾部」——那样群聊 relay 行会全被挪到
 * 后面（气泡错位、用户气泡掉出视口），私聊 divider 会跑到所有气泡下面。
 * 正确做法：**有 seq 的插到「第一个 seq 更大的行」之前；无 seq 的按到达顺序追加**，
 * 既保住宿主给的显示序，又让迟到/重放的行按 seq 归位。
 *
 * 与 dsh-Go 的差异：我们有 part（Bot 换段后 seq 空间独立），故按下标分段隔离。
 */
internal class TimelineEventLog {

    /** 每个 part（会话 id）一份有序列表；LinkedHashMap 保留 part 的先后。 */
    private val parts = LinkedHashMap<String, MutableList<TimelineRow>>()

    /** 当前（最新）part 的 key：增量帧写这里。 */
    private var currentPart: String = DEFAULT_PART

    /** earlier 分页前插的页：整页保持宿主给的顺序，排在所有 part 之前。 */
    private val earlierPages = ArrayList<List<TimelineRow>>()

    /**
     * 本地撤回的行 key（对齐 dsh-Go `_withdrawn`）：撤回的排队项即便被重放带回来也不显示；
     * 只有服务端**重新**给出该行（again put 同 key）时才算撤回失效，恢复显示。
     */
    private val withdrawn = HashSet<String>()

    /** 非 resync 快照：清空重建（对齐 dsh-Go 的 `_log.clear()`）。 */
    fun reset(rows: List<TimelineRow>, part: String = DEFAULT_PART) {
        parts.clear()
        earlierPages.clear()
        withdrawn.clear()
        currentPart = part
        merge(rows, part)
    }

    /** 快照 / since 重放：**幂等合并**，不清空已收到的行（丢帧补齐的关键）。 */
    fun merge(rows: List<TimelineRow>, part: String = currentPart) {
        if (rows.isEmpty()) return
        currentPart = part
        for (row in rows) put(part, row)
    }

    /** 增量帧的一行：幂等写入当前 part。 */
    fun put(row: TimelineRow) = put(currentPart, row)

    /** 显式指定 part 的幂等写入（快照/重放用）。 */
    fun put(part: String, row: TimelineRow) {
        // 服务端又重新给出这一行：撤回失效，恢复显示。
        withdrawn.remove(row.key)
        val list = parts.getOrPut(part) { ArrayList() }
        // 幂等：同 key（或同 seq）的行先移除，再插到新位置。
        val existing = list.indexOfFirst { it.key == row.key || (row.seq != null && it.seq == row.seq) }
        if (existing >= 0) list.removeAt(existing)
        val seq = row.seq
        if (seq == null) {
            // 无 seq：尊重宿主给的显示序（群聊位置序 / 私聊 divider）。
            list.add(row)
            return
        }
        val at = list.indexOfFirst { it.seq != null && it.seq > seq }
        if (at >= 0) list.add(at, row) else list.add(row)
    }

    /** 本地撤回一行（排队项撤回 / 乐观摘行）：rebuild 时不再显示。 */
    fun withdraw(key: String) {
        withdrawn += key
        parts.values.forEach { list -> list.removeAll { it.key == key } }
    }

    /**
     * earlier 分页：整页前插，保持宿主给的显示序。
     * 不按 seq 跨 part 排序（part 之间 seq 空间独立）。
     */
    fun prependPage(rows: List<TimelineRow>) {
        if (rows.isEmpty()) return
        earlierPages.add(0, rows)
    }

    /** 全量重算：earlier 页 → 各 part（已按上面的规则有序）。 */
    fun rows(): List<TimelineRow> {
        val out = ArrayList<TimelineRow>()
        for (page in earlierPages) out.addAll(page)
        for (list in parts.values) out.addAll(list)
        return if (withdrawn.isEmpty()) out else out.filterNot { it.key in withdrawn }
    }

    /** 当前 part 的最大 seq（since 重放游标用；earlier 页不计入）。 */
    fun maxSeq(): Long? = parts[currentPart]?.mapNotNull { it.seq }?.maxOrNull()

    /** 当前行数（诊断/测试用）。 */
    fun size(): Int = rows().size

    /** 清空（引擎换端点 / 会话切换）。 */
    fun clear() {
        parts.clear()
        earlierPages.clear()
        withdrawn.clear()
        currentPart = DEFAULT_PART
    }

    private companion object {
        const val DEFAULT_PART = "current"
    }
}
