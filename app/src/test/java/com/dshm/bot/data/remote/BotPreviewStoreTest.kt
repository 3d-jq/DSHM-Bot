package com.dshm.bot.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览缓存的容量与清理规则（纯逻辑）：LRU 条数上限、删除 Bot 清条目、
 * 整表字符软上限。这些规则堵住「一个 prefs key 存整张表、无上限、删 Bot 永不清理」。
 */
class BotPreviewStoreTest {

    /** 删除 Bot 后，对应条目要从表里清掉。 */
    @Test
    fun pruneDropsEntriesOfDeletedBots() {
        val kept = BotPreviewStore.prune(
            previews = linkedMapOf("b1" to "还在", "b2" to "被删了", "b3" to "也还在"),
            liveIds = setOf("b1", "b3"),
        )

        assertEquals(mapOf("b1" to "还在", "b3" to "也还在"), kept)
    }

    /** 条数超上限时丢最旧（顺序最靠前）的条目，留下最近更新的。 */
    @Test
    fun pruneEvictsOldestEntriesBeyondCap() {
        val previews = LinkedHashMap<String, String>()
        repeat(10) { index -> previews["b$index"] = "预览 $index" }

        val kept = BotPreviewStore.prune(previews, liveIds = null, maxEntries = 4)

        assertEquals(4, kept.size)
        assertEquals(listOf("b6", "b7", "b8", "b9"), kept.keys.toList())
    }

    /** 整表字符超软上限时继续丢最旧的，直到放得下为止。 */
    @Test
    fun pruneEvictsOldestUntilUnderCharBudget() {
        val previews = LinkedHashMap<String, String>()
        repeat(20) { index -> previews["b$index"] = "很长的预览".repeat(40) }

        val kept = BotPreviewStore.prune(previews, liveIds = null, maxEntries = 100, maxChars = 2048)

        assertTrue("应该丢到预算以内：${kept.size} 条", kept.size < 20)
        // 最旧的先走，剩下的都是最新的。
        assertEquals(listOf("b19"), kept.keys.toList().takeLast(1))
    }

    /** 空白条目（空 id / 空文案）不进表。 */
    @Test
    fun pruneSkipsBlankEntries() {
        val kept = BotPreviewStore.prune(
            previews = linkedMapOf("b1" to "ok", "  " to "空 id", "b2" to "   "),
            liveIds = null,
        )

        assertEquals(mapOf("b1" to "ok"), kept)
    }

    /** LRU 表：超过容量时淘汰最久没被访问（get / put 都算访问）的条目。 */
    @Test
    fun lruTableEvictsLeastRecentlyUsedEntry() {
        val table = LruPreviewTable(maxEntries = 3)
        table.put("b1", "一")
        table.put("b2", "二")
        table.put("b3", "三")
        // 访问 b1 —— 它变成最近使用的。
        assertEquals("一", table.get("b1"))
        table.put("b4", "四")

        assertEquals(3, table.snapshot().size)
        assertNull("最久没访问的 b2 应被淘汰", table.get("b2"))
        assertEquals("一", table.get("b1"))
        assertEquals("三", table.get("b3"))
        assertEquals("四", table.get("b4"))
    }

    /** LRU 表快照按「最旧在前」给出，正好喂给 prune 的淘汰顺序。 */
    @Test
    fun lruTableSnapshotIsOldestFirst() {
        val table = LruPreviewTable(maxEntries = 5)
        table.put("b1", "一")
        table.put("b2", "二")
        table.put("b3", "三")
        table.get("b1") // b1 变最新

        val snapshot = table.snapshot().keys.toList()

        assertEquals(listOf("b2", "b3", "b1"), snapshot)
    }

    /** LRU 表初始化和 put 都按展示规则压成一行、跳过空白。 */
    @Test
    fun lruTableNormalizesEntries() {
        val table = LruPreviewTable(initial = mapOf("b1" to "很长的预览".repeat(40), "  " to "空"))
        table.put("b2", "  ")

        val snapshot = table.snapshot()

        assertEquals(1, snapshot.size)
        assertEquals(BotPreview.oneLine("很长的预览".repeat(40)), snapshot.getValue("b1"))
    }
}
