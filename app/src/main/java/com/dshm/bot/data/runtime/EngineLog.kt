package com.dshm.bot.data.runtime

import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * 引擎日志：所有启动阶段都写到这里，格式对齐 dsh-Go 的 `[mono=…ms] [dsh-app] …`。
 * 文件是 `filesDir/engine.log`，引擎页直接读取展示。
 *
 * 引擎可能连跑数天且持续输出，日志文件设了尺寸上限：超过就只保留尾部，
 * 不做上限的日志迟早把磁盘写穿。token / API Key 在落盘前就打码
 * （展示层的 [TextMasking] 保留为冗余防线）。
 */
object EngineLog {
    private const val TAG = "[dshm]"

    /** 单文件上限：写入前检查，超过就截断保留尾部。 */
    private const val MAX_BYTES = 1L shl 20

    /** 截断时保留的尾部大小：留出余量，避免每一笔写入都触发重写。 */
    private const val KEEP_BYTES = MAX_BYTES / 2

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
        file = File(context.applicationContext.filesDir, "engine.log")
        startAt = nowMs()
    }

    /** 单测用：强制重绑日志文件（生产路径走 [init]，只初始化一次）。不触碰 [SystemClock]。 */
    internal fun rebindForTest(target: File) {
        synchronized(lock) { file = target }
    }

    /** 新一次启动：清空旧日志并重新计时。未初始化（单测路径）时空转。 */
    fun reset() {
        synchronized(lock) {
            if (file == null) return
            startAt = nowMs()
            file?.writeText("")
        }
    }

    fun log(message: String) {
        if (file == null) return // 未初始化（单测路径）：写入空转，也不取时间戳
        val ms = nowMs()
        append("[mono=${ms}ms] $TAG ${TextMasking.maskSecrets(message)}\n")
    }

    /** 原样追加引擎输出；token / API Key 落盘前打码，避免明文进 engine.log。 */
    fun raw(line: String) {
        append(TextMasking.maskSecrets(line) + "\n")
    }

    private fun append(text: String) {
        val target = file ?: return
        synchronized(lock) {
            if (target.length() > MAX_BYTES) truncateToTail(target)
            target.appendText(text)
        }
    }

    /** 超限时只保留日志尾部：truncate 后体积回到 [KEEP_BYTES] 附近。 */
    private fun truncateToTail(target: File) {
        runCatching {
            val skip = target.length() - KEEP_BYTES
            val tail = if (skip > 0) {
                target.inputStream().use { input ->
                    var remaining = skip
                    while (remaining > 0) {
                        val n = input.skip(remaining)
                        if (n <= 0) break
                        remaining -= n
                    }
                    input.readBytes()
                }
            } else {
                ByteArray(0)
            }
            target.writeBytes(tail)
        }
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
