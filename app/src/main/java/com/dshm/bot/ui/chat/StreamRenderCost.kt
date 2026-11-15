package com.dshm.bot.ui.chat

import android.os.SystemClock

/**
 * 流式 markdown 的**自适应节流**（对齐 DeepSeek 官方 demo 的
 * `useThrottleMarkdownRender`：按渲染耗时 × 2 决定下次更新时机，上限 2s）。
 *
 * 官方量的是 React 提交（渲染）耗时。我们这边渲染被拆成「后台解析 + 主线程排版」，
 * 解析耗时是**可测且与复杂度强相关**的那一段（长表格 / 长代码块最贵），所以拿它当
 * 代价信号：解析越慢，下一次刷新间隔越宽 —— 内容再连续涌入也会被合并成最新一份，
 * 不会把主线程压垮、也不会让内容越落越滞后。
 *
 * 与旧的「按文本长度分档」的关系：长度仍是**下限**（短回答照样 30ms 就推，观感是
 * 逐字落），自适应只在「上一帧确实很贵」时把间隔拉宽。两者取大。
 *
 * 纯观测对象，无 Android 依赖之外的状态；未观测过时 [delayFromNowMs] 返回 0。
 */
internal object StreamRenderCost {

    /** 两次更新的最长间隔（官方 `MAX_WAIT`）。 */
    private const val MAX_WAIT_MS = 2_000L

    /** 耗时倍率（官方 `TIME_CONSUMING_RATE`）。 */
    private const val COST_RATE = 2L

    /** 最近一次解析耗时（诊断用）。 */
    @Volatile
    var lastParseMs: Long = 0L
        private set

    /**
     * 时间源：JVM 单测里 `SystemClock` 未 mock 会抛 "not mocked"，
     * 回退 wall clock（与 EngineLog 同做法）。同一环境只走其中一支。
     */
    private fun nowMs(): Long = runCatching { SystemClock.elapsedRealtime() }
        .getOrDefault(System.currentTimeMillis())

    /** 允许下次刷新的时刻。 */
    @Volatile
    private var updatableAtMs: Long = 0L

    /**
     * 报告一次渲染（解析）耗时。下次刷新至少等 `耗时 × 2`（不超过 2s）。
     * 由 `MarkdownFragment` 在解析落地后调用。
     */
    fun observeRenderCost(costMs: Long) {
        lastParseMs = costMs
        if (costMs <= 0L) return
        val wait = (costMs * COST_RATE).coerceAtMost(MAX_WAIT_MS)
        updatableAtMs = nowMs() + wait
    }

    /** 距离下次可刷新还剩多久（0 = 现在就可以）。 */
    fun delayFromNowMs(): Long {
        val remaining = updatableAtMs - nowMs()
        return if (remaining > 0L) remaining else 0L
    }

    /** 单测/会话切换用：清掉观测值。 */
    fun reset() {
        lastParseMs = 0L
        updatableAtMs = 0L
    }
}
