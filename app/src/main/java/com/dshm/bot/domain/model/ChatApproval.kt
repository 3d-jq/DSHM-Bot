package com.dshm.bot.domain.model

/**
 * 一条待确认的请求（Bot 要跑命令 / 要问用户）。
 *
 * 显示和应答都走 DS Bot：`state.work.approvals` 给卡片，`approval-answer` 回执。
 * 不依赖 DSH 主机事件流（`$events`），所以权限卡片不会被连接状态挡住。
 */
data class ChatApproval(
    /** 本地唯一 id —— 用来定位要应答哪一条，也是列表的稳定 key。 */
    val key: String,
    /** 要放行的工具名（提问时为空）。 */
    val tool: String,
    /** 为什么需要确认。 */
    val reason: String,
    /** 详情（命令内容之类）。 */
    val detail: String? = null,
    /** 错误码。 */
    val code: String? = null,
    /** 这张卡现在能让用户做什么。 */
    val action: Action = Action.ANSWER_ELSEWHERE,
    /** 请求的种类：审批 / Bot 反问用户。 */
    val kind: Kind = Kind.APPROVAL,
    /** 内部来源标记 —— 保留给提问卡片等还不能本地应答的类型。 */
    val source: Source = Source.DS_BOT,
) {
    /** 请求种类。 */
    enum class Kind {
        /** Bot 要跑一个敏感工具，等人放行。 */
        APPROVAL,

        /** Bot 反过来问用户问题（App 还不能在这里作答）。 */
        QUESTION,
    }

    /** 卡片能做什么 —— 界面按这个分支渲染，不再猜布尔值。 */
    enum class Action {
        /** 有应答入口：显示「拒绝 / 允许一次」。 */
        ANSWER,

        /** 应答入口不在我们手上：说明去哪儿答。 */
        ANSWER_ELSEWHERE,
    }

    /** 应答走哪条路。 */
    enum class Source {
        /** DSH 主机事件：暂未在移动端提供应答入口。 */
        DSH_HOST,

        /** DS Bot：`approval-answer {id, choice}`。 */
        DS_BOT,
    }
}
