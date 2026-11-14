package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.TimelineRow
import com.dshm.bot.data.remote.TimelineSnapshot

/**
 * Phase 1a 的 shadow 对比器（debug 双跑用，`docs/phase1-design.md` §6）：
 * 旧管线（load + feed message + session/follow）产出的 [ChatEntry] 序列 vs
 * 新管线（timeline 快照 + feed `timeline` 增量）产出的 [TimelineRow] 序列，
 * 逐行对比 **key / seq / kind**，首个分歧点两侧摘要经日志出口输出。
 *
 * key 对齐规则（设计 §2 的三条 key 规则）：
 * - 旧 `user:<rpcId>` 气泡 → 新 `user:<seq>`（认领后数据键切 seq，比较按 seq 侧）；
 * - `tool:<resultSeq>` / `retry:<seq>` / `turnfail:<seq>` / `present:<turn>` 两边同名，直接比；
 * - divider 的旧 id 是 item id、partIndex 对不上 → 只比 kind + seq。
 *
 * 只比 settled 行：两边都丢掉无 seq 的行（echo/inbox 是客户端/排队态，设计 §2「无 seq
 * 不参排序」）；浏览器缩略图与诊断行是客户端本地功能（新管线明确不投影），同样跳过。
 *
 * 纯逻辑类：日志出口与时钟可注入，JVM 可测。**只读不写**——不碰任何主管线状态。
 */
internal class TimelineShadowComparator(
    private val log: (String) -> Unit = com.dshm.bot.data.remote.DshMuxLog::log,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** 同一会话两次差异日志的最小间隔（设计 §6：每会话限频 1 条/5s）。 */
    private val minIntervalMs: Long = 5_000L,
) {

    /** 一次对比的结论。 */
    data class Report(
        val identical: Boolean,
        /** 有差异且本轮允许输出时的日志文案；无差异或被限频时为 null。 */
        val message: String?,
    )

    /** 每会话上次输出差异的时刻与被限频压掉的次数。 */
    private val lastLoggedAt = HashMap<String, Long>()
    private val suppressed = HashMap<String, Int>()

    /**
     * 对比一次两侧行序列。
     *
     * [oldEntries] 用对话页当前的 entries 快照；[newSnapshot]/[newRows] 用行管线的
     * 当前序列（快照或「快照+增量」合并结果，二选一传入）。
     */
    fun compare(
        sessionKey: String,
        oldEntries: List<ChatEntry>,
        newRows: List<TimelineRow>,
        newSnapshot: TimelineSnapshot? = null,
    ): Report {
        val oldSteps = oldEntries.mapNotNull(::stepOf)
        val newSteps = newRows.filter { it.seq != null }.map { row ->
            Step(kind = row.kind, seq = row.seq, key = row.key)
        }

        val firstDiff = firstDivergence(oldSteps, newSteps)
        if (firstDiff == null && oldSteps.size == newSteps.size) {
            suppressed.remove(sessionKey)
            return Report(identical = true, message = null)
        }

        val now = nowMs()
        val last = lastLoggedAt[sessionKey]
        if (last != null && now - last < minIntervalMs) {
            suppressed.merge(sessionKey, 1, Int::plus)
            return Report(identical = false, message = null)
        }
        lastLoggedAt[sessionKey] = now
        val dropped = suppressed.remove(sessionKey) ?: 0

        val snapshotNote = newSnapshot?.let {
            " snapshot(seq=${it.seq} rows=${it.rows.size} cursor=${it.cursor != null})"
        } ?: ""
        val message = buildString {
            append("timeline shadow 分歧 @").append(sessionKey)
            append(snapshotNote)
            append("：旧 ").append(oldSteps.size).append(" 行 / 新 ").append(newSteps.size).append(" 行")
            if (dropped > 0) append("（本会话已抑制 ").append(dropped).append(" 条）")
            if (firstDiff != null) {
                append("；首分歧 @#").append(firstDiff.index).append("：")
                append("旧[").append(firstDiff.old?.summary() ?: "").append("] ")
                append("新[").append(firstDiff.new?.summary() ?: "").append("]")
                append("；旧侧窗口：")
                append(window(oldSteps, firstDiff.index))
                append("；新侧窗口：")
                append(window(newSteps, firstDiff.index))
            }
        }
        log(message)
        return Report(identical = false, message = message)
    }

    /** 首个 (kind, seq, 可比 key) 不一致的位置；长度差也按「一侧缺行」计。 */
    private fun firstDivergence(old: List<Step>, new: List<Step>): Divergence? {
        val common = minOf(old.size, new.size)
        for (index in 0 until common) {
            val a = old[index]
            val b = new[index]
            val same = a.kind == b.kind && a.seq == b.seq && (a.key == null || a.key == b.key)
            if (!same) return Divergence(index, a, b)
        }
        // 前缀全同但长度不同：多出的一侧在分界点上缺行。
        return when {
            old.size > new.size -> Divergence(new.size, old[new.size], null)
            new.size > old.size -> Divergence(old.size, null, new[old.size])
            else -> null
        }
    }

    private fun window(steps: List<Step>, at: Int): String =
        steps.subList(maxOf(0, at - 2), minOf(steps.size, at + 3))
            .joinToString(prefix = "[", postfix = "]") { it.summary() }

    private data class Step(val kind: String, val seq: Long?, val key: String?) {
        fun summary(): String = "$kind@${seq ?: "-"}" + (key?.let { "($it)" } ?: "")
    }

    private data class Divergence(val index: Int, val old: Step?, val new: Step?)

    /** 旧管线条目 → 可比步骤；本地态（echo/诊断/缩略图）返回 null 跳过。 */
    private fun stepOf(entry: ChatEntry): Step? {
        if (entry.seq == null) return null
        if (entry.kind == ChatEntryKind.BROWSER_SHOT) return null
        val id = entry.id
        // 诊断行（diag:<time>）是客户端补画的失败行，新管线由 turnfail 行承担。
        if (id.startsWith("diag:")) return null
        val kind: String
        val key: String?
        when (entry.kind) {
            ChatEntryKind.USER -> {
                kind = TimelineRow.KIND_USER
                key = "user:${entry.seq}"
            }

            ChatEntryKind.BOT -> {
                kind = TimelineRow.KIND_BOT
                key = "bot:${entry.seq}"
            }

            ChatEntryKind.EVENT -> when {
                id.startsWith("tool:") -> {
                    kind = TimelineRow.KIND_TOOL
                    key = id
                }

                id.startsWith("retry:") || id.startsWith("turnfail:") -> {
                    // retry / turnfail 保持现名（设计 §2 event 行的 key 规则，测试已锁）。
                    kind = TimelineRow.KIND_EVENT
                    key = id
                }

                else -> {
                    kind = TimelineRow.KIND_EVENT
                    key = "event:${entry.seq}"
                }
            }

            ChatEntryKind.ARTIFACTS -> {
                kind = TimelineRow.KIND_ARTIFACT
                key = id.takeIf { it.startsWith("present:") }
            }

            ChatEntryKind.VIEW -> {
                kind = TimelineRow.KIND_VIEW
                key = "view:${entry.seq}"
            }

            ChatEntryKind.ANSWERED -> {
                kind = TimelineRow.KIND_ANSWERED
                key = "answer:${entry.seq}"
            }

            // 旧 divider 的 id 是历史 item id，partIndex 推不出来 → 只比 kind + seq。
            ChatEntryKind.DIVIDER -> {
                kind = TimelineRow.KIND_DIVIDER
                key = null
            }

            else -> return null
        }
        return Step(kind = kind, seq = entry.seq, key = key)
    }
}
