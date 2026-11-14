package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.TimelineFile
import com.dshm.bot.data.remote.TimelineRow
import com.dshm.bot.data.remote.TimelineRowPayload
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * shadow 对比器（Phase 1a）：旧管线 entries vs 新管线行序列的 key/seq/kind 对比、
 * key 对齐映射（设计 §2 三条规则）、本地态行跳过、限频日志。
 */
class TimelineShadowComparatorTest {

    private val logs = mutableListOf<String>()
    private var clockMs = 1_000_000L

    private fun comparator(minIntervalMs: Long = 5_000L) = TimelineShadowComparator(
        log = { logs += it },
        nowMs = { clockMs },
        minIntervalMs = minIntervalMs,
    )

    // ---- 旧行构造（对齐 BotChatViewModel 的 id 约定）----

    private fun userEntry(seq: Long, rpcId: String = "req-$seq") =
        ChatEntry(id = "user:$rpcId", kind = ChatEntryKind.USER, text = "q$seq", seq = seq)

    private fun botEntry(seq: Long, text: String = "a$seq") =
        ChatEntry(id = "hist:$seq", kind = ChatEntryKind.BOT, text = text, seq = seq)

    private fun toolEntry(seq: Long) =
        ChatEntry(id = "tool:$seq", kind = ChatEntryKind.EVENT, text = "交付", seq = seq)

    private fun retryEntry(seq: Long) =
        ChatEntry(id = "retry:$seq", kind = ChatEntryKind.EVENT, text = "重试", seq = seq)

    private fun turnfailEntry(seq: Long) =
        ChatEntry(id = "turnfail:$seq", kind = ChatEntryKind.EVENT, text = "失败", seq = seq)

    private fun artifactEntry(turn: Int, seq: Long) =
        ChatEntry(id = "present:$turn", kind = ChatEntryKind.ARTIFACTS, text = "", seq = seq)

    private fun dividerEntry(seq: Long) =
        ChatEntry(id = "hist:$seq", kind = ChatEntryKind.DIVIDER, text = "", seq = seq)

    private fun echoEntry(requestId: String) =
        ChatEntry(id = "user:$requestId", kind = ChatEntryKind.USER, text = "本地", seq = null)

    // ---- 新行构造（对齐宿主投影 key 惯例）----

    private fun row(key: String, kind: String, seq: Long?): TimelineRow = TimelineRow(
        key = key,
        kind = kind,
        seq = seq,
        turn = null,
        timeMs = null,
        payload = when (kind) {
            TimelineRow.KIND_ARTIFACT -> TimelineRowPayload.Artifact(emptyList<TimelineFile>(), delivered = true, turn = 1)
            TimelineRow.KIND_INBOX -> TimelineRowPayload.Inbox("排队", "next-turn", null, "i-1")
            else -> TimelineRowPayload.Raw(JSONObject())
        },
    )

    @Test
    fun identicalSequencesReportCleanWithNoLog() {
        val old = listOf(userEntry(1), botEntry(2), toolEntry(3))
        val new = listOf(
            row("user:1", "user", 1L),
            row("bot:2", "bot", 2L),
            row("tool:3", "tool", 3L),
        )

        val report = comparator().compare("bot-1", old, new)

        assertTrue(report.identical)
        assertNull(report.message)
        assertTrue("一致时不应写日志：$logs", logs.isEmpty())
    }

    @Test
    fun userEchoKeyAlignsToSeqBasedKey() {
        // 旧 user 气泡 id=user:<rpcId>，新行 key=user:<seq>（key 规则 1：认领后按 seq 侧对齐）。
        val old = listOf(userEntry(5, rpcId = "req-abc"))
        val new = listOf(row("user:5", "user", 5L))

        assertTrue(comparator().compare("bot-1", old, new).identical)
    }

    @Test
    fun artifactAndRetryAndTurnfailKeepTheirNames() {
        val old = listOf(artifactEntry(2, seq = 20), retryEntry(21), turnfailEntry(22))
        val new = listOf(
            row("present:2", "artifact", 20L),
            row("retry:21", "event", 21L),
            row("turnfail:22", "event", 22L),
        )

        assertTrue(comparator().compare("bot-1", old, new).identical)
    }

    @Test
    fun dividerComparesByKindAndSeqOnly() {
        // divider 的旧 id 推不出 partIndex → key 不参与比较。
        val old = listOf(dividerEntry(4))
        val new = listOf(row("divider:0", "divider", 4L))

        assertTrue(comparator().compare("bot-1", old, new).identical)
    }

    @Test
    fun clientLocalRowsAreSkippedOnBothSides() {
        // 旧侧：echo（无 seq）、缩略图、诊断行不参比；新侧：inbox（无 seq）不参比。
        val old = listOf(
            echoEntry("req-x"),
            ChatEntry(id = "shot:1:2", kind = ChatEntryKind.BROWSER_SHOT, text = "", seq = 9),
            ChatEntry(id = "diag:100", kind = ChatEntryKind.EVENT, text = "失败", seq = 9),
            botEntry(10),
        )
        val new = listOf(
            row("inbox:i-1", "inbox", null),
            row("bot:10", "bot", 10L),
        )

        assertTrue(comparator().compare("bot-1", old, new).identical)
    }

    @Test
    fun kindDivergenceReportsFirstMismatchWithBothSummaries() {
        val old = listOf(userEntry(1), botEntry(2))
        val new = listOf(row("user:1", "user", 1L), row("tool:2", "tool", 2L))

        val report = comparator().compare("bot-1", old, new)

        assertFalse(report.identical)
        val message = report.message.orEmpty()
        assertTrue("日志应包含会话键：$message", message.contains("@bot-1"))
        assertTrue("日志应指出首分歧位置：$message", message.contains("#1"))
        assertTrue("日志应带旧侧行摘要：$message", message.contains("bot@2"))
        assertTrue("日志应带新侧行摘要：$message", message.contains("tool@2(tool:2)"))
        assertTrue("日志应进 DshMuxLog 出口：$logs", logs.single().contains("timeline shadow 分歧"))
    }

    @Test
    fun seqAndKeyDivergencesAreReported() {
        // seq 不一致。
        val seqDiff = comparator().compare(
            "bot-1",
            listOf(botEntry(2)),
            listOf(row("bot:3", "bot", 3L)),
        )
        assertFalse(seqDiff.identical)
        assertTrue(seqDiff.message!!.contains("bot@2"))

        // key 不一致（同 kind 同 seq）。
        val keyDiff = comparator().compare(
            "bot-1",
            listOf(toolEntry(7)),
            listOf(row("tool:9", "tool", 7L)),
        )
        assertFalse(keyDiff.identical)
        assertTrue(keyDiff.message!!.contains("tool:7"))
        assertTrue(keyDiff.message!!.contains("tool:9"))
    }

    @Test
    fun lengthDivergenceReportsTheMissingRow() {
        val report = comparator().compare(
            "bot-1",
            listOf(userEntry(1), botEntry(2)),
            listOf(row("user:1", "user", 1L)),
        )

        assertFalse(report.identical)
        assertTrue(report.message!!.contains("#1"))
        assertTrue("缺行一侧的摘要应为空：${report.message}", report.message!!.contains("新[]") || report.message!!.contains("旧[]"))
    }

    @Test
    fun diffsAreRateLimitedToOneLogPerFiveSecondsPerSession() {
        val comparator = comparator(minIntervalMs = 5_000L)
        val old = listOf(botEntry(2))
        val new = listOf<TimelineRow>() // 恒有差异

        val first = comparator.compare("bot-1", old, new)
        assertFalse(first.identical)
        assertTrue(first.message != null)
        assertEquals(1, logs.size)

        // 5s 内再分歧：压掉（不写日志），但结论仍是非一致。
        clockMs += 1_000
        val second = comparator.compare("bot-1", old, new)
        assertFalse(second.identical)
        assertNull("限频窗口内不该再写日志", second.message)
        assertEquals(1, logs.size)

        // 窗口过了：放行，且带「已抑制 N 条」。
        clockMs += 4_001
        val third = comparator.compare("bot-1", old, new)
        assertFalse(third.identical)
        assertTrue(third.message!!.contains("已抑制 1 条"))
        assertEquals(2, logs.size)
    }

    @Test
    fun rateLimitIsPerSession() {
        val comparator = comparator()
        val old = listOf(botEntry(2))
        val new = listOf<TimelineRow>()

        comparator.compare("bot-1", old, new)
        comparator.compare("bot-2", old, new)

        assertEquals("不同会话互不限频", 2, logs.size)
    }

    @Test
    fun cleanComparisonClearsTheSuppressionCount() {
        val comparator = comparator()
        val diffOld = listOf(botEntry(2))
        val diffNew = listOf<TimelineRow>()
        val cleanOld = listOf(botEntry(2))
        val cleanNew = listOf(row("bot:2", "bot", 2L))

        comparator.compare("bot-1", diffOld, diffNew)
        comparator.compare("bot-1", diffOld, diffNew) // 抑制 +1
        comparator.compare("bot-1", cleanOld, cleanNew) // 一致，清零
        clockMs += 6_000
        val after = comparator.compare("bot-1", diffOld, diffNew)

        assertFalse(after.identical)
        assertTrue("清零后不应再提抑制：${after.message}", !after.message!!.contains("已抑制"))
    }

    @Test
    fun snapshotNoteIsIncludedWhenProvided() {
        val snapshot = com.dshm.bot.data.remote.TimelineSnapshot(
            sessionId = "s-1",
            roomId = null,
            seq = 42,
            rows = listOf(row("bot:42", "bot", 42L)),
            cursor = JSONObject(),
            running = null,
            speaking = null,
        )
        val report = comparator().compare(
            "bot-1",
            listOf(botEntry(2)),
            listOf(row("bot:42", "bot", 42L)),
            newSnapshot = snapshot,
        )

        assertFalse(report.identical)
        assertTrue(report.message!!.contains("snapshot(seq=42"))
        assertTrue(report.message!!.contains("cursor=true"))
    }
}
