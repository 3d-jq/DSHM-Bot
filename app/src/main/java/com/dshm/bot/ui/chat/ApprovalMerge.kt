package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.BotApproval
import com.dshm.bot.data.remote.DshQuestionRequest
import com.dshm.bot.domain.model.ChatApproval

/**
 * 卡片的合并规则（纯函数，JVM 可测）。
 *
 * **审批只认 DS Bot 的 `state.work.approvals`** —— 官方客户端也只看这一份
 * （`ds-bot/client.js` 的 `ApprovalCard`），并且用同一个 `approval-answer` 应答。
 * DS Bot 已经把 DSH 的 `agentId` 关联成了 `botId` / `roomId`，所以「这条属于哪个
 * 对话」不用我们猜，也不需要等 `$events` 的 eventId。
 *
 * 主机事件流只用于显示「Bot 反问用户」；这类卡片还没有选项 UI，不提供假按钮。
 */
internal fun mergeChatApprovals(
    botEntries: List<BotApproval>,
    hostQuestions: List<DshQuestionRequest>,
    isGroup: Boolean,
    botId: String,
    sessionId: String?,
    locale: String = "zh",
): List<ChatApproval> {
    val ourSessions = botEntries.map { it.sessionId }.filterNot { it.isEmpty() }.toSet()
    val relevantQuestions =
        hostQuestions.filter { isRelevant(it.sessionId, ourSessions, isGroup, botId, sessionId) }

    return buildList {
        botEntries.forEach { entry ->
            add(
                ChatApproval(
                    key = "bot:${entry.id}",
                    tool = entry.tool,
                    reason = entry.reason,
                    detail = entry.detailText,
                    code = entry.detailCode,
                    // DS Bot 已经 hold 住请求；answerable=false 只说明旧插件没接管，
                    // 这种情况不画假按钮，让她去 DS Bot 的界面答。
                    action = if (entry.answerable) {
                        ChatApproval.Action.ANSWER
                    } else {
                        ChatApproval.Action.ANSWER_ELSEWHERE
                    },
                    source = ChatApproval.Source.DS_BOT,
                ),
            )
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
                ),
            )
        }
    }.distinctBy { it.key }
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
