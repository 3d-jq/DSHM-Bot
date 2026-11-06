package com.dshm.bot.data.remote

import org.json.JSONObject

/**
 * 一条待审批的请求（DS Bot 的 `state.work.approvals` 里的一项）。
 *
 * 引擎侧结构（`ds-bot/src/host/approvals.js`）：
 * `{id, sessionId, botId, roomId?, tool, reason, detail:{text?, code?}, at, answerable}`
 *
 * `answerable` 表示这个请求能由我们应答（true）还是只能在 DSH 自己的卡片里答
 * （false —— 那种情况界面要引导用户去 DSH 那边点）。
 */
data class BotApproval(
    val id: String,
    val sessionId: String,
    val botId: String,
    val roomId: String? = null,
    /** 要用的工具名。 */
    val tool: String,
    /** 为什么需要审批。 */
    val reason: String,
    /** 详情文本（命令内容之类）。 */
    val detailText: String? = null,
    /** 错误码。 */
    val detailCode: String? = null,
    /** 能不能由我们应答。 */
    val answerable: Boolean = true,
)

/** 解析 `state.work.approvals`。 */
fun parseBotApprovals(raw: JSONObject?): List<BotApproval> {
    val work = raw?.optJSONObject("work") ?: return emptyList()
    val approvals = work.optJSONArray("approvals") ?: return emptyList()
    val out = mutableListOf<BotApproval>()
    for (index in 0 until approvals.length()) {
        val item = approvals.optJSONObject(index) ?: continue
        val id = item.optString("id")
        if (id.isEmpty()) continue
        val detail = item.optJSONObject("detail")
        out += BotApproval(
            id = id,
            sessionId = item.optString("sessionId"),
            botId = item.optString("botId"),
            roomId = item.optString("roomId").takeIf { it.isNotEmpty() },
            tool = item.optString("tool"),
            reason = item.optString("reason"),
            detailText = detail?.optString("text")?.takeIf { it.isNotEmpty() },
            detailCode = detail?.optString("code")?.takeIf { it.isNotEmpty() },
            answerable = item.optBoolean("answerable", true),
        )
    }
    return out
}

/** 应答审批：`approval-answer {id, choice}`，choice 是 `allow` 或 `reject`。 */
class BotApprovalsRepository(private val client: DshClient) {

    /** 允许一次。 */
    suspend fun allow(id: String) = answer(id, "allow")

    /** 拒绝。 */
    suspend fun reject(id: String) = answer(id, "reject")

    private suspend fun answer(id: String, choice: String) {
        client.bot(
            "approval-answer",
            JSONObject().put("id", id).put("choice", choice),
        )
    }
}
