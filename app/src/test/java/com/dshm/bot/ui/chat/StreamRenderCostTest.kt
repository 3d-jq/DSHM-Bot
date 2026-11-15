package com.dshm.bot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自适应节流：照 DeepSeek 官方 demo 的「渲染耗时 × 2，上限 2s」。 */
class StreamRenderCostTest {

    @Test
    fun noObservationMeansNoDelay() {
        StreamRenderCost.reset()
        assertEquals(0L, StreamRenderCost.delayFromNowMs())
    }

    @Test
    fun aSlowRenderWidensTheNextUpdate() {
        StreamRenderCost.reset()
        StreamRenderCost.observeRenderCost(120L)
        // 120ms × 2 = 240ms 左右（留时间误差）
        val delay = StreamRenderCost.delayFromNowMs()
        assertTrue("应拉宽到 200ms 以上，实际 $delay", delay > 150L)
        assertTrue("不应超过 2× 太多，实际 $delay", delay <= 240L)
    }

    @Test
    fun theDelayIsCappedAtTwoSeconds() {
        StreamRenderCost.reset()
        StreamRenderCost.observeRenderCost(5_000L) // 5s × 2 = 10s，要被上限截断
        val delay = StreamRenderCost.delayFromNowMs()
        assertTrue("上限 2s，实际 $delay", delay <= 2_000L)
        assertTrue("接近上限，实际 $delay", delay > 1_800L)
    }

    @Test
    fun aFastRenderAddsNoDelay() {
        StreamRenderCost.reset()
        StreamRenderCost.observeRenderCost(0L)
        assertEquals(0L, StreamRenderCost.delayFromNowMs())
    }
}
