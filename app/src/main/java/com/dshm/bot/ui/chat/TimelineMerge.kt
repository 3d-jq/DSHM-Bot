package com.dshm.bot.ui.chat

/**
 * 把会话事件行（工具调用 / 产物卡）插进已经加载出来的消息里。
 *
 * 按 `seq` 找位置，**不动**已经有内容的那一段顺序：本地刚发出的用户气泡还没有
 * 真 seq（服务端回显被去重，要等下一次重载才有），整表排序会把它甩到末尾 ——
 * 倒序列表里就是最下面，表现成「大模型的回复跑到了问题上面」。
 *
 * 找不到更大的 seq（事件比现有历史都新）时放在末尾。
 */
fun mergeTimeline(base: List<ChatEntry>, incoming: List<ChatEntry>): List<ChatEntry> {
    if (incoming.isEmpty()) return base
    val merged = base.toMutableList()
    incoming.sortedBy { it.seq ?: 0L }.forEach { entry ->
        val seq = entry.seq ?: 0L
        val at = merged.indexOfFirst { existing ->
            val existingSeq = existing.seq ?: return@indexOfFirst false
            existingSeq > seq
        }
        if (at >= 0) merged.add(at, entry) else merged.add(entry)
    }
    return merged.toList()
}

/**
 * 单条按 [mergeTimeline] 的同一套规则插入（feed `message` 的落地用）：
 * 有 seq 的插到「第一条 seq 更大的条目」之前，找不到就追加末尾；无 seq 的条目对
 * 定位透明，新条目追加末尾。
 *
 * 对齐官方 runtime（insertAssistantSettlement：持久事件插到已可见尾部之前的真 seq
 * 位置）：排队/steer 的消息还没回显、占位气泡挂在末尾时，先到的收尾文字与回复
 * 不能被无条件甩到它后面 —— 追加会把「比占位气泡更早落日志」的消息排错。
 */
fun mergeTimelineEntry(base: List<ChatEntry>, entry: ChatEntry): List<ChatEntry> {
    val seq = entry.seq ?: return base + entry
    val at = base.indexOfFirst { existing ->
        val existingSeq = existing.seq ?: return@indexOfFirst false
        existingSeq > seq
    }
    return if (at >= 0) base.toMutableList().apply { add(at, entry) } else base + entry
}
