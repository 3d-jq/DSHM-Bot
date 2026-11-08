package com.dshm.bot.ui.chat

import org.json.JSONArray
import org.json.JSONObject

/** `org.json.JSONArray` 没有 Kotlin 的 `forEach`；蓝图遍历统一走这个。 */
internal inline fun JSONArray.forEachElement(block: (Any?) -> Unit) {
    for (index in 0 until length()) block(opt(index))
}

/**
 * 输入框上方那道「待办 dock」里的卡（DS Bot `conversation.input.dock`）。
 *
 * 两个来源都在 DS Bot 的 `state` 里（`channel.js view()` 的 `questions` / `views`）：
 * - `state.questions[botId]`：`ask_user` 提的问题卡（`answer-question` 作答）；
 * - `state.views[botId]`：`show_view` 等答复的视图卡（`view-reply` 作答）。
 * 单聊主机的 `ask_user_question` 被 DS Bot 从 Bot 的工具表里拿掉了，所以 Bot 的提问
 * 走的是这一条，不是 DSH 的 `user-questions` 瀑布流。
 */
data class ChatQuestionCard(
    val id: String,
    val question: String,
    val detail: String?,
    val options: List<ChatQuestionOption>,
    val multiSelect: Boolean,
    val allowCustom: Boolean,
)

data class ChatQuestionOption(val label: String, val description: String?)

/** `show_view` 的蓝图（`src/shared/views.js` 校验过的那份 JSON）。 */
data class ChatViewCard(
    val id: String,
    val title: String?,
    val view: JSONObject,
)

/** 取 `state.questions[sessionId]`；没有或形状不对时为空。 */
fun parseChatQuestions(state: JSONObject, sessionId: String): List<ChatQuestionCard> {
    val item = state.optJSONObject("questions")?.optJSONObject(sessionId) ?: return emptyList()
    val id = item.optString("id").takeIf { it.isNotEmpty() } ?: return emptyList()
    val question = item.optString("question").trim()
    if (question.isEmpty()) return emptyList()
    val options = item.optJSONArray("options")?.let { array ->
        (0 until array.length()).mapNotNull { index ->
            val option = array.optJSONObject(index) ?: return@mapNotNull null
            val label = option.optString("label").trim()
            if (label.isEmpty()) return@mapNotNull null
            ChatQuestionOption(label, option.optString("description").takeIf { it.isNotEmpty() })
        }
    }.orEmpty()
    return listOf(
        ChatQuestionCard(
            id = id,
            question = question,
            detail = item.optString("detail").takeIf { it.isNotEmpty() },
            options = options,
            multiSelect = item.optBoolean("multiSelect", false),
            allowCustom = item.optBoolean("allowCustom", false),
        ),
    )
}

/** 取 `state.views[sessionId]`；没有或形状不对时为空。 */
fun parseChatViews(state: JSONObject, sessionId: String): List<ChatViewCard> {
    val item = state.optJSONObject("views")?.optJSONObject(sessionId) ?: return emptyList()
    val id = item.optString("id").takeIf { it.isNotEmpty() } ?: return emptyList()
    val view = item.optJSONObject("view") ?: return emptyList()
    return listOf(
        ChatViewCard(
            id = id,
            title = item.optString("title").takeIf { it.isNotEmpty() },
            view = view,
        ),
    )
}

/**
 * 视图蓝图里的一个可交互节点：`options` / `confirm` / `submit` / `form`。
 *
 * 作答时 [id] 作为 `view-reply` 的 `action`。
 */
data class ChatViewAction(
    val id: String,
    val kind: String,
    val label: String?,
    val options: List<String>,
    val multi: Boolean,
)

/** 蓝图里的所有可交互节点（按出现顺序），界面用它画按钮。 */
fun chatViewActions(view: JSONObject): List<ChatViewAction> {
    val out = mutableListOf<ChatViewAction>()
    fun walk(node: Any?) {
        when (node) {
            is JSONArray -> node.forEachElement(::walk)
            is JSONObject -> {
                when (node.optString("type")) {
                    "stack", "row" -> walk(node.opt("children"))
                    "options", "confirm", "submit", "form" -> {
                        val id = node.optString("id")
                        if (id.isNotEmpty()) {
                            out += ChatViewAction(
                                id = id,
                                kind = node.optString("type"),
                                label = node.optString("label").takeIf { it.isNotEmpty() }
                                    ?: node.optString("submitLabel").takeIf { it.isNotEmpty() }
                                    ?: node.optString("confirmLabel").takeIf { it.isNotEmpty() },
                                options = node.optJSONArray("options")?.let { array ->
                                    (0 until array.length()).mapNotNull { index ->
                                        val option = array.opt(index)
                                        when (option) {
                                            is String -> option
                                            is JSONObject -> option.optString("label").ifEmpty {
                                                option.optString("value")
                                            }
                                            else -> null
                                        }?.takeIf { it.isNotEmpty() }
                                    }
                                }.orEmpty(),
                                multi = node.optBoolean("multi", false),
                            )
                        }
                    }
                }
            }
        }
    }
    walk(view)
    return out
}
