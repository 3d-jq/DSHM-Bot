package com.dshm.bot.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 引擎日志的落盘安全：token / API Key 必须在写盘前打码（展示层打码是冗余防线），
 * 且长会话下日志体积有上限（超限只保留尾部）。只走 [EngineLog.raw]（不触碰
 * SystemClock），文件由 rebindForTest 指向临时目录。
 */
class EngineLogTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "dshm-test-$name-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }

    private fun freshLog(dir: File): File {
        val target = File(dir, "engine.log")
        EngineLog.rebindForTest(target)
        return target
    }

    @Test
    fun masksEngineTokenBeforeItHitsDisk() {
        val target = freshLog(tempDir("log-token"))

        EngineLog.raw("dsh web: http://127.0.0.1:3080/?token=AbC123_xyz")
        val text = target.readText()

        assertTrue(text.contains("token=***"))
        assertFalse(text.contains("AbC123_xyz"))
    }

    @Test
    fun masksApiKeysBeforeItHitsDisk() {
        val target = freshLog(tempDir("log-key"))

        EngineLog.raw("config: api_key=sk-abcdefghij0123456789")
        val text = target.readText()

        assertTrue(text.contains("api_key=***"))
        assertFalse(text.contains("sk-abcdefghij0123456789"))
    }

    @Test
    fun keepsOrdinaryEngineLinesIntact() {
        val target = freshLog(tempDir("log-plain"))

        EngineLog.raw("INFO DSH is listening at http://127.0.0.1:3080/")

        assertTrue(target.readText().contains("INFO DSH is listening"))
    }

    @Test
    fun rotatesIntoHistorySegmentsInsteadOfLosingOldLines() {
        val dir = tempDir("log-rotate")
        val target = freshLog(dir)
        EngineLog.raw("oldest-line-from-previous-run")
        // 单段 512 KiB：每行 ~1KB，写 1 MiB 必触发轮转。
        repeat(1024) { index ->
            EngineLog.raw("chunk-$index-" + "x".repeat(960))
        }
        EngineLog.raw("the-latest-line")

        assertTrue("当前段应 ≤ 512 KiB + 一行余量，实际 ${target.length()}", target.length() <= 512L * 1024L + 4096)
        assertTrue(target.readText().contains("the-latest-line"))
        // 旧行进了历史段，而不是被截断丢掉。
        val history = File(dir, "engine.1.log")
        assertTrue("轮转后应有 engine.1.log", history.isFile)
        assertTrue("历史段应保留上一轮的行", history.readText().contains("oldest-line-from-previous-run") ||
            (2..4).any { File(dir, "engine.$it.log").takeIf { f -> f.isFile }?.readText()?.contains("oldest-line-from-previous-run") == true })
    }

    @Test
    fun resetKeepsHistoryAndMarksANewStart() {
        val dir = tempDir("log-reset")
        val target = freshLog(dir)
        EngineLog.raw("evidence-from-before-restart")
        EngineLog.reset()
        val text = target.readText()

        assertTrue("重启前的证据不能丢", text.contains("evidence-from-before-restart"))
        assertTrue("启动应有分隔线", text.contains("===== engine start"))
    }

    @Test
    fun readAllIncludesPreviousSegments() {
        val dir = tempDir("log-readall")
        val target = freshLog(dir)
        File(dir, "engine.1.log").writeText("older-session-line\n")
        target.writeText("current-session-line\n")

        val all = EngineLog.readAll(target, 4096)
        assertTrue(all.contains("older-session-line"))
        assertTrue(all.contains("current-session-line"))
        assertTrue("旧段应排在当前段之前", all.indexOf("older-session-line") < all.indexOf("current-session-line"))
    }

    @Test
    fun readTailReturnsWholeFileWhenWithinBudget() {
        val dir = tempDir("tail-small")
        val target = File(dir, "engine.log").apply { writeText("line1\nline2\n") }

        assertEquals("line1\nline2\n", EngineLog.readTail(target, 4096))
    }

    @Test
    fun readTailReturnsOnlyTheTailOfLargeFiles() {
        val dir = tempDir("tail-large")
        val head = "head-line-".repeat(100) + "\n"
        val body = (1..2000).joinToString("") { "row-$it\n" }
        val target = File(dir, "engine.log").apply { writeText(head + body) }
        val maxBytes = (target.length() / 2)

        val tail = EngineLog.readTail(target, maxBytes)

        // 起点（跳过的首字节）丢弃残缺首行后，从完整的一行开始。
        assertFalse(tail.startsWith("\n"))
        assertTrue(tail.startsWith("row-"))
        assertTrue(tail.trimEnd().endsWith("row-2000"))
        assertTrue(tail.length.toLong() <= maxBytes + "row-2000\n".length)
    }

    @Test
    fun readTailDropsCharacterFragmentAtSkipBoundary() {
        val dir = tempDir("tail-utf8")
        val line = "汉字测试行首部填充".repeat(40) + "\n"
        val body = (1..100).joinToString("") { "第${it}行\n" }
        val target = File(dir, "engine.log").apply { writeText(line + body) }
        // 故意让跳过的字节数落在多字节字符中间。
        val maxBytes = target.length() - (target.length() / 3) - 1

        val tail = EngineLog.readTail(target, maxBytes)

        assertFalse("跳过起点截断了多字节字符，应丢弃残缺首行", tail.contains('\uFFFD'))
        assertTrue(tail.startsWith("第"))
        assertTrue(tail.trimEnd().endsWith("第100行"))
    }
}
