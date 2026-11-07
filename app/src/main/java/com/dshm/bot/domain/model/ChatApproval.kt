package com.dshm.bot.domain.model

/**
 * 一条待确认的请求（Bot 要跑命令 / 要问用户）。
 *
 * 显示身份只有一个来源：**DS Bot 的 `state.work.approvals`**（官方客户端也是只看这一份）。
 * DSH 主机事件流（`$events` 的 `approval/request`）只贡献一样东西 —— 应答要用的 [eventId]。
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
    val action: Action = Action.WAIT_ENGINE,
    /** 请求的种类：审批 / Bot 反问用户。 */
    val kind: Kind = Kind.APPROVAL,
    /** 内部来源标记 —— 应答时要知道走哪条路。 */
    val source: Source = Source.DSH_HOST,
    /** 主机事件 id：私聊回 `$events/result` 时必须带上；没拿到就是 null。 */
    val eventId: String? = null,
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

        /** 归我们答，但还没拿到 eventId（事件流没连上）：按钮置灰 + 说明原因。 */
        WAIT_ENGINE,

        /** 应答入口不在我们手上：说明去哪儿答。 */
        ANSWER_ELSEWHERE,
    }

    /** 应答走哪条路。 */
    enum class Source {
        /** DSH 主机事件：`$events/result`，带 [eventId]。 */
        DSH_HOST,

        /** DS Bot：`approval-answer {id, choice}`。 */
        DS_BOT,
    }
}
