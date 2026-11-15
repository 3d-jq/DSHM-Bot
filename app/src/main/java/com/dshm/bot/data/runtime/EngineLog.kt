package com.dshm.bot.data.runtime

import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * 引擎日志：所有启动阶段都写到这里，格式对齐 dsh-Go 的 `[mono=…ms] [dsh-app] …`。
 * 文件是 `filesDir/engine.log`，引擎页直接读取展示。
 *
 * **启动不清空**（2026-11-15 改）：以前 [reset] 每次引擎启动都 `writeText("")`，
 * 一重启就把上一轮的诊断证据抹掉 —— 产物卡「不及时」排查时反复吃这个亏。
 * 现在每次启动只写一条分隔线；日志按段轮转（engine.log 满 → engine.1.log …），
 * 保留最近若干段（合计约 [MAX_SEGMENTS] × [MAX_BYTES]），不会写穿磁盘。
 *
 * token / API Key 在落盘前就打码（展示层的 [TextMasking] 保留为冗余防线）。
 */
object EngineLog {
    private const val TAG = "[dshm]"

    /** 单段上限：超过就轮转到下一段。 */
    private const val MAX_BYTES = 512L * 1024L

    /** 最多保留的历史段数（不含当前段）。 */
    private const val MAX_SEGMENTS = 4

    private const val FILE_NAME = "engine.log"

    private var file: File? = null
    private var startAt = 0L
    private val lock = Any()

    /**
     * 时间戳来源：Android 用单调的 [SystemClock]，JVM 单测环境没有它（调用即抛
     * "not mocked"），回退 wall clock。同一运行环境里只会稳定走其中一支，
     * startAt 与 [elapsedMs] 不会混用两种时钟。
     */
    private fun nowMs(): Long = runCatching { SystemClock.elapsedRealtime() }
        .getOrDefault(System.currentTimeMillis())

    fun init(context: Context) {
        if (file != null) return
        synchronized(lock) {
            file = File(context.applicationContext.filesDir, FILE_NAME)
            startAt = nowMs()
        }
    }

    /** 单测用：强制重绑日志文件（生产路径走 [init]，只初始化一次）。不触碰 [SystemClock]。 */
    internal fun rebindForTest(target: File) {
        synchronized(lock) { file = target }
    }

    /**
     * 新一次启动：**不再清空历史**，只写一条带挂钟时间的分隔线，方便按次定位。
     * 未初始化（单测路径）时空转。
     */
    fun reset() {
        val target = file ?: return
        synchronized(lock) {
            startAt = nowMs()
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT)
                .format(java.util.Date())
            appendLocked(target, "\n===== engine start $stamp =====\n")
        }
    }

    fun log(message: String) {
        val target = file ?: return // 未初始化（单测路径）：写入空转，也不取时间戳
        val ms = nowMs()
        append("[mono=${ms}ms] $TAG ${TextMasking.maskSecrets(message)}\n")
    }

    /** 原样追加引擎输出；token / API Key 落盘前打码，避免明文进 engine.log。 */
    fun raw(line: String) {
        append(TextMasking.maskSecrets(line) + "\n")
    }

    private fun append(text: String) {
        val target = file ?: return
        synchronized(lock) { appendLocked(target, text) }
    }

    private fun appendLocked(target: File, text: String) {
        if (target.length() + text.length > MAX_BYTES) rotate(target)
        runCatching { target.appendText(text) }
    }

    /**
     * 轮转：engine.(n).log 依次后移，当前段变成 engine.1.log，最老的丢弃；
     * 然后当前文件清空，新行从空文件开始写。历史段不被 [reset] 删除。
     */
    private fun rotate(target: File) {
        runCatching {
            val dir = target.parentFile ?: return
            for (index in MAX_SEGMENTS downTo 2) {
                val older = File(dir, "engine.${index - 1}.log")
                if (older.isFile) {
                    val next = File(dir, "engine.$index.log")
                    if (next.exists()) next.delete()
                    older.renameTo(next)
                }
            }
            val first = File(dir, "engine.1.log")
            if (first.exists()) first.delete()
            target.renameTo(first)
            target.writeText("")
        }
    }

    /** 按时间从旧到新拼出所有段的尾部，供引擎页展示（重启前的证据也能看到）。 */
    internal fun readAll(target: File, perSegmentBytes: Long): String {
        val dir = target.parentFile ?: return readTail(target, perSegmentBytes)
        val segments = (MAX_SEGMENTS downTo 1)
            .map { File(dir, "engine.$it.log") }
            .filter { it.isFile } + target.takeIf { it.isFile }.orEmpty()
        return segments.joinToString("") { readTail(it, perSegmentBytes) }
    }

    /**
     * 只读 [target] 末尾 [maxBytes] 字节并按 UTF-8 解码。
     * 日志可能很大，整读再裁剪会白占内存；跳过的起点可能落在多字节字符中间，
     * 因此丢弃残缺的首行。
     */
    internal fun readTail(target: File, maxBytes: Long): String = runCatching {
        val length = target.length()
        if (length <= maxBytes) return@runCatching target.readText()
        val skip = length - maxBytes
        val text = target.inputStream().use { input ->
            var remaining = skip
            while (remaining > 0) {
                val n = input.skip(remaining)
                if (n <= 0) break
                remaining -= n
            }
            input.readBytes().toString(Charsets.UTF_8)
        }
        text.substringAfter('\n', "")
    }.getOrDefault("")

    fun elapsedMs(): Long = nowMs() - startAt
}

private fun <T> T?.orEmpty(): List<T> = this?.let { listOf(it) } ?: emptyList()
