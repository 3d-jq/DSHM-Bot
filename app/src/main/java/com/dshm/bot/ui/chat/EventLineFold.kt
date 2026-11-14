package com.dshm.bot.ui.chat

/**
 * 时间线里一条「展示项」：[entry] 是代表条目，[repeatCount] 是它连续重复的次数。
 * `repeatCount == 1` 表示原样展示；> 1 时事件行文案尾部追加「 ×N」。
 *
 * key 稳定性：折叠组用**代表条目**（组内第一条）的 id 当 LazyColumn key——
 * 同类事件往后追加时代表不变，行原位更新计数，不整行重建。
 */
data class EventLineDisplay(
    val entry: ChatEntry,
    val repeatCount: Int,
)

/**
 * 把**连续且完全相同**的事件行折叠成一条「 ×N」。
 *
 * 背景：`retry`（模型把工具调用写成了文本，宿主要求重试）在重灾区回合里一次来
 * 十几条，每条一行把对话堆得很高——官方网页端（cells.js:619）也是一条一行，
 * 这里是我们有意的偏离：相同文案连续出现时合并成一条计数行。
 *
 * 不折的（各有各的去处 / 语义，折叠会藏掉差异）：
 * - relay / 工具类行（带目标头像、点得开对方对话、带记忆图标）；
 * - 带产物卡 / 缩略图 / 视图 / 问答等富内容的条目（这些 kind 本来就不是 EVENT）；
 * - 文案不同的行（同 kind 但内容不一样，是两条信息）。
 *
 * 无 seq 的条目照常参与折叠：折叠只看「相邻 + 相同」，与排序无关。
 */
internal fun foldRepeatedEventLines(entries: List<ChatEntry>): List<EventLineDisplay> {
    val out = ArrayList<EventLineDisplay>(entries.size)
    var index = 0
    while (index < entries.size) {
        val entry = entries[index]
        val foldable = entry.kind == ChatEntryKind.EVENT &&
            entry.eventKind !in RELAY_EVENT_KINDS &&
            entry.tool == null &&
            entry.target == null &&
            entry.artifacts.isEmpty() &&
            entry.shot == null &&
            entry.view == null &&
            entry.answered.isEmpty() &&
            entry.answeredView == null
        if (!foldable) {
            out += EventLineDisplay(entry, 1)
            index += 1
            continue
        }
        var count = 1
        while (index + count < entries.size) {
            val next = entries[index + count]
            val same = next.kind == ChatEntryKind.EVENT &&
                next.eventKind == entry.eventKind &&
                next.text == entry.text &&
                next.author == entry.author &&
                next.botId == entry.botId &&
                next.eventCode == entry.eventCode &&
                next.tool == null &&
                next.target == null &&
                next.artifacts.isEmpty() &&
                next.shot == null &&
                next.view == null &&
                next.answered.isEmpty() &&
                next.answeredView == null
            if (!same) break
            count += 1
        }
        out += EventLineDisplay(entry, count)
        index += count
    }
    return out
}
