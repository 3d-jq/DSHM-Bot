package com.dshm.bot.domain.model

/**
 * 一条待确认的请求（Bot 要跑命令 / 要问用户）。
 *
 * 两个来源合并成一种：
 * 1. **DS Bot 的群聊审批**（`state.work.approvals`）—— 群成员 Bot 在群里跑命令，
 *    群会话是隐藏的，DSH 的卡片看不到，所以由 DS Bot 自己答
 *    （`approval-answer {id, choice}`）。
 * 2. **DSH 的主机审批**（`$events` 的 `approval/request`）—— Bot 私聊里跑命令，
 *    在移动端由我们这个 App 当那张卡片（`$events/result`）。
 */
data class ChatApproval(
    /** 本地唯一 id —— 用来定位要应答哪一条。 */
    val key: String,
    /** 要放行的工具名。 */
    val tool: String,
    /** 为什么需要确认。 */
    val reason: String,
    /** 详情（命令内容之类）。 */
    val detail: String? = null,
    /** 错误码。 */
    val code: String? = null,
    /** 能不能由我们应答。 */
    val answerable: Boolean = true,
    /** 内部来源标记 —— 应答时要知道走哪条路。 */
    val source: Source = Source.DSH_HOST,
) {
    /** 审批的来源。 */
    enum class Source {
        /** DSH 主机事件（`$events` 的 `approval/request`）。 */
        DSH_HOST,

        /** DS Bot 的群聊审批（`state.work.approvals`）。 */
        DS_BOT,
    }
}
