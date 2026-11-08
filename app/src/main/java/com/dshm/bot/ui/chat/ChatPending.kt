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
 * 三个来源都在 DS Bot 的 `state` 里（`channel.js view()`）：
 * - `state.questions[botId]`：`ask_user` 提的问题卡（`answer-question` 作答）；
 * - `state.views[botId]`：`show_view` 等答复的视图卡（`view-reply` 作答）；
 * - `state.secretRequests[botId]`：`request_secret` 等用户填的密钥卡（`secret-set` 等）。
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

/**
 * `request_secret` 留的一张卡（DS Bot `src/host/secrets.js` 的 `state.secretRequests[botId]`）。
 *
 * - `kind == "fill"`：这把钥匙还没存过，用户要填值；`"allow"`：值已经存过，只是问能不能给这个 Bot 用。
 * - [savedScope] 只在 settled 后有意义（引擎回填 `"all"` 或 Bot id 数组）。
 */
data class ChatSecretRequest(
    val id: String,
    val name: String,
    val purpose: String?,
    val kind: String,
    val status: String,
    val callId: String?,
    val askedAt: Long,
    val settledAt: Long,
    val savedScope: Any?,
) {
    val pending: Boolean get() = status == "pending"
    val canceled: Boolean get() = status == "canceled"
}

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

/** 取 `state.secretRequests[botId]`；没问过（或不是真 Bot）时为空。 */
fun parseChatSecrets(state: JSONObject, botId: String): List<ChatSecretRequest> {
    val array = state.optJSONObject("secretRequests")?.optJSONArray(botId) ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val name = item.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        ChatSecretRequest(
            id = id,
            name = name,
            purpose = item.optString("purpose").trim().takeIf { it.isNotEmpty() },
            kind = item.optString("kind").ifEmpty { "fill" },
            status = item.optString("status").ifEmpty { "pending" },
            callId = item.optString("callId").takeIf { it.isNotEmpty() },
            askedAt = item.optLong("askedAt", 0L),
            settledAt = item.optLong("settledAt", 0L),
            savedScope = if (item.has("scope")) item.opt("scope") else null,
        )
    }
}

// ---------------------------------------------------------------------------
// 蓝图遍历

private val LAYOUT_TYPES = setOf("stack", "row")
private val INPUT_TYPES = setOf("slider", "choice", "toggle", "number")
private val ANSWER_TYPES = setOf("options", "confirm", "form", "submit")

/** 按出现顺序走一遍蓝图里的每个节点（`stack` / `row` 递归进 children）。 */
internal fun walkView(view: JSONObject, visit: (JSONObject) -> Unit) {
    fun step(node: Any?) {
        when (node) {
            is JSONArray -> node.forEachElement(::step)
            is JSONObject -> {
                visit(node)
                if (node.optString("type") in LAYOUT_TYPES) step(node.opt("children"))
            }
        }
    }
    step(view)
}

/** 蓝图里的输入控件（`choice` / `toggle` / `number` / `slider`），按出现顺序。 */
fun viewInputNodes(view: JSONObject): List<JSONObject> =
    buildList { walkView(view) { if (it.optString("type") in INPUT_TYPES) add(it) } }

/** 蓝图里的作答控件（`options` / `confirm` / `form` / `submit`），按出现顺序。 */
fun viewAnswerNodes(view: JSONObject): List<JSONObject> =
    buildList { walkView(view) { if (it.optString("type") in ANSWER_TYPES) add(it) } }

/** `choice` 选项的取值（没写 `value` 时用 `label`）。 */
internal fun choiceValueOf(node: JSONObject): Any? = node.opt("value") ?: node.optString("label")

/**
 * 视图每个输入的初值（DS Bot `inputDefault`）。
 *
 * 蓝图里写的 `value`；`choice` 没写就用第一个选项的取值，`toggle` 只有 `true` 才算开。
 */
fun viewDefaultInputs(view: JSONObject): Map<String, Any?> {
    val out = mutableMapOf<String, Any?>()
    viewInputNodes(view).forEach { node ->
        val id = node.optString("id")
        if (id.isEmpty()) return@forEach
        when (node.optString("type")) {
            "toggle" -> out[id] = node.opt("value") == true
            "choice" -> out[id] = if (node.has("value")) {
                node.opt("value")
            } else {
                node.optJSONArray("options")?.optJSONObject(0)?.let(::choiceValueOf)
            }

            else -> out[id] = node.optDouble("value", 0.0)
        }
    }
    return out
}

/** 视图的取值域：输入值 + 蓝图根上的 `let` 名字。 */
internal fun viewScope(view: JSONObject, values: Map<String, Any?>): ChatViewScope =
    ChatViewScope(values, ChatViewScope.letsOf(view))

// ---------------------------------------------------------------------------
// 图表

data class ChatViewSeries(val name: String?, val values: List<Double>)

/**
 * `chart` 组件的数据（DS Bot `chartData`）：类别 + 每条曲线的数值。
 *
 * [kind] 缺省当 `bar`；数值算不出来时按 0 画（DS Bot 一样）。
 */
data class ChatViewChart(
    val kind: String,
    val categories: List<String>,
    val series: List<ChatViewSeries>,
    val unit: String?,
    val caption: String?,
) {
    /** 所有曲线共用的纵轴上限（柱图/折线图用）。 */
    val max: Double = (series.flatMap { it.values }.maxOrNull() ?: 0.0).coerceAtLeast(0.0)
}

internal fun chatViewChart(node: JSONObject, scope: ChatViewScope): ChatViewChart {
    val categories = node.optJSONArray("categories")?.let { array ->
        (0 until array.length()).map { show(array.opt(it)) }
    }.orEmpty()
    val series = node.optJSONArray("series")?.let { array ->
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val values = item.optJSONArray("values")
            ChatViewSeries(
                name = item.optString("name").takeIf { it.isNotEmpty() },
                values = categories.indices.map { at ->
                    scope.resolveNumber(values?.opt(at), 0.0).takeIf { it.isFinite() } ?: 0.0
                },
            )
        }
    }.orEmpty()
    return ChatViewChart(
        kind = node.optString("kind").ifEmpty { "bar" },
        categories = categories,
        series = series,
        unit = node.optString("unit").takeIf { it.isNotEmpty() },
        caption = node.optString("caption").takeIf { it.isNotEmpty() },
    )
}

// ---------------------------------------------------------------------------
// 蓝图里那些「可能是公式」的属性

/** 取一个属性：字面量原样返回，`{"=": …}` 算出数值/文字。 */
internal fun JSONObject.prop(key: String, scope: ChatViewScope, fallback: Any? = null): Any? =
    scope.resolve(opt(key), fallback)

/** 取一个属性的文字形式（公式算出来的数字也走 [show] 那套去噪）。 */
internal fun JSONObject.textProp(key: String, scope: ChatViewScope, fallback: String = ""): String =
    show(scope.resolve(opt(key), fallback))

/** 取一个属性的数字形式。 */
internal fun JSONObject.numberProp(key: String, scope: ChatViewScope, fallback: Double = 0.0): Double =
    scope.resolveNumber(opt(key), fallback)

/** 值当文字看：字符串原样，数字/布尔走 [show]。 */
internal fun viewText(value: Any?): String = when (value) {
    null -> ""
    is String -> value
    else -> show(value)
}
