package com.dshm.bot.data.runtime

import android.content.Context
import java.io.File

/**
 * 客户端日志：`filesDir/client.log`（分段轮转，同 [EngineLog] 的规矩）。
 *
 * 为什么要有它：客户端自己的诊断（[com.dshm.bot.data.remote.DshMuxLog]）原先只写
 * logcat，而**不少 ROM 会过滤第三方应用的日志**（真机实测：进程在跑、logcat 里却
 * 一条应用日志都没有）。于是「产物卡不及时」「行错位」这类问题在真机上**没有证据**，
 * 只能靠猜 —— 这正是之前反复打补丁的根源。
 *
 * 落盘后：诊断不再依赖 logcat，`adb shell run-as com.dshm.bot cat files/client.log`
 * 就能拿到完整现场（宿主侧证据在 `engine.log`，两边一起看）。
 *
 * 与 [EngineLog] 的关系：两个文件、同一套规矩（启动不清空、按段轮转、保留若干段）。
 * 分开是因为引擎页会把 `engine.log` 直接展示给用户，客户端日志不该混进去。
 */
object ClientLog {
    /** 单段上限：超过就轮转到下一段。 */
    private const val MAX_BYTES = 512L * 1024L

    /** 最多保留的历史段数（不含当前段）。 */
    private const val MAX_SEGMENTS = 4

    private const val FILE_NAME = "client.log"

    private var file: File? = null
    private val lock = Any()

    /** 初始化并挂到 [com.dshm.bot.data.remote.DshMuxLog] 的出口（幂等）。 */
    fun install(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (file == null) {
                file = File(appContext.filesDir, FILE_NAME)
            }
        }
        com.dshm.bot.data.remote.DshMuxLog.sink = { message ->
            write(message)
            // logcat 也留一份（开发机上看得到更方便）；ROM 过滤了也不影响落盘。
            runCatching { android.util.Log.i("DshMux", message) }
        }
    }

    /** 单测用：强制重绑日志文件。 */
    internal fun rebindForTest(target: File) {
        synchronized(lock) { file = target }
    }

    /** 当前日志文件（诊断页展示用）。 */
    fun currentFile(): File? = file

    /** 历史段（最新在前）：`client.log`、`client.1.log` … */
    fun segments(): List<File> {
        val current = file ?: return emptyList()
        val out = ArrayList<File>()
        out += current
        for (index in 1..MAX_SEGMENTS) {
            val older = File(current.parentFile, "$FILE_NAME".replace(".log", ".$index.log"))
            if (!older.exists()) break
            out += older
        }
        return out
    }

    fun log(message: String) = write(message)

    private fun write(message: String) {
        val target = file ?: return
        synchronized(lock) {
            try {
                target.parentFile?.mkdirs()
                if (target.length() > MAX_BYTES) rotate(target)
                target.appendText(message + "\n")
            } catch (_: Exception) {
                // 日志写不进去不能影响业务。
            }
        }
    }

    /** 轮转：`client.log` → `client.1.log` → …，丢掉最老的一段。 */
    private fun rotate(current: File) {
        val oldest = File(current.parentFile, "client.${MAX_SEGMENTS}.log")
        if (oldest.exists()) oldest.delete()
        for (index in MAX_SEGMENTS - 1 downTo 1) {
            val from = File(current.parentFile, "client.$index.log")
            val to = File(current.parentFile, "client.${index + 1}.log")
            if (from.exists()) {
                if (to.exists()) to.delete()
                from.renameTo(to)
            }
        }
        val first = File(current.parentFile, "client.1.log")
        if (first.exists()) first.delete()
        current.renameTo(first)
    }
}
