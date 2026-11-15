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
    fun rowsWithoutSeqKeepHostDisplayOrder() {
        val log = TimelineEventLog()
        // 私聊：divider（无 seq）插在两个有 seq 行之间，不能被挪到尾部。
        log.merge(listOf(row("a", 10), row("divider", null), row("b", 20)))
        assertEquals(listOf("a", "divider", "b"), keys(log))
    }

    /** 回归（2026-11-15 真机）：群聊是位置序，relay 回复行 seq=null，不能被挪到后面。 */
    @Test
    fun groupPositionOrderIsPreserved() {
        val log = TimelineEventLog()
        log.merge(
            listOf(
                row("user:1", 1),
                row("bot:relay-1:0", null),
                row("user:relay-1:1", null),
                row("bot:relay-1:2", null),
                row("user:5", 5),
            ),
        )
        assertEquals(listOf("user:1", "bot:relay-1:0", "user:relay-1:1", "bot:relay-1:2", "user:5"), keys(log))
    }

    /** 回归：有 seq 的新行仍要插到正确位置，且不打断已排好的无 seq 行。 */
    @Test
    fun lateSeqRowLandsBetweenSeqRowsWithoutDisturbingNoSeqRows() {
        val log = TimelineEventLog()
        log.merge(listOf(row("a", 10), row("divider", null), row("c", 30)))
        log.put(row("b", 20)) // 迟到的中间 seq 行
        assertEquals(listOf("a", "divider", "b", "c"), keys(log))
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

    /** 回归（2026-11-15 真机「用着用着气泡乱、冷启动又正常」）：整页重放不能挪已有行。 */
    @Test
    fun replayingAPageKeepsExistingRowPositions() {
        val log = TimelineEventLog()
        log.merge(listOf(row("user:1", 1), row("bot:relay-1:0", null, "旧"), row("user:2", 2), row("bot:relay-1:1", null, "旧")))
        assertEquals(listOf("user:1", "bot:relay-1:0", "user:2", "bot:relay-1:1"), keys(log))

        // 运行期会把整页再给一遍（room 刷新 / 轮末收口）。旧实现「先删再插」会把
        // user:1 插到第一个更大 seq 之前（一步跨过中间的 relay 行），并把无 seq 的
        // 回复行挪到队尾 —— 每重放一次就错位一次。
        log.merge(listOf(row("user:1", 1), row("bot:relay-1:0", null, "新"), row("user:2", 2), row("bot:relay-1:1", null, "新")))
        assertEquals(listOf("user:1", "bot:relay-1:0", "user:2", "bot:relay-1:1"), keys(log))
        assertEquals("新", (log.rows()[1].payload as TimelineRowPayload.Bot).text)
        assertEquals("新", (log.rows()[3].payload as TimelineRowPayload.Bot).text)
    }

    @Test
    fun repeatedReplaysAreStable() {
        val log = TimelineEventLog()
        val page = listOf(row("user:1", 1), row("bot:relay-1:0", null), row("user:2", 2), row("bot:relay-1:1", null))
        repeat(5) { log.merge(page) }
        assertEquals(page.map { it.key }, keys(log))
        assertEquals(page.size, log.size())
    }

    /** live 回复（无 seq）settled 后拿到真 seq：身份不变，位置按 seq 归位。 */
    @Test
    fun aRowsSeqAppearingOnReplayMovesItToItsSeqPosition() {
        val log = TimelineEventLog()
        log.put(row("user:1", 1))
        log.put(row("bot:relay-1:0", null))
        log.put(row("bot:relay-1:0", 3))
        assertEquals(listOf("user:1", "bot:relay-1:0"), keys(log))
        assertEquals(3L, log.rows()[1].seq)
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
