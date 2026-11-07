package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.BotApproval
import com.dshm.bot.data.remote.DshApprovalRequest
import com.dshm.bot.data.remote.DshQuestionRequest
import com.dshm.bot.domain.model.ChatApproval

/**
 * 审批卡片的合并规则（纯函数，JVM 可测）。
 *
 * **显示身份只认 DS Bot 的 `state.work.approvals`** —— 官方客户端也只看这一份
 * （`ds-bot/src/client/member-status.js` 的 `ApprovalCard`）。DS Bot 在
 * `ds-bot/src/host/approvals.js` 里已经把 DSH 的 `agentId` 关联成了 `botId` / `roomId`，
 * 所以「这条属于哪个对话」不用我们猜。
 *
 * 引擎事件流（`$events` 的 `approval/request`）只补一样东西：**应答用的 eventId**。
 * - 群聊：会话是隐藏的，DS Bot 自己 hold 住请求，只能走 `approval-answer`；
 *   我们绝不能再回 `$events/result`，否则两个应答入口抢同一次请求。
 * - 私聊：DS Bot 只是「备注谁在等」（`answerable = false`），真正把请求交给 DSH 的
 *   waterfall；移动端没有 DSH 卡片，**我们就是那张卡片**，必须回 `$events/result`。
 *   没拿到 eventId 之前按钮是灰的 —— 而不是再画一张「去 DSH 里答」的死路卡片。
 */
/**
 * 主机应答通道是否可用（事件流已连上并拿到当前代的 clientId）。
 *
 * 私聊的应答走 `$events/result`，链路断了就发不出去；这时卡片要退回
 * [ChatApproval.Action.WAIT_ENGINE]，而不是留着一个点了报错的按钮。
 * 群聊走 DS Bot 的 `approval-answer`（普通 HTTP），不受它影响。
 */
internal fun mergeChatApprovals(
    botEntries: List<BotApproval>,
    hostApprovals: List<DshApprovalRequest>,
    hostQuestions: List<DshQuestionRequest>,
    isGroup: Boolean,
    botId: String,
    sessionId: String?,
    hostAnswerable: Boolean = true,
    locale: String = "zh",
): List<ChatApproval> {
    // DS Bot 说这几条属于本对话（私聊按 botId，群聊按 roomId），它给的 sessionId
    // 就是引擎事件流里的 agentId —— 两边靠这个对齐。
    val ourSessions = botEntries.map { it.sessionId }.filterNot { it.isEmpty() }.toSet()
    val relevant = hostApprovals.filter { isRelevant(it.sessionId, ourSessions, isGroup, botId, sessionId) }
    val relevantQuestions =
        hostQuestions.filter { isRelevant(it.sessionId, ourSessions, isGroup, botId, sessionId) }

    val claimed = mutableSetOf<String>()
    val merged = buildList {
        botEntries.forEach { entry ->
            val host = relevant.firstOrNull {
                it.sessionId == entry.sessionId && it.eventId !in claimed
            }
            if (host != null) claimed += host.eventId
            add(
                ChatApproval(
                    key = "bot:${entry.id}",
                    tool = entry.tool,
                    reason = entry.reason,
                    detail = entry.detailText,
                    code = entry.detailCode,
                    // 群聊由 DS Bot 应答；私聊只有拿到 eventId 才答得了。
                    action = when {
                        isGroup -> if (entry.answerable) {
                            ChatApproval.Action.ANSWER
                        } else {
                            ChatApproval.Action.ANSWER_ELSEWHERE
                        }

                        host != null && hostAnswerable -> ChatApproval.Action.ANSWER
                        else -> ChatApproval.Action.WAIT_ENGINE
                    },
                    source = if (isGroup) ChatApproval.Source.DS_BOT else ChatApproval.Source.DSH_HOST,
                    eventId = host?.eventId,
                ),
            )
        }

        // DS Bot 的 state 快照比事件流慢半拍：先把引擎已经在等的这条画出来，
        // 免得人对着灰按钮干等。state 刷新后会被上面那条同 sessionId 的条目取代。
        if (!isGroup) {
            relevant.filterNot { it.eventId in claimed }.forEach { host ->
                add(
                    ChatApproval(
                        key = "host:${host.eventId}",
                        tool = host.toolName,
                        reason = host.describe(locale),
                        detail = host.reason,
                        action = if (hostAnswerable) ChatApproval.Action.ANSWER else ChatApproval.Action.WAIT_ENGINE,
                        source = ChatApproval.Source.DSH_HOST,
                        eventId = host.eventId,
                    ),
                )
            }
        }

        // Bot 反问用户：选项 UI 和 `userQuestions/*` 还没接，先把问题本身显示出来，
        // 不静默丢弃（丢了这一轮就一直卡着）。
        relevantQuestions.forEach { question ->
            val text = question.questions.firstOrNull()?.question.orEmpty()
            add(
                ChatApproval(
                    key = "question:${question.eventId}",
                    tool = "",
                    reason = text.ifEmpty { question.questions.firstOrNull()?.header.orEmpty() },
                    detail = question.questions.firstOrNull()?.detail,
                    action = ChatApproval.Action.ANSWER_ELSEWHERE,
                    kind = ChatApproval.Kind.QUESTION,
                    source = ChatApproval.Source.DSH_HOST,
                    eventId = question.eventId,
                ),
            )
        }
    }
    return merged.distinctBy { it.key }
}

/**
 * 这个引擎请求属不属于当前对话。
 *
 * 三条路，按可靠性从高到低：
 * 1. DS Bot 已经把它认成本对话的条目（`ourSessions`，私聊 / 群聊都适用）；
 * 2. 私聊里等于 `chat` 接口回来的 sessionId（= `bot.sessionId`，见 `store.js` 的 `chatOf`）；
 * 3. Bot 还没有自己的会话时，DS Bot 用 botId 当会话 id。
 * 群聊的成员会话是隐藏的，走不到 2 / 3 —— 只能靠 1，所以群聊不会凭空多出一张卡。
 */
internal fun isRelevant(
    agentSessionId: String,
    ourSessions: Set<String>,
    isGroup: Boolean,
    botId: String,
    sessionId: String?,
): Boolean = agentSessionId.isNotEmpty() &&
    (agentSessionId in ourSessions || (!isGroup && agentSessionId == (sessionId ?: botId)))
