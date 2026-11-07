package com.dshm.bot.data.remote

import android.util.Log

/**
 * mux 的日志出口。
 *
 * 抽出来是为了单测 —— `android.util.Log` 在 JVM 单测里会抛
 * "Method i in android.util.Log not mocked"，把日志直接写在业务代码里
 * 会让整个测试套件挂掉。
 */
internal object DshMuxLog {

    private const val TAG = "DshMux"

    /** 真实实现：写 logcat。 */
    var sink: (String) -> Unit = { message -> Log.i(TAG, message) }

    fun log(message: String) {
        sink(message)
    }
}
