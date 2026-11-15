package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.TimelineRow
import com.dshm.bot.data.remote.TimelineRowPayload
import org.junit.Assert.assertEquals
import org.junit.Test

/** TimelineEventLog：沿袭 dsh-Go `_log` 的一致性语义（幂等/乱序归位/合并/前插/part 隔离）。 */
class TimelineEventLogTest {

    private fun row(key: String, seq: Long?, text: String = key) = TimelineRow(
        key = key,
        kind = "bot",
        seq = seq,
        turn = null,
        timeMs = null,
        payload = TimelineRowPayload.Bot(
            text = text,
            author = null,
            botId = null,
            durationMs = null,
            whole = null,
            live = false,
        ),
    )

    private fun keys(log: TimelineEventLog) = log.rows().map { it.key }

    @Test
    fun outOfOrderWritesAreSortedBySeq() {
        val log = TimelineEventLog()
        log.merge(listOf(row("c", 30), row("a", 10), row("b", 20)))
        assertEquals(listOf("a", "b", "c"), keys(log))
    }

    @Test
    fun lateFrameLandsInItsSeqPositionNotAtTheEnd() {
        val log = TimelineEventLog()
        log.merge(listOf(row("a", 10), row("c", 30)))
        // 迟到的中间帧：写进日志后自动归位，而不是插在末尾。
        log.put(row("b", 20))
        assertEquals(listOf("a", "b", "c"), keys(log))
    }

    @Test
    fun sameSeqIsIdempotent() {
        val log = TimelineEventLog()
        log.put(row("first", 10, "旧"))
        log.put(row("first", 10, "新"))
        assertEquals(1, log.size())
        assertEquals("新", (log.rows().single().payload as TimelineRowPayload.Bot).text)
    }

    @Test
    fun mergeDoesNotClearAlreadyReceivedRows() {
        val log = TimelineEventLog()
        log.put(row("live", 12)) // 增量已经收到的、比重放更新的行
        // 断档重放只带回 seq<=11 的行；本地更新过的行必须保留（不清空）。
        log.merge(listOf(row("a", 10), row("b", 11)))
        assertEquals(listOf("a", "b", "live"), keys(log))
    }

    @Test
    fun mergeOverwritesSameSeqInsteadOfDuplicating() {
        val log = TimelineEventLog()
        log.put(row("same", 11, "旧"))
        log.merge(listOf(row("same", 11, "新")))
        assertEquals(1, log.size())
        assertEquals("新", (log.rows().single().payload as TimelineRowPayload.Bot).text)
    }

    @Test
    fun replayAfterLostCardBringsItBack() {
        val log = TimelineEventLog()
        log.put(row("user", 10))
        log.put(row("bot", 12)) // 卡帧 seq=11 丢了
        assertEquals(listOf("user", "bot"), keys(log))

        log.merge(listOf(row("present", 11))) // since 重放把卡带回
        assertEquals(listOf("user", "present", "bot"), keys(log))
    }

    @Test
    fun resetClearsAndRebuilds() {
        val log = TimelineEventLog()
        log.merge(listOf(row("old", 10)))
        log.reset(listOf(row("new", 20)))
        assertEquals(listOf("new"), keys(log))
    }

    @Test
    fun rowsWithoutSeqStayAtTheTail() {
        val log = TimelineEventLog()
        log.merge(listOf(row("inbox", null), row("a", 10), row("b", 20)))
        assertEquals(listOf("a", "b", "inbox"), keys(log))
    }

    @Test
    fun earlierPagesPrependInOrderWithoutCrossPartSorting() {
        val log = TimelineEventLog()
        log.reset(listOf(row("cur", 100)), part = "p2")
        log.prependPage(listOf(row("old2", 5), row("old3", 6)))
        log.prependPage(listOf(row("old0", 1), row("old1", 2)))
        assertEquals(listOf("old0", "old1", "old2", "old3", "cur"), keys(log))
    }

    @Test
    fun partsAreIsolatedAndKeptInInsertionOrder() {
        val log = TimelineEventLog()
        log.reset(listOf(row("p1-a", 1)), part = "p1")
        log.merge(listOf(row("p2-a", 1)), part = "p2")
        // 两个 part 的 seq 空间独立，同 seq 不互相覆盖。
        assertEquals(listOf("p1-a", "p2-a"), keys(log))
    }

    @Test
    fun maxSeqTracksTheCurrentPart() {
        val log = TimelineEventLog()
        log.reset(listOf(row("a", 10), row("b", 20)), part = "p1")
        assertEquals(20L, log.maxSeq())
        val early = TimelineEventLog()
        early.reset(listOf(row("a", 10)), part = "cur")
        early.prependPage(listOf(row("older", 5)))
        assertEquals(10L, early.maxSeq())
    }
}
