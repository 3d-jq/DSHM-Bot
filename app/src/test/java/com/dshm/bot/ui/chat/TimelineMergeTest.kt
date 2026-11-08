package com.dshm.bot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineMergeTest {

    private fun entry(id: String, seq: Long?): ChatEntry =
        ChatEntry(id = id, kind = ChatEntryKind.BOT, text = id, seq = seq)

    @Test
    fun `keeps local bubbles without a seq in place and appends newer events after them`() {
        // 本地刚发出的用户气泡还没有 seq：事件比历史都新时应该排在它后面，
        // 而不是把它挤到列表最后（那就是「回复跑到问题上面」）。
        val base = listOf(entry("bot-1", 100L), entry("local-user", null))
        val merged = mergeTimeline(base, listOf(entry("tool:101", 101L)))

        assertEquals(listOf("bot-1", "local-user", "tool:101"), merged.map { it.id })
    }

    @Test
    fun `inserts an older event into the history by seq`() {
        val base = listOf(entry("bot-1", 100L), entry("bot-2", 200L))
        val merged = mergeTimeline(base, listOf(entry("tool:150", 150L)))

        assertEquals(listOf("bot-1", "tool:150", "bot-2"), merged.map { it.id })
    }

    @Test
    fun `does not move a null-seq entry that sits between sequenced ones`() {
        val base = listOf(entry("bot-1", 100L), entry("local-user", null), entry("bot-2", 200L))
        val merged = mergeTimeline(base, listOf(entry("tool:150", 150L)))

        // 150 落在 100 和 200 之间 → 插在 bot-2 前面，但不动 local-user 的位置。
        assertEquals(listOf("bot-1", "local-user", "tool:150", "bot-2"), merged.map { it.id })
    }

    @Test
    fun `merges several events in seq order and is idempotent`() {
        val base = listOf(entry("bot-1", 100L), entry("bot-2", 300L))
        val once = mergeTimeline(base, listOf(entry("tool:250", 250L), entry("tool:150", 150L)))
        assertEquals(listOf("bot-1", "tool:150", "tool:250", "bot-2"), once.map { it.id })

        val twice = mergeTimeline(once.filterNot { it.id.startsWith("tool:") }, listOf(entry("tool:150", 150L)))
        assertEquals(listOf("bot-1", "tool:150", "bot-2"), twice.map { it.id })
    }
}
