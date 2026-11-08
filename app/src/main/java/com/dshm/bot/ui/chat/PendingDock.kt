package com.dshm.bot.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
 * 顺序照 DS Bot 插槽的 order：提问（-100）→ 视图（-95）→ 密钥（-90）；
 * 审批在这里一并展示（DS Bot 网页端把审批放在群成员条里，我们没有那一条，
 * 就统一收进这道 dock）。
 */
@Composable
fun PendingDock(
    botName: String,
    approvals: List<ChatApproval>,
    questions: List<ChatQuestionCard>,
    views: List<ChatViewCard>,
    secrets: List<ChatSecretRequest>,
    busy: Boolean,
    onAnswerApproval: (ChatApproval, Boolean) -> Unit,
    onAnswerQuestionApproval: (ChatApproval, List<String>) -> Unit,
    onAnswerQuestion: (ChatQuestionCard, List<String>, String) -> Unit,
    onAnswerView: (ChatViewCard, String, JSONObject) -> Unit,
    onDismissView: (ChatViewCard) -> Unit,
    onSecretSubmit: (ChatSecretRequest, String, Boolean) -> Unit,
    onSecretAllow: (ChatSecretRequest) -> Unit,
    onSecretCancel: (ChatSecretRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (approvals.isEmpty() && questions.isEmpty() && views.isEmpty() && secrets.isEmpty()) return
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
        secrets.forEach { request ->
            if (request.pending) {
                SecretDockCard(
                    botName = botName,
                    request = request,
                    busy = busy,
                    onSubmit = { value, onlyThisBot -> onSecretSubmit(request, value, onlyThisBot) },
                    onAllow = { onSecretAllow(request) },
                    onCancel = { onSecretCancel(request) },
                )
            } else {
                SettledSecretLine(botName = botName, request = request)
            }
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
    leading: (@Composable () -> Unit)? = null,
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
        if (title != null || leading != null || trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                leading?.invoke()
                if (leading != null) Spacer(Modifier.size(Dshm.spacing.sm))
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

/** dock 卡脚：右下角一排按钮。 */
@Composable
private fun DockFoot(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.xs, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        content()
    }
}

// ---------------------------------------------------------------------------
// 提问卡（DS Bot `ask_user`）

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
            DockChoiceRow(
                label = option.label,
                description = option.description,
                picked = picked,
                enabled = !busy,
                onClick = {
                    selected = when {
                        card.multiSelect && picked -> selected - option.label
                        card.multiSelect -> selected + option.label
                        picked -> emptyList()
                        else -> listOf(option.label)
                    }
                },
            )
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
        DockFoot {
            TextButton(
                enabled = !busy && (selected.isNotEmpty() || custom.isNotBlank()),
                onClick = { onAnswer(selected, custom) },
            ) {
                Text(stringResource(R.string.pending_answer))
            }
        }
    }
}

/** 一个可点的选项行（提问的选项、视图的 options）。 */
@Composable
private fun DockChoiceRow(
    label: String,
    description: String?,
    picked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dshm.spacing.xxs)
            .clip(RoundedCornerShape(Dshm.radius.sm))
            .background(if (picked) Dshm.colors.accent.copy(alpha = 0.12f) else Dshm.colors.surfaceMuted)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
    ) {
        Text(
            text = label,
            style = Dshm.typography.settingsRowTitle,
            color = if (picked) Dshm.colors.accent else Dshm.colors.textPrimary,
        )
        description?.let { text ->
            Text(
                text = text,
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 密钥卡（DS Bot `request_secret` / secrets.js）

@Composable
private fun SecretDockCard(
    botName: String,
    request: ChatSecretRequest,
    busy: Boolean,
    onSubmit: (String, Boolean) -> Unit,
    onAllow: () -> Unit,
    onCancel: () -> Unit,
) {
    var value by remember(request.id) { mutableStateOf("") }
    var onlyThisBot by remember(request.id) { mutableStateOf(false) }
    var masked by remember(request.id) { mutableStateOf(true) }
    var replacing by remember(request.id) { mutableStateOf(false) }
    val filling = request.kind != "allow" || replacing
    val longEnough = value.trim().length >= SECRET_MIN_VALUE

    DockCard(
        title = if (request.kind == "allow" && !replacing) {
            stringResource(R.string.pending_secret_allow_title, botName, request.name)
        } else {
            stringResource(R.string.pending_secret_fill_title, request.name)
        },
        trailing = {
            TextButton(enabled = !busy, onClick = onCancel) {
                Text(stringResource(R.string.pending_secret_cancel))
            }
        },
    ) {
        request.purpose?.let { purpose ->
            Text(
                text = purpose,
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
            )
            Spacer(Modifier.size(Dshm.spacing.sm))
        }
        if (filling) {
            DockTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = stringResource(R.string.pending_secret_value_hint, request.name),
                enabled = !busy,
                maskable = true,
                masked = masked,
                onToggleMask = { masked = !masked },
                keyboardType = KeyboardType.Password,
            )
            if (value.isNotEmpty() && !longEnough) {
                Spacer(Modifier.size(Dshm.spacing.xxs))
                Text(
                    text = stringResource(R.string.pending_secret_min, SECRET_MIN_VALUE),
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.warning,
                )
            }
            if (request.kind == "fill") {
                Spacer(Modifier.size(Dshm.spacing.xs))
                DockChoiceRow(
                    label = stringResource(R.string.pending_secret_scope_all),
                    description = null,
                    picked = !onlyThisBot,
                    enabled = !busy,
                    onClick = { onlyThisBot = false },
                )
                DockChoiceRow(
                    label = stringResource(R.string.pending_secret_scope_bot, botName),
                    description = null,
                    picked = onlyThisBot,
                    enabled = !busy,
                    onClick = { onlyThisBot = true },
                )
            }
        } else {
            request.savedScope?.let { scope ->
                Text(
                    text = stringResource(R.string.pending_secret_saved_for, scopeLabel(scope)),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
            }
        }
        Spacer(Modifier.size(Dshm.spacing.sm))
        Text(
            text = stringResource(R.string.pending_secret_note, botName, "DSH_SECRET_${request.name}"),
            style = Dshm.typography.rowTime,
            color = Dshm.colors.textTertiary,
        )
        Spacer(Modifier.size(Dshm.spacing.sm))
        DockFoot {
            if (request.kind == "allow" && !replacing) {
                TextButton(enabled = !busy, onClick = { replacing = true }) {
                    Text(stringResource(R.string.pending_secret_replace))
                }
                TextButton(enabled = !busy, onClick = onAllow) {
                    Text(
                        stringResource(
                            if (busy) R.string.pending_secret_allowing else R.string.pending_secret_allow,
                        ),
                    )
                }
            } else {
                TextButton(enabled = !busy && longEnough, onClick = { onSubmit(value, onlyThisBot) }) {
                    Text(
                        stringResource(
                            if (busy) R.string.pending_secret_saving else R.string.pending_secret_save,
                        ),
                    )
                }
            }
        }
    }
}

/** 已经答过的密钥卡：一行结果（DS Bot `SettledSecret`）。 */
@Composable
private fun SettledSecretLine(botName: String, request: ChatSecretRequest) {
    val text = when {
        request.canceled -> stringResource(R.string.pending_secret_done_canceled, botName, request.name)
        request.status == "allowed" ->
            stringResource(R.string.pending_secret_done_allowed, botName, request.name)

        else -> stringResource(R.string.pending_secret_done_saved, request.name)
    }
    val scope = if (request.canceled) null else request.savedScope?.let { scopeLabel(it) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.xs),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dshm.spacing.xs),
    ) {
        Text(
            text = if (scope == null) text else "$text · $scope",
            style = Dshm.typography.rowTime,
            color = Dshm.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(
                if (request.canceled) R.string.pending_secret_canceled else R.string.pending_secret_done,
            ),
            style = Dshm.typography.rowTime,
            color = if (request.canceled) Dshm.colors.textTertiary else Dshm.colors.success,
        )
    }
}

/** DS Bot `MIN_VALUE`：密钥至少 4 个字符。 */
private const val SECRET_MIN_VALUE = 4

/** `scope` 显示成「所有 Bot」或一串名字（DS Bot `scopeLabel`）。 */
@Composable
private fun scopeLabel(scope: Any?): String = when (scope) {
    null -> ""
    is String -> if (scope == "all") stringResource(R.string.pending_secret_scope_all) else scope
    is JSONArray -> (0 until scope.length()).map { scope.optString(it) }.filter { it.isNotEmpty() }
        .joinToString(", ")

    else -> scope.toString()
}

// ---------------------------------------------------------------------------
// 视图卡（DS Bot `show_view`）

/** 视图卡的作答：一次提交里带上的当前状态。 */
private class ViewReply(
    val busy: Boolean,
    val picked: Map<String, List<String>>,
    val fields: Map<String, Map<String, String>>,
    val onInput: (String, Any?) -> Unit,
    val onPick: (String, List<String>) -> Unit,
    val onField: (String, String, String) -> Unit,
    val onSubmit: (String, JSONObject) -> Unit,
)

@Composable
private fun ViewDockCard(
    card: ChatViewCard,
    busy: Boolean,
    onSubmit: (String, JSONObject) -> Unit,
    onDismiss: () -> Unit,
) {
    var inputs by remember(card.id) { mutableStateOf(viewDefaultInputs(card.view)) }
    var picked by remember(card.id) { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var fields by remember(card.id) { mutableStateOf<Map<String, Map<String, String>>>(emptyMap()) }
    val scope = viewScope(card.view, inputs)
    val reply = ViewReply(
        busy = busy,
        picked = picked,
        fields = fields,
        onInput = { id, value -> inputs = inputs + (id to value) },
        onPick = { id, value -> picked = picked + (id to value) },
        onField = { formId, fieldId, value ->
            fields = fields + (formId to (fields[formId].orEmpty() + (fieldId to value)))
        },
        onSubmit = onSubmit,
    )
    DockCard(
        title = card.title ?: stringResource(R.string.pending_view),
        trailing = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.pending_dismiss))
            }
        },
    ) {
        ViewNode(card.view, scope, inputs, reply)
    }
}

/** 蓝图里的一个节点：容器递归，叶子按 `type` 画。 */
@Composable
private fun ViewNode(
    node: JSONObject,
    scope: ChatViewScope,
    inputs: Map<String, Any?>,
    reply: ViewReply,
) {
    when (node.optString("type")) {
        "stack" -> Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm)) {
            node.children().forEach { ViewNode(it, scope, inputs, reply) }
        }

        "row" -> Row(
            horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            node.children().forEach { child ->
                Box(Modifier.weight(1f)) { ViewNode(child, scope, inputs, reply) }
            }
        }

        "text" -> Text(
            text = node.textProp("text", scope),
            style = Dshm.typography.rowPreview,
            color = if (node.optString("tone") == "muted") Dshm.colors.textSecondary else Dshm.colors.textPrimary,
        )

        "metric" -> ViewMetric(node, scope)
        "progress" -> ViewProgress(node, scope)
        "callout" -> Text(
            text = node.textProp("text", scope),
            style = Dshm.typography.rowPreview,
            color = toneColor(node.optString("tone")),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dshm.radius.sm))
                .background(toneColor(node.optString("tone")).copy(alpha = 0.08f))
                .padding(Dshm.spacing.sm),
        )

        "table" -> ViewTable(node, scope)
        "sources" -> ViewSources(node)
        "chart" -> ViewChart(node, scope)
        "html" -> ViewHtml(node)
        "choice", "toggle", "number", "slider" -> ViewInput(node, inputs, reply)
        "options" -> ViewOptions(node, inputs, reply)
        "confirm" -> ViewConfirm(node, inputs, reply)
        "form" -> ViewForm(node, inputs, reply)
        "submit" -> DockFoot {
            TextButton(enabled = !reply.busy, onClick = {
                reply.onSubmit(node.optString("id"), viewValues(inputs))
            }) {
                Text(node.optString("label").ifEmpty { stringResource(R.string.pending_send) })
            }
        }
    }
}

private fun JSONObject.children(): List<JSONObject> {
    val array = optJSONArray("children") ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}

@Composable
private fun ViewMetric(node: JSONObject, scope: ChatViewScope) {
    Column {
        Text(
            text = node.optString("label"),
            style = Dshm.typography.rowTime,
            color = Dshm.colors.textSecondary,
        )
        Text(
            text = node.textProp("value", scope),
            style = Dshm.typography.pageTitle,
            color = toneColor(node.optString("tone")),
        )
        val caption = node.textProp("caption", scope)
        if (caption.isNotEmpty()) {
            Text(caption, style = Dshm.typography.rowTime, color = Dshm.colors.textTertiary)
        }
    }
}

@Composable
private fun ViewProgress(node: JSONObject, scope: ChatViewScope) {
    val percent = if (node.has("percent")) {
        node.numberProp("percent", scope)
    } else {
        val max = node.numberProp("max", scope)
        if (max > 0) node.numberProp("value", scope) / max * 100 else 0.0
    }.coerceIn(0.0, 100.0)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = node.optString("label"),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = node.textProp("valueLabel", scope).ifEmpty { "${percent.toInt()}%" },
                style = Dshm.typography.rowTime,
                color = Dshm.colors.textSecondary,
            )
        }
        Spacer(Modifier.size(Dshm.spacing.xxs))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dshm.spacing.sm)
                .clip(RoundedCornerShape(Dshm.radius.pill))
                .background(Dshm.colors.surfaceMuted),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((percent / 100.0).toFloat())
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Dshm.radius.pill))
                    .background(toneColor(node.optString("tone")).copy(alpha = 0.8f)),
            )
        }
    }
}

@Composable
private fun ViewTable(node: JSONObject, scope: ChatViewScope) {
    val columns = node.optJSONArray("columns")?.let { array ->
        (0 until array.length()).map { index ->
            val column = array.optJSONObject(index)
            column?.optString("label")?.ifEmpty { column.optString("key") }.orEmpty()
        }
    }.orEmpty()
    val rows = node.optJSONArray("rows") ?: return
    val shape = RoundedCornerShape(Dshm.radius.sm)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(Dshm.spacing.hairline, Dshm.colors.divider, shape),
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
                        text = viewText(scope.resolve(row.opt(column), "")),
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
private fun ViewSources(node: JSONObject) {
    val items = node.optJSONArray("items") ?: return
    Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xxs)) {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val label = item.optString("label").ifEmpty { item.optString("url") }
            if (label.isEmpty()) continue
            Text(
                text = "· $label",
                style = Dshm.typography.rowTime,
                color = Dshm.colors.textSecondary,
            )
        }
    }
}

/**
 * `html` 组件：模型自己画的图（常见是纯 CSS 的 chart）在这里真的渲染出来。
 *
 * 只有拿不到标记时才退回它的一行 `summary`。
 */
@Composable
private fun ViewHtml(node: JSONObject) {
    val html = node.optString("html").trim()
    if (html.isNotEmpty()) {
        ChatHtmlFrame(html = html, heightDp = chatHtmlHeight(node))
        return
    }
    val summary = node.optString("summary")
    if (summary.isNotEmpty()) {
        Text(
            text = summary,
            style = Dshm.typography.rowPreview,
            color = Dshm.colors.textSecondary,
        )
    }
}

@Composable
private fun toneColor(tone: String): Color = when (tone) {
    "good" -> Dshm.colors.success
    "warn" -> Dshm.colors.warning
    "bad" -> Dshm.colors.danger
    else -> Dshm.colors.textPrimary
}

// ---------------------------------------------------------------------------
// 视图的输入控件 / 作答控件

@Composable
private fun ViewInput(node: JSONObject, inputs: Map<String, Any?>, reply: ViewReply) {
    val id = node.optString("id")
    val kind = node.optString("type")
    val label = node.optString("label").ifEmpty { id }
    Column {
        Text(
            text = label,
            style = Dshm.typography.rowTime,
            color = Dshm.colors.textSecondary,
        )
        Spacer(Modifier.size(Dshm.spacing.xxs))
        when (kind) {
            "toggle" -> {
                val on = inputs[id] == true
                Text(
                    text = stringResource(if (on) R.string.pending_on else R.string.pending_off),
                    style = Dshm.typography.settingsRowTitle,
                    color = if (on) Dshm.colors.accent else Dshm.colors.textPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Dshm.radius.sm))
                        .background(if (on) Dshm.colors.accent.copy(alpha = 0.12f) else Dshm.colors.surfaceMuted)
                        .clickable(enabled = !reply.busy) { reply.onInput(id, !on) }
                        .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
                )
            }

            "choice" -> node.optJSONArray("options")?.let { array ->
                for (index in 0 until array.length()) {
                    val option = array.optJSONObject(index) ?: continue
                    val optionValue = choiceValueOf(option)
                    DockChoiceRow(
                        label = option.optString("label"),
                        description = null,
                        picked = inputs[id] == optionValue,
                        enabled = !reply.busy,
                        onClick = { reply.onInput(id, optionValue) },
                    )
                }
            }

            else -> {
                val unit = node.optString("unit").takeIf { it.isNotEmpty() }
                DockTextField(
                    value = formatNumber((inputs[id] as? Number)?.toDouble() ?: 0.0),
                    onValueChange = { text -> text.trim().toDoubleOrNull()?.let { reply.onInput(id, it) } },
                    placeholder = if (unit != null) "0 $unit" else "0",
                    enabled = !reply.busy,
                    keyboardType = KeyboardType.Number,
                )
            }
        }
    }
}

@Composable
private fun ViewOptions(node: JSONObject, inputs: Map<String, Any?>, reply: ViewReply) {
    val id = node.optString("id")
    val multi = node.optBoolean("multi", false)
    val options = node.optJSONArray("options") ?: return
    val picked = reply.picked[id].orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xxs)) {
        for (index in 0 until options.length()) {
            val option = options.optJSONObject(index) ?: continue
            val label = option.optString("label")
            if (label.isEmpty()) continue
            DockChoiceRow(
                label = label,
                description = option.optString("description").takeIf { it.isNotEmpty() },
                picked = multi && label in picked,
                enabled = !reply.busy,
                onClick = {
                    // 单选：点一下就作答（DS Bot `OptionsInput`）；多选：先勾再提交。
                    if (!multi) {
                        reply.onSubmit(id, viewValues(inputs, selected = listOf(label)))
                    } else {
                        reply.onPick(
                            id,
                            if (label in picked) picked - label else picked + label,
                        )
                    }
                },
            )
        }
        if (multi) {
            DockFoot {
                TextButton(enabled = !reply.busy && picked.isNotEmpty(), onClick = {
                    reply.onSubmit(id, viewValues(inputs, selected = picked))
                }) {
                    Text(stringResource(R.string.pending_submit))
                }
            }
        }
    }
}

@Composable
private fun ViewConfirm(node: JSONObject, inputs: Map<String, Any?>, reply: ViewReply) {
    val id = node.optString("id")
    DockFoot {
        TextButton(enabled = !reply.busy, onClick = {
            reply.onSubmit(id, viewValues(inputs, confirmed = false))
        }) {
            Text(node.optString("cancelLabel").ifEmpty { stringResource(R.string.pending_cancel) })
        }
        TextButton(enabled = !reply.busy, onClick = {
            reply.onSubmit(id, viewValues(inputs, confirmed = true))
        }) {
            Text(node.optString("confirmLabel").ifEmpty { stringResource(R.string.pending_confirm) })
        }
    }
}

@Composable
private fun ViewForm(node: JSONObject, inputs: Map<String, Any?>, reply: ViewReply) {
    val id = node.optString("id")
    val fields = node.optJSONArray("fields") ?: JSONArray()
    val draft = reply.fields[id].orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm)) {
        for (index in 0 until fields.length()) {
            val field = fields.optJSONObject(index) ?: continue
            val fieldId = field.optString("id")
            if (fieldId.isEmpty()) continue
            Column {
                Text(
                    text = field.optString("label").ifEmpty { fieldId },
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.textSecondary,
                )
                Spacer(Modifier.size(Dshm.spacing.xxs))
                DockTextField(
                    value = draft[fieldId].orEmpty(),
                    onValueChange = { value -> reply.onField(id, fieldId, value) },
                    placeholder = field.optString("placeholder"),
                    enabled = !reply.busy,
                )
            }
        }
        DockFoot {
            TextButton(enabled = !reply.busy, onClick = {
                val filled = fields.let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        val field = array.optJSONObject(index) ?: return@mapNotNull null
                        val fieldId = field.optString("id")
                        if (fieldId.isEmpty()) null else fieldId to draft[fieldId].orEmpty()
                    }
                }.toMap()
                reply.onSubmit(id, viewValues(inputs, fields = filled))
            }) {
                Text(node.optString("submitLabel").ifEmpty { stringResource(R.string.pending_submit) })
            }
        }
    }
}

/** `view-reply` 的 `values`：`{inputs?, selected?, confirmed?, fields?}`。 */
private fun viewValues(
    inputs: Map<String, Any?>,
    selected: List<String> = emptyList(),
    confirmed: Boolean? = null,
    fields: Map<String, String>? = null,
): JSONObject {
    val values = JSONObject()
    if (inputs.isNotEmpty()) {
        val json = JSONObject()
        inputs.forEach { (key, value) -> if (value != null) json.put(key, value) }
        if (json.length() > 0) values.put("inputs", json)
    }
    if (selected.isNotEmpty()) values.put("selected", JSONArray().apply { selected.forEach(::put) })
    if (confirmed != null) values.put("confirmed", confirmed)
    if (fields != null) {
        val json = JSONObject()
        fields.forEach { (key, value) -> json.put(key, value) }
        values.put("fields", json)
    }
    return values
}

private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

// ---------------------------------------------------------------------------
// 图表（DS Bot `chart` 组件：bar / hbar / line / area / donut）

private val CHART_HEIGHT = 132.dp
private val DONUT_SIZE = 104.dp

@Composable
private fun seriesColor(index: Int): Color = when (index % 5) {
    0 -> Dshm.colors.accent
    1 -> Dshm.colors.success
    2 -> Dshm.colors.warning
    3 -> Dshm.colors.danger
    else -> Dshm.colors.textSecondary
}

@Composable
private fun ViewChart(node: JSONObject, scope: ChatViewScope) {
    val chart = chatViewChart(node, scope)
    if (chart.categories.isEmpty() || chart.series.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xs)) {
        when (chart.kind) {
            "hbar" -> HBarChart(chart)
            "donut" -> DonutChart(chart)
            "line", "area" -> LineChart(chart, filled = chart.kind == "area")
            else -> BarChart(chart)
        }
        val caption = chart.caption
            ?: if (chart.kind == "donut") null else stringResource(R.string.pending_chart)
        caption?.let {
            Text(it, style = Dshm.typography.rowTime, color = Dshm.colors.textTertiary)
        }
        ChartLegend(chart)
    }
}

@Composable
private fun BarChart(chart: ChatViewChart) {
    val max = chart.max.takeIf { it > 0 } ?: 1.0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.xxs),
    ) {
        chart.categories.forEachIndexed { index, label ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (chart.series.size == 1) {
                    Text(
                        text = chartValue(chart.series[0].values.getOrNull(index) ?: 0.0, chart.unit),
                        style = Dshm.typography.rowTime,
                        color = Dshm.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    chart.series.forEachIndexed { seriesIndex, series ->
                        val value = series.values.getOrNull(index) ?: 0.0
                        Box(
                            modifier = Modifier
                                .padding(horizontal = Dshm.spacing.xxs)
                                .width(seriesWidth(chart.series.size))
                                .fillMaxHeight((value / max).coerceIn(0.02, 1.0).toFloat())
                                .clip(RoundedCornerShape(Dshm.radius.xs))
                                .background(seriesColor(seriesIndex)),
                        )
                    }
                }
                Spacer(Modifier.size(Dshm.spacing.xxs))
                Text(
                    text = label,
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.textTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 一组柱子的宽度：柱子多的时候细一点。 */
private fun seriesWidth(count: Int) = if (count <= 1) 14.dp else (24 / count).coerceAtLeast(6).dp

@Composable
private fun HBarChart(chart: ChatViewChart) {
    val max = chart.max.takeIf { it > 0 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xs)) {
        chart.categories.forEachIndexed { index, label ->
            Column {
                Text(
                    text = label,
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                chart.series.forEachIndexed { seriesIndex, series ->
                    val value = series.values.getOrNull(index) ?: 0.0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(Dshm.spacing.md)
                                .clip(RoundedCornerShape(Dshm.radius.xs))
                                .background(Dshm.colors.surfaceMuted),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth((value / max).coerceIn(0.0, 1.0).toFloat())
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(Dshm.radius.xs))
                                    .background(seriesColor(seriesIndex)),
                            )
                        }
                        Spacer(Modifier.size(Dshm.spacing.xs))
                        Text(
                            text = chartValue(value, chart.unit),
                            style = Dshm.typography.rowTime,
                            color = Dshm.colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LineChart(chart: ChatViewChart, filled: Boolean) {
    val colors = List(chart.series.size) { seriesColor(it) }
    val max = chart.max.takeIf { it > 0 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xxs)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT),
        ) {
            val count = chart.categories.size
            val stepX = if (count <= 1) 0f else size.width / (count - 1)
            val stroke = 2.dp.toPx()
            val inset = stroke
            chart.series.forEachIndexed { index, series ->
                val points = series.values.mapIndexed { at, value ->
                    Offset(
                        x = if (count <= 1) size.width / 2f else inset + stepX * at,
                        y = size.height - inset - (value / max).coerceIn(0.0, 1.0).toFloat() * (size.height - inset * 2),
                    )
                }
                if (points.isEmpty()) return@forEachIndexed
                val path = Path().apply {
                    points.forEachIndexed { at, point ->
                        if (at == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                    }
                }
                if (filled) {
                    val area = Path().apply {
                        addPath(path)
                        lineTo(points.last().x, size.height)
                        lineTo(points.first().x, size.height)
                        close()
                    }
                    drawPath(area, colors[index].copy(alpha = 0.16f))
                }
                drawPath(
                    path = path,
                    color = colors[index],
                    style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                points.forEach { drawCircle(colors[index], radius = 2.5.dp.toPx(), center = it) }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            chart.categories.forEach { label ->
                Text(
                    text = label,
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DonutChart(chart: ChatViewChart) {
    val values = chart.series.firstOrNull()?.values.orEmpty()
    val total = values.sum().takeIf { it > 0 } ?: 1.0
    val colors = List(values.size) { seriesColor(it) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(DONUT_SIZE)) {
            val stroke = 18.dp.toPx()
            var start = -90f
            values.forEachIndexed { index, value ->
                val sweep = (value / total * 360.0).toFloat()
                drawArc(
                    color = colors[index],
                    startAngle = start,
                    sweepAngle = (sweep - 1.6f).coerceAtLeast(0.6f),
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                )
                start += sweep
            }
        }
        Spacer(Modifier.size(Dshm.spacing.md))
        Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xxs)) {
            chart.categories.forEachIndexed { index, label ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(Dshm.spacing.sm)
                            .clip(RoundedCornerShape(Dshm.radius.xs))
                            .background(seriesColor(index)),
                    )
                    Spacer(Modifier.size(Dshm.spacing.xs))
                    Text(
                        text = label,
                        style = Dshm.typography.rowTime,
                        color = Dshm.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = chartValue(values.getOrNull(index) ?: 0.0, chart.unit),
                        style = Dshm.typography.rowTime,
                        color = Dshm.colors.textPrimary,
                    )
                }
            }
        }
    }
}

/** 多条曲线 / 甜甜圈时的图例。 */
@Composable
private fun ChartLegend(chart: ChatViewChart) {
    if (chart.kind == "donut" || chart.series.size <= 1) return
    Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xxs)) {
        chart.series.forEachIndexed { index, series ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(Dshm.spacing.sm)
                        .clip(RoundedCornerShape(Dshm.radius.xs))
                        .background(seriesColor(index)),
                )
                Spacer(Modifier.size(Dshm.spacing.xs))
                Text(
                    text = series.name ?: stringResource(R.string.pending_chart_series, index + 1),
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.textSecondary,
                )
            }
        }
    }
}

private fun chartValue(value: Double, unit: String?): String {
    val text = formatNumber(value)
    return if (unit.isNullOrEmpty()) text else "$text $unit"
}

// ---------------------------------------------------------------------------
// dock 里的单行输入（提问的自定义回答、视图的文本/数字控件、密钥）

@Composable
private fun DockTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    maskable: Boolean = false,
    masked: Boolean = false,
    onToggleMask: (() -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dshm.radius.sm))
            .background(Dshm.colors.surfaceMuted)
            .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = Dshm.typography.settingsRowTitle.copy(color = Dshm.colors.textPrimary),
            cursorBrush = SolidColor(Dshm.colors.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = Dshm.typography.settingsRowTitle,
                            color = Dshm.colors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
        if (maskable) {
            Spacer(Modifier.size(Dshm.spacing.xs))
            Text(
                text = stringResource(
                    if (masked) R.string.pending_secret_show else R.string.pending_secret_hide,
                ),
                style = Dshm.typography.rowTime,
                color = Dshm.colors.accent,
                modifier = Modifier.clickable(enabled = enabled) { onToggleMask?.invoke() },
            )
        }
    }
}
