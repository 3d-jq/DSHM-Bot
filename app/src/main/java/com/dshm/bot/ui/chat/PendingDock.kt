package com.dshm.bot.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.domain.model.ChatApproval
import org.json.JSONArray
import org.json.JSONObject

/**
 * 输入框上方的待办 dock（DS Bot `conversation.input.dock`）。
 *
 * 顺序照 DS Bot 的插槽：提问 → 视图 → 密钥；审批在这里一并展示（DS Bot 网页端把审批
 * 放在群成员条里，我们没有那一条，就统一收进这道 dock）。
 */
@Composable
fun PendingDock(
    approvals: List<ChatApproval>,
    questions: List<ChatQuestionCard>,
    views: List<ChatViewCard>,
    busy: Boolean,
    onAnswerApproval: (ChatApproval, Boolean) -> Unit,
    onAnswerQuestionApproval: (ChatApproval, List<String>) -> Unit,
    onAnswerQuestion: (ChatQuestionCard, List<String>, String) -> Unit,
    onAnswerView: (ChatViewCard, String, JSONObject) -> Unit,
    onDismissView: (ChatViewCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (approvals.isEmpty() && questions.isEmpty() && views.isEmpty()) return
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.45f
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(bottom = Dshm.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
    ) {
        questions.forEach { card ->
            QuestionDockCard(
                card = card,
                busy = busy,
                onAnswer = { selected, custom -> onAnswerQuestion(card, selected, custom) },
            )
        }
        views.forEach { card ->
            ViewDockCard(
                card = card,
                busy = busy,
                onSubmit = { action, values -> onAnswerView(card, action, values) },
                onDismiss = { onDismissView(card) },
            )
        }
        approvals.forEach { approval ->
            ApprovalCard(
                approval = approval,
                busy = busy,
                onAnswer = { allow -> onAnswerApproval(approval, allow) },
                onAnswerQuestion = { selected -> onAnswerQuestionApproval(approval, selected) },
            )
        }
    }
}

/** dock 卡的共同外壳：白底圆角 + 细描边（DS Bot `.bt-q`）。 */
@Composable
private fun DockCard(
    title: String?,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Dshm.radius.md)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Dshm.colors.surface)
            .border(Dshm.spacing.hairline, Dshm.colors.divider, shape)
            .padding(Dshm.spacing.md),
    ) {
        if (title != null || trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (title != null) {
                    Text(
                        text = title,
                        style = Dshm.typography.settingsRowTitle,
                        color = Dshm.colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                trailing?.invoke()
            }
            Spacer(Modifier.size(Dshm.spacing.sm))
        }
        content()
    }
}

/** `ask_user` 的提问卡：选项 + 可选自定义回答。 */
@Composable
private fun QuestionDockCard(
    card: ChatQuestionCard,
    busy: Boolean,
    onAnswer: (List<String>, String) -> Unit,
) {
    var selected by remember(card.id) { mutableStateOf(emptyList<String>()) }
    var custom by remember(card.id) { mutableStateOf("") }
    DockCard(title = card.question) {
        card.detail?.let { detail ->
            Text(
                text = detail,
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
            )
            Spacer(Modifier.size(Dshm.spacing.sm))
        }
        card.options.forEach { option ->
            val picked = option.label in selected
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dshm.spacing.xxs)
                    .clip(RoundedCornerShape(Dshm.radius.sm))
                    .background(if (picked) Dshm.colors.accent.copy(alpha = 0.12f) else Dshm.colors.surfaceMuted)
                    .clickable(enabled = !busy) {
                        selected = when {
                            card.multiSelect && picked -> selected - option.label
                            card.multiSelect -> selected + option.label
                            picked -> emptyList()
                            else -> listOf(option.label)
                        }
                    }
                    .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
            ) {
                Text(
                    text = option.label,
                    style = Dshm.typography.settingsRowTitle,
                    color = Dshm.colors.textPrimary,
                )
                option.description?.let { description ->
                    Text(
                        text = description,
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (card.allowCustom) {
            Spacer(Modifier.size(Dshm.spacing.xs))
            DockTextField(
                value = custom,
                onValueChange = { custom = it },
                placeholder = stringResource(R.string.pending_custom_hint),
                enabled = !busy,
            )
        }
        Spacer(Modifier.size(Dshm.spacing.sm))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(
                enabled = !busy && (selected.isNotEmpty() || custom.isNotBlank()),
                onClick = { onAnswer(selected, custom) },
            ) {
                Text(stringResource(R.string.pending_answer))
            }
        }
    }
}

/** `show_view` 的视图卡：蓝图只读部分 + 可提交的控件。 */
@Composable
private fun ViewDockCard(
    card: ChatViewCard,
    busy: Boolean,
    onSubmit: (String, JSONObject) -> Unit,
    onDismiss: () -> Unit,
) {
    val actions = remember(card.id) { chatViewActions(card.view) }
    var inputs by remember(card.id) { mutableStateOf(chatViewInputs(card.view)) }
    var picked by remember(card.id) { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    DockCard(
        title = card.title ?: stringResource(R.string.pending_view),
        trailing = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.pending_dismiss))
            }
        },
    ) {
        ViewBlueprint(view = card.view)
        // 输入型控件（choice / toggle / number / slider）
        chatViewInputFields(card.view).forEach { field ->
            Spacer(Modifier.size(Dshm.spacing.sm))
            ViewInputField(
                field = field,
                value = inputs[field.id],
                enabled = !busy,
                onValue = { value -> inputs = inputs + (field.id to value) },
            )
        }
        actions.forEach { action ->
            Spacer(Modifier.size(Dshm.spacing.sm))
            if (action.options.isEmpty()) {
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(enabled = !busy, onClick = { onSubmit(action.id, viewValues(inputs, emptyList())) }) {
                        Text(action.label ?: stringResource(R.string.pending_answer))
                    }
                }
            } else {
                val chosen = picked[action.id].orEmpty()
                action.options.forEach { option ->
                    val on = option in chosen
                    Text(
                        text = option,
                        style = Dshm.typography.settingsRowTitle,
                        color = if (on) Dshm.colors.accent else Dshm.colors.textPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Dshm.spacing.xxs)
                            .clip(RoundedCornerShape(Dshm.radius.sm))
                            .background(if (on) Dshm.colors.accent.copy(alpha = 0.12f) else Dshm.colors.surfaceMuted)
                            .clickable(enabled = !busy) {
                                val next = when {
                                    action.multi && on -> chosen - option
                                    action.multi -> chosen + option
                                    else -> listOf(option)
                                }
                                picked = picked + (action.id to next)
                            }
                            .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
                    )
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        enabled = !busy && chosen.isNotEmpty(),
                        onClick = { onSubmit(action.id, viewValues(inputs, chosen)) },
                    ) {
                        Text(action.label ?: stringResource(R.string.pending_answer))
                    }
                }
            }
        }
    }
}

/** `view-reply` 的 `values`：`{inputs: {...}, selected: [...]}`。 */
private fun viewValues(inputs: Map<String, Any>, selected: List<String>): JSONObject {
    val values = JSONObject()
    if (inputs.isNotEmpty()) {
        val json = JSONObject()
        inputs.forEach { (key, value) -> json.put(key, value) }
        values.put("inputs", json)
    }
    if (selected.isNotEmpty()) values.put("selected", JSONArray().apply { selected.forEach(::put) })
    return values
}

/** 蓝图的只读部分（text / metric / progress / callout / table / sources）。 */
@Composable
private fun ViewBlueprint(view: JSONObject) {
    val nodes = remember(view) { viewDisplayNodes(view) }
    if (nodes.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm)) {
        nodes.forEach { node -> ViewDisplayNode(node) }
    }
}

@Composable
private fun ViewDisplayNode(node: JSONObject) {
    when (node.optString("type")) {
        "text" -> Text(
            text = node.optString("text"),
            style = Dshm.typography.rowPreview,
            color = if (node.optString("tone") == "muted") Dshm.colors.textSecondary else Dshm.colors.textPrimary,
        )

        "metric" -> Column {
            Text(
                text = node.optString("label"),
                style = Dshm.typography.rowTime,
                color = Dshm.colors.textSecondary,
            )
            Text(
                text = node.opt("value")?.toString().orEmpty(),
                style = Dshm.typography.pageTitle,
                color = toneColor(node.optString("tone")),
            )
            node.optString("caption").takeIf { it.isNotEmpty() }?.let { caption ->
                Text(caption, style = Dshm.typography.rowTime, color = Dshm.colors.textTertiary)
            }
        }

        "progress" -> Column {
            val percent = node.optDouble("percent", Double.NaN)
                .takeIf { !it.isNaN() }
                ?: node.optDouble("max", 0.0).takeIf { it > 0 }?.let {
                    node.optDouble("value", 0.0) / it * 100
                }
                ?: 0.0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = node.optString("label"),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = node.optString("valueLabel").ifEmpty { "${percent.toInt()}%" },
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.textSecondary,
                )
            }
            Spacer(Modifier.size(Dshm.spacing.xxs))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .size(Dshm.spacing.sm)
                    .clip(RoundedCornerShape(Dshm.radius.pill))
                    .background(Dshm.colors.surfaceMuted),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((percent / 100.0).coerceIn(0.0, 1.0).toFloat())
                        .size(Dshm.spacing.sm)
                        .clip(RoundedCornerShape(Dshm.radius.pill))
                        .background(toneColor(node.optString("tone")).copy(alpha = 0.8f)),
                )
            }
        }

        "callout" -> Text(
            text = node.optString("text"),
            style = Dshm.typography.rowPreview,
            color = toneColor(node.optString("tone")),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dshm.radius.sm))
                .background(toneColor(node.optString("tone")).copy(alpha = 0.08f))
                .padding(Dshm.spacing.sm),
        )

        "table" -> ViewTable(node)

        "sources" -> Column {
            node.optJSONArray("items")?.let { items ->
                for (index in 0 until items.length()) {
                    val item = items.opt(index)
                    val text = when (item) {
                        is String -> item
                        is JSONObject -> item.optString("label").ifEmpty { item.optString("url") }
                        else -> ""
                    }
                    if (text.isNotEmpty()) {
                        Text(
                            text = "· $text",
                            style = Dshm.typography.rowTime,
                            color = Dshm.colors.textSecondary,
                        )
                    }
                }
            }
        }

        "chart", "html" -> Text(
            text = stringResource(R.string.pending_view_unsupported, node.optString("type")),
            style = Dshm.typography.rowTime,
            color = Dshm.colors.textTertiary,
        )
    }
}

@Composable
private fun ViewTable(node: JSONObject) {
    val columns = node.optJSONArray("columns")?.let { array ->
        (0 until array.length()).map { array.opt(it)?.toString().orEmpty() }
    }.orEmpty()
    val rows = node.optJSONArray("rows") ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dshm.radius.sm))
            .border(Dshm.spacing.hairline, Dshm.colors.divider, RoundedCornerShape(Dshm.radius.sm)),
    ) {
        if (columns.isNotEmpty()) {
            Row(Modifier.background(Dshm.colors.surfaceMuted).padding(Dshm.spacing.xs)) {
                columns.forEach { column ->
                    Text(
                        text = column,
                        style = Dshm.typography.rowTime.copy(fontWeight = FontWeight.Medium),
                        color = Dshm.colors.textSecondary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        for (index in 0 until rows.length()) {
            val row = rows.optJSONArray(index) ?: continue
            Row(Modifier.padding(Dshm.spacing.xs)) {
                for (column in 0 until maxOf(columns.size, row.length())) {
                    Text(
                        text = row.opt(column)?.toString().orEmpty(),
                        style = Dshm.typography.rowTime,
                        color = Dshm.colors.textPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun toneColor(tone: String): Color = when (tone) {
    "good" -> Dshm.colors.success
    "warn" -> Dshm.colors.warning
    "bad" -> Dshm.colors.danger
    else -> Dshm.colors.textPrimary
}

/** 蓝图里的一个输入控件（`choice` / `toggle` / `number` / `slider`）。 */
data class ChatViewInput(
    val id: String,
    val kind: String,
    val label: String,
    val options: List<Pair<String, String>>,
    val min: Double?,
    val max: Double?,
    val unit: String?,
    val defaultValue: Any?,
)

/** 蓝图里的所有输入控件（按出现顺序）。 */
fun chatViewInputFields(view: JSONObject): List<ChatViewInput> {
    val out = mutableListOf<ChatViewInput>()
    fun walk(node: Any?) {
        when (node) {
            is JSONArray -> node.forEachElement(::walk)
            is JSONObject -> {
                when (node.optString("type")) {
                    "stack", "row" -> walk(node.opt("children"))
                    "choice" -> {
                        val id = node.optString("id")
                        if (id.isEmpty()) return
                        val options = node.optJSONArray("options")?.let { array ->
                            (0 until array.length()).mapNotNull { index ->
                                when (val option = array.opt(index)) {
                                    is String -> option to option
                                    is JSONObject -> option.optString("value").ifEmpty { option.optString("label") } to
                                        option.optString("label").ifEmpty { option.optString("value") }
                                    else -> null
                                }
                            }
                        }.orEmpty()
                        out += ChatViewInput(
                            id = id,
                            kind = "choice",
                            label = node.optString("label").ifEmpty { id },
                            options = options,
                            min = null,
                            max = null,
                            unit = null,
                            defaultValue = node.opt("value"),
                        )
                    }

                    "toggle" -> {
                        val id = node.optString("id")
                        if (id.isEmpty()) return
                        out += ChatViewInput(
                            id = id,
                            kind = "toggle",
                            label = node.optString("label").ifEmpty { id },
                            options = emptyList(),
                            min = null,
                            max = null,
                            unit = null,
                            defaultValue = if (node.has("value")) node.optBoolean("value") else false,
                        )
                    }

                    "number", "slider" -> {
                        val id = node.optString("id")
                        if (id.isEmpty()) return
                        out += ChatViewInput(
                            id = id,
                            kind = node.optString("type"),
                            label = node.optString("label").ifEmpty { id },
                            options = emptyList(),
                            min = node.optDouble("min", Double.NaN).takeIf { !it.isNaN() },
                            max = node.optDouble("max", Double.NaN).takeIf { !it.isNaN() },
                            unit = node.optString("unit").takeIf { it.isNotEmpty() },
                            defaultValue = if (node.has("value")) node.optDouble("value") else 0.0,
                        )
                    }
                }
            }
        }
    }
    walk(view)
    return out
}

/** 输入控件的初值：蓝图里写的 `value`（没有就用类型默认值）。 */
fun chatViewInputs(view: JSONObject): Map<String, Any> =
    chatViewInputFields(view).mapNotNull { field ->
        when {
            field.defaultValue is Boolean -> field.id to field.defaultValue
            field.defaultValue is Number -> field.id to field.defaultValue.toDouble()
            field.defaultValue is String -> field.id to field.defaultValue
            field.kind == "toggle" -> field.id to false
            field.kind == "number" || field.kind == "slider" -> field.id to 0.0
            else -> null
        }
    }.toMap()

/** 蓝图里要画的只读节点（按出现顺序）：text / metric / progress / callout / table / sources。 */
fun viewDisplayNodes(view: JSONObject): List<JSONObject> {
    val out = mutableListOf<JSONObject>()
    fun walk(node: Any?) {
        when (node) {
            is JSONArray -> node.forEachElement(::walk)
            is JSONObject -> when (node.optString("type")) {
                "stack", "row" -> walk(node.opt("children"))
                "text", "metric", "progress", "callout", "table", "sources", "chart", "html" -> out += node
            }
        }
    }
    walk(view)
    return out
}

/** 一个输入控件：choice 用选项块，toggle 用开关，number / slider 用单行数字。 */
@Composable
private fun ViewInputField(
    field: ChatViewInput,
    value: Any?,
    enabled: Boolean,
    onValue: (Any) -> Unit,
) {
    Column {
        Text(
            text = field.label,
            style = Dshm.typography.rowTime,
            color = Dshm.colors.textSecondary,
        )
        Spacer(Modifier.size(Dshm.spacing.xxs))
        when (field.kind) {
            "toggle" -> {
                val on = value as? Boolean ?: false
                Text(
                    text = if (on) stringResource(R.string.pending_on) else stringResource(R.string.pending_off),
                    style = Dshm.typography.settingsRowTitle,
                    color = if (on) Dshm.colors.accent else Dshm.colors.textPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Dshm.radius.sm))
                        .background(if (on) Dshm.colors.accent.copy(alpha = 0.12f) else Dshm.colors.surfaceMuted)
                        .clickable(enabled = enabled) { onValue(!on) }
                        .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
                )
            }

            "choice" -> field.options.forEach { (optionValue, optionLabel) ->
                val on = value == optionValue
                Text(
                    text = optionLabel,
                    style = Dshm.typography.settingsRowTitle,
                    color = if (on) Dshm.colors.accent else Dshm.colors.textPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Dshm.spacing.xxs)
                        .clip(RoundedCornerShape(Dshm.radius.sm))
                        .background(if (on) Dshm.colors.accent.copy(alpha = 0.12f) else Dshm.colors.surfaceMuted)
                        .clickable(enabled = enabled) { onValue(optionValue) }
                        .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
                )
            }

            else -> DockTextField(
                value = (value as? Number)?.let { formatNumber(it.toDouble()) } ?: "",
                onValueChange = { text -> text.trim().toDoubleOrNull()?.let(onValue) },
                placeholder = field.unit?.let { "0 $it" } ?: "0",
                enabled = enabled,
            )
        }
    }
}

private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

/** dock 里的单行输入（提问的自定义回答、视图的数字控件）。 */
@Composable
private fun DockTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = Dshm.typography.settingsRowTitle.copy(color = Dshm.colors.textPrimary),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Dshm.colors.accent),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dshm.radius.sm))
            .background(Dshm.colors.surfaceMuted)
            .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = Dshm.typography.settingsRowTitle,
                    color = Dshm.colors.textTertiary,
                )
            }
            inner()
        },
    )
}
