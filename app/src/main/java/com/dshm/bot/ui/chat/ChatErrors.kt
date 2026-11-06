package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.DshRpcException

/**
 * DS Bot 的错误本地化，对齐 dsh-Go `app/lib/state/bots_state.dart` 的
 * `describeDsBotError`。
 *
 * 引擎的报错是英文长句（尤其是「团队被另一个 dsh 占用」那一段），直接甩给用户
 * 看不懂也不像我们自己的 App —— 这里按 code 换成本地文案。
 */
fun describeChatError(error: Throwable): String = when (error) {
    is DshRpcException -> when (error.code) {
        // 团队被另一个 dsh 进程占用：当前只能看，不能改。
        "bot/read-only" -> "另一个 DSH 正在使用这个团队，请关闭它后重试"
        "bot/not-found" -> "没有这个功能或条目"
        "bot/refused" -> error.message ?: "操作被拒绝"
        "bot/internal" -> "引擎内部出错了"
        else -> error.message ?: "操作失败"
    }
    else -> error.message ?: "操作失败"
}
