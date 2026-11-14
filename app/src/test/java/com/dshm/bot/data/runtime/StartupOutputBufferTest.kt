package com.dshm.bot.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动期 stdout 缓冲策略的回归测试：
 * 曾经拿到 token 后就没人再消费队列，读线程却一直往无界 LinkedBlockingQueue 里
 * put —— 引擎连跑数天，App 进程被慢慢吃穿。修复后 drain() 一旦调用，
 * 后续行不再入队（只进 EngineLog）。
 */
class StartupOutputBufferTest {

    @Test
    fun buffersLinesForTheStartupLoop() {
        val buffer = StartupOutputBuffer()

        buffer.onLine("starting proot…")
        buffer.onLine("dsh web: http://127.0.0.1:3080/?token=abc")

        assertEquals("starting proot…", buffer.poll(0))
        assertEquals("dsh web: http://127.0.0.1:3080/?token=abc", buffer.poll(0))
        assertNull(buffer.poll(0))
    }

    @Test
    fun tailKeepsOnlyTheMostRecentLines() {
        val buffer = StartupOutputBuffer(tailSize = 3)

        repeat(5) { index ->
            buffer.onLine("line-$index")
            buffer.poll(0)
        }

        assertEquals(listOf("line-2", "line-3", "line-4"), buffer.tailLines())
    }

    @Test
    fun drainStopsAcceptingNewLinesAndClearsTheQueue() {
        val buffer = StartupOutputBuffer()
        buffer.onLine("pre-token")
        buffer.poll(0)

        buffer.drain() // token 到手 / 启动失败：切丢弃模式

        // drain 后旧内容清空、新行不再入队 —— 长会话的输出不会无限堆积。
        assertNull(buffer.poll(0))
        repeat(1000) { buffer.onLine("noise-$it") }
        assertNull(buffer.poll(0))
        // 尾部保留 drain 前取过的行，供失败提示用。
        assertEquals(listOf("pre-token"), buffer.tailLines())
    }

    @Test
    fun pollTimesOutWithoutLines() {
        val buffer = StartupOutputBuffer()
        val startedAt = System.nanoTime()

        assertNull(buffer.poll(50))

        assertTrue(System.nanoTime() - startedAt >= 50_000_000L - 1_000_000L)
    }
}
