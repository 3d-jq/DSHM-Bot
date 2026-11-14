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
    fun truncatesToTailWhenFileGrowsOverCap() {
        val target = freshLog(tempDir("log-cap"))
        val marker = "oldest-line-that-must-not-survive"
        EngineLog.raw(marker)
        // 1 MiB 上限：每行 ~1KB，写 3 MiB 必触发多次截断。
        repeat(3 * 1024) { index ->
            EngineLog.raw("chunk-$index-" + "x".repeat(960))
        }
        EngineLog.raw("the-latest-line")

        val text = target.readText()
        // 体积被压回上限以内（允许最后一行的零头），且保留的是尾部而不是开头。
        assertTrue("日志体积应 ≤ 1 MiB + 一行的余量，实际 ${target.length()}", target.length() <= (1L shl 20) + 4096)
        assertFalse(text.contains(marker))
        assertTrue(text.contains("the-latest-line"))
        // 只保留尾部：靠后的 chunk 还在，前半段（1 MiB 附近）的旧行已被截掉。
        assertTrue(text.contains("chunk-${3 * 1024 - 1}-"))
        assertFalse(text.contains("chunk-1023-"))
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
