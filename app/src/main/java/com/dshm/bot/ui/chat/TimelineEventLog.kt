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
 * 正确做法：**已经存在的行（同 key / 同 seq）原地覆盖、绝不挪位**；新出现的行才
 * 「有 seq 的插到第一个 seq 更大的行之前、无 seq 的按到达顺序追加」。这样既保住宿主
 * 给的显示序，又让迟到/重放的新行按 seq 归位，且**整页重放不会打乱已有顺序**。
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
        insertRow(parts.getOrPut(part) { ArrayList() }, row)
    }

    /** 单行有序插入的本体（幂等、同身份原地覆盖、新行按 seq 归位）。 */
    private fun insertRow(list: MutableList<TimelineRow>, row: TimelineRow) {
        // 同 key（或同 seq）已存在 → **原地覆盖，绝不挪位**。
        //
        // 这是「用着用着气泡乱、冷启动又正常」的根：运行期会整页重放（room 刷新、
        // 轮末收口、宿主 reset 帧）。若沿用「先删再插」，每个有 seq 的行都会被重新
        // 插到「第一个 seq 更大的行」之前——一步跨过夹在中间的无 seq 群聊回复行，
        // 于是每重放一次就错位一次；冷启动只有一次快照，所以看不出来。
        // 真正迟到/乱序的行是**新身份**，仍按下面的规则归位。
        val existing = list.indexOfFirst { it.key == row.key || (row.seq != null && it.seq == row.seq) }
        if (existing >= 0) {
            // 身份相同但 seq 从无到有（live → settled）：位置要按 seq 重新归位。
            if (list[existing].seq == row.seq) {
                list[existing] = row
                return
            }
            list.removeAt(existing)
        }
        val seq = row.seq
        if (seq == null) {
            // 无 seq：尊重宿主给的显示序（群聊位置序 / 私聊 divider），追加到尾部。
            list.add(row)
            return
        }
        val at = list.indexOfFirst { it.seq != null && it.seq > seq }
        if (at >= 0) list.add(at, row) else list.add(row)
    }

    /**
     * 权威重读：用**最新快照**重建当前 part，清掉快照不再包含的行。
     * 保留更早的分页（earlierPages）与其它 part（不清历史）。
     *
     * 用于宿主 reset 帧 / 群聊轮末收口——它们返回的是「最新窗口」：
     * 盲目合并会让已落定/已移除的行永久残留（越用越乱、冷启动才好）；
     * 全量清空又会丢掉更早的历史。这是两者之间的收敛语义。
     */
    fun resyncCurrent(rows: List<TimelineRow>) {
        withdrawn.clear()
        val list = parts.getOrPut(currentPart) { ArrayList() }
        list.clear()
        for (row in rows) insertRow(list, row)
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
