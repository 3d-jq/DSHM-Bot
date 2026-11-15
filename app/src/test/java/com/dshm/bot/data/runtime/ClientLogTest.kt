package com.dshm.bot.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 客户端日志落盘：分段轮转 + 写失败不影响业务。 */
class ClientLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun writesLandInTheFile() {
        val target = folder.newFile("client.log")
        ClientLog.rebindForTest(target)
        ClientLog.log("第一条")
        ClientLog.log("第二条")
        val text = target.readText()
        assertTrue(text.contains("第一条"))
        assertTrue(text.contains("第二条"))
    }

    @Test
    fun aFullSegmentRotatesAndKeepsHistory() {
        val target = folder.newFile("client.log")
        ClientLog.rebindForTest(target)
        // 写超过单段上限 → 轮转；历史不能丢。
        val chunk = "x".repeat(1024)
        repeat(600) { ClientLog.log(chunk) }
        val rotated = folder.root.resolve("client.1.log")
        assertTrue("满段后应轮转出 client.1.log", rotated.exists())
        assertTrue("轮转后当前段仍可写", target.exists())
        assertTrue("历史内容不能丢", rotated.length() > 0)
    }

    @Test
    fun segmentsAreListedNewestFirst() {
        val target = folder.newFile("client.log")
        ClientLog.rebindForTest(target)
        val chunk = "y".repeat(1024)
        repeat(600) { ClientLog.log(chunk) }
        val segments = ClientLog.segments()
        assertTrue(segments.size >= 2)
        assertEquals("client.log", segments.first().name)
        assertEquals("client.1.log", segments[1].name)
    }
}
