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
