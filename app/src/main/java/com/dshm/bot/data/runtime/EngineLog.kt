package com.dshm.bot.data.runtime

import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * 引擎日志：所有启动阶段都写到这里，格式对齐 dsh-Go 的 `[mono=…ms] [dsh-app] …`。
 * 文件是 `filesDir/engine.log`，引擎页直接读取展示。
 */
object EngineLog {
    private const val TAG = "[dshm]"
    private var file: File? = null
    private var startAt = 0L
    private val lock = Any()

    fun init(context: Context) {
        if (file != null) return
        file = File(context.applicationContext.filesDir, "engine.log")
        startAt = SystemClock.elapsedRealtime()
    }

    /** 新一次启动：清空旧日志并重新计时。 */
    fun reset() {
        synchronized(lock) {
            startAt = SystemClock.elapsedRealtime()
            file?.writeText("")
        }
    }

    fun log(message: String) {
        val ms = SystemClock.elapsedRealtime()
        val target = file ?: return
        synchronized(lock) {
            target.appendText("[mono=${ms}ms] $TAG $message\n")
        }
    }

    /** 原样追加引擎输出（token 由展示层打码）。 */
    fun raw(line: String) {
        val target = file ?: return
        synchronized(lock) {
            target.appendText(line + "\n")
        }
    }

    fun elapsedMs(): Long = SystemClock.elapsedRealtime() - startAt
}
