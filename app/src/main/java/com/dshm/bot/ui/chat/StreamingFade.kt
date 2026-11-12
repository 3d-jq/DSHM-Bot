package com.dshm.bot.ui.chat

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * 逐词淡入（对齐 DeepSearch `FadeController` / iOS TextFadeAnimator）。
 *
 * 只对「新追加的后缀」做 α 0→1，前缀保持不透明；一个 frame 循环只在有
 * 活跃区间时跑，全部淡完就挂起，空闲零成本。burst 超过 160 个词直接整批
 * 不透明，避免 1k-token 重排把帧预算吃光。
 */
internal class StreamingFadeController {
    private data class Range(val start: Int, val end: Int, val startNanos: Long, val staggerMs: Long)

    private var lastText = ""
    private val ranges = mutableListOf<Range>()
    private val ticker = mutableFloatStateOf(0f)
    val active: Boolean get() = ranges.isNotEmpty()

    fun ingest(text: String) {
        if (text == lastText) return
        if (!text.startsWith(lastText)) {
            ranges.clear()
            lastText = text
            return
        }
        val base = lastText.length
        val suffix = text.substring(base)
        lastText = text
        if (suffix.isEmpty()) return

        val words = mutableListOf<IntRange>()
        var cursor = 0
        var inWord = false
        var wordStart = 0
        for (index in suffix.indices) {
            val ws = suffix[index].isWhitespace()
            if (!ws && !inWord) {
                wordStart = index
                inWord = true
            } else if (ws && inWord) {
                words.add(wordStart until index)
                inWord = false
            }
            cursor = index
        }
        if (inWord) words.add(wordStart..cursor)
        if (words.isEmpty() || words.size + ranges.size > MAX_FADE_WORDS) {
            ranges.clear()
            return
        }
        val now = System.nanoTime()
        val per = minOf(PER_WORD_STAGGER_MS, STAGGER_WINDOW_MS / words.size.coerceAtLeast(1))
        words.forEachIndexed { index, word ->
            ranges.add(
                Range(
                    start = base + word.first,
                    end = base + word.last + 1,
                    startNanos = now,
                    staggerMs = index * per,
                ),
            )
        }
    }

    /** 返回 true 表示还有区间在淡入，frame 循环继续。 */
    fun tick(nowNanos: Long): Boolean {
        if (ranges.isEmpty()) return false
        ranges.removeAll { range ->
            val elapsedMs = (nowNanos - range.startNanos) / 1_000_000L - range.staggerMs
            elapsedMs >= FADE_DURATION_MS
        }
        ticker.floatValue += 1f
        return ranges.isNotEmpty()
    }

    fun overlay(base: AnnotatedString, color: Color): AnnotatedString {
        if (ranges.isEmpty()) return base
        @Suppress("UNUSED_VARIABLE") val frame = ticker.floatValue
        val nowNanos = System.nanoTime()
        return buildAnnotatedString {
            append(base)
            for (range in ranges) {
                if (range.end > base.length) continue
                val elapsed = (nowNanos - range.startNanos) / 1_000_000L - range.staggerMs
                val progress = (elapsed.coerceAtLeast(0).toFloat() / FADE_DURATION_MS).coerceIn(0f, 1f)
                val inv = 1f - progress
                val alpha = 1f - inv * inv * inv
                addStyle(SpanStyle(color = color.copy(alpha = alpha)), range.start, range.end)
            }
        }
    }

    private companion object {
        const val FADE_DURATION_MS = 350L
        const val STAGGER_WINDOW_MS = 300L
        const val PER_WORD_STAGGER_MS = 90L
        const val MAX_FADE_WORDS = 160
    }
}

@Composable
internal fun rememberStreamingFade(): StreamingFadeController = remember { StreamingFadeController() }

/** 单个 live tail 的 frame 驱动；没有活跃淡入区间就自动挂起。 */
@Composable
internal fun StreamingFadeDriver(controller: StreamingFadeController) {
    LaunchedEffect(controller) {
        while (true) {
            // 没有活跃区间时等一帧再查，空闲成本≈0；有新词立刻被 ingest 唤醒。
            val anyActive = withFrameNanos { now -> controller.tick(now) }
            if (!anyActive) kotlinx.coroutines.delay(16L)
        }
    }
}

/** 慢解析埋点：只打超过阈值的单次解析，平时静默（DeepSearch `SLOW_PARSE_LOG_MS`）。 */
internal object StreamRenderLog {
    private const val TAG = "dshm-stream"
    private const val SLOW_PARSE_MS = 80L

    fun slowParse(layer: String, chars: Int, parseMs: Long, thread: String) {
        if (parseMs < SLOW_PARSE_MS) return
        Log.d(TAG, "slow layer=$layer chars=$chars ms=$parseMs thread=$thread")
    }

    fun flush(chars: Int, intervalMs: Long) {
        if (intervalMs < SLOW_PARSE_MS) return
        Log.d(TAG, "slow flush chars=$chars intervalMs=$intervalMs")
    }
}
