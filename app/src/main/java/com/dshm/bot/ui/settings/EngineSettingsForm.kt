package com.dshm.bot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.FieldKind
import com.dshm.bot.data.remote.FieldUnit
import com.dshm.bot.data.remote.SettingsNamespace
import com.dshm.bot.data.remote.SettingsSchemaNode
import com.dshm.bot.data.remote.fieldKindOf
import com.dshm.bot.data.remote.lookupPath
import com.dshm.bot.data.remote.schemaAt
import com.dshm.bot.data.remote.unitFor
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 通用 schema 设置表单：对齐 dsh-Go `engine_settings/schema_form.dart` 的行为，
 * 界面长成我们自己的设置卡样式。
 */
@Composable
fun EngineNamespaceSection(
    viewModel: EngineSettingsViewModel,
    namespace: SettingsNamespace,
    language: String,
    title: String = SettingsCopy.namespaceTitle(namespace.ns, language),
    showTitle: Boolean = true,
    footer: String? = null,
    /** true：所有分组合并成一张卡（子代理页的「运行限制」那样）。 */
    singleCard: Boolean = false,
) {
    val sections = remember(namespace.schema, language) {
        formSections(namespace.ns, namespace.schema, emptyList(), 0, language)
    }
    val allFields = remember(sections) { sections.flatMap { it.fields } }
    if (allFields.isEmpty()) {
        if (showTitle) {
            SettingsSection(title = title) {
                Text(
                    text = stringResource(R.string.settings_no_fields),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textTertiary,
                    modifier = Modifier.padding(Dshm.spacing.lg),
                )
            }
        }
        return
    }

    // dsh-Go 的字段分层：主要 → 常规 → 高级；高级够多时折进「高级」分组。
    val tiers = remember(allFields) {
        allFields.associate { field ->
            field.path to configTierOf(field.path.lastOrNull().orEmpty(), field.node)
        }
    }
    val advancedCount = tiers.values.count { it == ConfigTier.ADVANCED }
    val collapseDeclared = remember(namespace.schema) { declaresCollapse(namespace.schema) }
    val foldAdvanced = shouldFoldAdvanced(allFields.size, advancedCount, collapseDeclared)

    // 「全部恢复默认」：引擎的用户层里改过的字段一次性 unset。
    val modifiedPaths = remember(namespace) {
        val user = namespace.user
        if (user == null) {
            emptyList()
        } else {
            buildList {
                val keys = user.keys()
                while (keys.hasNext()) {
                    val path = keys.next().split('\u0000').filter { it.isNotEmpty() }
                    if (path.isNotEmpty()) add(path)
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xxl)) {
        if (singleCard) {
            // 合并成一张卡：字段按分组顺序、组内按 tier 顺序。
            val merged = sections.flatMap { section -> section.fields }
            val ordered = merged
                .filter { !foldAdvanced || tiers[it.path] != ConfigTier.ADVANCED }
                .sortedBy { tierOrder(tiers[it.path]) }
            if (ordered.isNotEmpty()) {
                SettingsSection(title = if (showTitle) title else null, footer = footer) {
                    ordered.forEachIndexed { index, field ->
                        if (index > 0) SettingsRowDivider()
                        EngineFieldRow(viewModel, namespace, field, language)
                    }
                }
            }
        } else {
            sections.forEach { section ->
                val visible = section.fields
                    .filter { !foldAdvanced || tiers[it.path] != ConfigTier.ADVANCED }
                    .sortedBy { tierOrder(tiers[it.path]) }
                if (visible.isEmpty()) return@forEach
                SettingsSection(
                    title = if (showTitle && section.title == null) title else section.title,
                    footer = section.footer,
                ) {
                    visible.forEachIndexed { index, field ->
                        if (index > 0) SettingsRowDivider()
                        EngineFieldRow(viewModel, namespace, field, language)
                    }
                }
            }
        }

        if (foldAdvanced) {
            // 高级分组：默认收起，展开才看得到那些超时、限额、路径。
            val advanced = sections.flatMap { section ->
                section.fields.filter { tiers[it.path] == ConfigTier.ADVANCED }.map { section.title to it }
            }
            SettingsCollapsedSection(
                title = stringResource(R.string.settings_engine_advanced),
                count = advanced.size,
            ) {
                advanced.forEachIndexed { index, (_, field) ->
                    if (index > 0) SettingsRowDivider()
                    EngineFieldRow(viewModel, namespace, field, language)
                }
            }
        }

        if (modifiedPaths.isNotEmpty()) {
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.settings_engine_reset_all),
                    enabled = namespace.writable,
                    trailing = {},
                    onClick = {
                        modifiedPaths.forEach { path -> viewModel.unsetValue(namespace.ns, path) }
                    },
                )
            }
        }
    }
}

/** 一个 schema 分组（dsh-Go 的 `SchemaFormSection`）。 */
private data class FormSection(
    val title: String?,
    val fields: List<FormField>,
    val footer: String? = null,
)

private data class FormField(
    val path: List<String>,
    val node: SettingsSchemaNode,
    val kind: FieldKind,
    val depth: Int,
)

/** tier 的排序权重：主要 → 常规 → 高级。 */
private fun tierOrder(tier: ConfigTier?): Int = when (tier) {
    ConfigTier.PRIMARY -> 0
    ConfigTier.REGULAR -> 1
    ConfigTier.ADVANCED -> 2
    null -> 1
}

/**
 * 把 schema 切成「一组一张卡」：对象的直接子字段是一组，子对象另起一组，
 * 标题按 dsh-Go 的「父标题 · 子标题」拼。
 */
private fun formSections(
    ns: String,
    schema: SettingsSchemaNode,
    path: List<String>,
    depth: Int,
    language: String,
): List<FormSection> {
    val result = mutableListOf<FormSection>()
    val fields = schema.fields() ?: return result
    val plain = mutableListOf<FormField>()
    for ((key, node) in fields) {
        val kind = fieldKindOf(node)
        if (kind == FieldKind.HIDDEN) continue
        val childPath = path + key
        if (kind == FieldKind.GROUP) {
            if (plain.isNotEmpty()) {
                result += FormSection(null, plain.toList())
                plain.clear()
            }
            val heading = labelOf(ns, childPath, node, language)
            result += formSections(ns, node, childPath, depth + 1, language).map { section ->
                section.copy(
                    title = when {
                        section.title == null -> heading
                        heading.isNullOrBlank() -> section.title
                        else -> "$heading · ${section.title}"
                    },
                )
            }
        } else {
            plain.add(FormField(childPath, node, kind, depth))
        }
    }
    if (plain.isNotEmpty()) result += FormSection(null, plain.toList())
    return result
}

private enum class FieldSheet { TEXT, CHOICE, SECRET, LIST, JSON, BITSET }

private object ResetChoice

@Composable
private fun EngineFieldRow(
    viewModel: EngineSettingsViewModel,
    namespace: SettingsNamespace,
    field: FormField,
    language: String,
) {
    val ns = namespace.ns
    val path = field.path
    val node = field.node
    val meta = node.effective.meta
    val current = namespace.valueOf(path)
    val present = lookupPath(namespace.value, path).found
    val modified = namespace.userHas(path)
    val saving = viewModel.isSaving(ns, path)
    val error = viewModel.fieldError(ns, path)
    val enabled = namespace.writable && !saving && !meta.disabled
    val description = meta.description?.resolve(language)
    val modifiedLabel = stringResource(R.string.settings_modified)
    val subtitle = when {
        error != null -> error
        modified && !description.isNullOrBlank() -> "$description · $modifiedLabel"
        modified -> modifiedLabel
        else -> description
    }
    val label = labelOf(ns, path, node, language)
    val indent = if (field.depth > 0) Modifier.padding(start = Dshm.spacing.md * field.depth) else Modifier
    var sheet by remember { mutableStateOf<FieldSheet?>(null) }

    when (field.kind) {
        FieldKind.TOGGLE -> SettingsSwitchRow(
            title = label,
            subtitle = subtitle,
            subtitleColor = if (error != null) Dshm.colors.danger else null,
            checked = current as? Boolean ?: false,
            enabled = enabled,
            onCheckedChange = { viewModel.setValue(ns, path, it) },
            modifier = indent,
        )

        FieldKind.CHOICE -> {
            if (node.effective.type == "bitset") {
                SettingsRow(
                    title = label,
                    subtitle = subtitle,
                    subtitleColor = if (error != null) Dshm.colors.danger else null,
                    value = bitsetLabel(node, current),
                    enabled = enabled,
                    onClick = { sheet = FieldSheet.BITSET },
                    modifier = indent,
                )
                if (sheet == FieldSheet.BITSET) {
                    SettingsBitsetSheet(
                        title = label,
                        bits = node.effective.bits,
                        value = (current as? Number)?.toInt() ?: 0,
                        onDismiss = { sheet = null },
                        onSave = { viewModel.setValue(ns, path, it) },
                        onReset = if (modified) {
                            { viewModel.unsetValue(ns, path) }
                        } else {
                            null
                        },
                    )
                }
            } else {
                SettingsRow(
                    title = label,
                    subtitle = subtitle,
                    subtitleColor = if (error != null) Dshm.colors.danger else null,
                    value = choiceLabel(node, current, language),
                    enabled = enabled,
                    onClick = { sheet = FieldSheet.CHOICE },
                    modifier = indent,
                )
                if (sheet == FieldSheet.CHOICE) {
                    val choices = buildList {
                        if (node.effective.nullable || current == null) {
                            add(SettingsChoice(null, stringResource(R.string.settings_value_default)))
                        }
                        node.enumValues.orEmpty().forEach { value ->
                            add(SettingsChoice(value, enumLabel(value, language)))
                        }
                        if (modified) add(SettingsChoice(ResetChoice, stringResource(R.string.settings_reset_default)))
                    }
                    SettingsChoiceSheet(
                        title = label,
                        options = choices,
                        selected = current,
                        onDismiss = { sheet = null },
                        onPick = { picked ->
                            when (picked) {
                                ResetChoice, null -> viewModel.unsetValue(ns, path)
                                else -> viewModel.setValue(ns, path, picked)
                            }
                        },
                        onReset = if (modified) {
                            { viewModel.unsetValue(ns, path) }
                        } else {
                            null
                        },
                    )
                }
            }
        }

        FieldKind.NUMBER -> {
            val unit = unitFor(path.lastOrNull().orEmpty(), node)
            SettingsRow(
                title = label,
                subtitle = subtitle,
                subtitleColor = if (error != null) Dshm.colors.danger else null,
                value = formatFieldValue(path.lastOrNull().orEmpty(), node, current, language),
                enabled = enabled,
                onClick = { sheet = FieldSheet.TEXT },
                modifier = indent,
            )
            if (sheet == FieldSheet.TEXT) {
                SettingsTextSheet(
                    title = label,
                    initial = editNumberText(current, unit),
                    subtitle = listOfNotNull(description, unitHint(unit, language)).joinToString(" · "),
                    keyboardType = KeyboardType.Decimal,
                    onDismiss = { sheet = null },
                    onSave = { text ->
                        val stored = parseEditedNumber(text, unit)
                        when {
                            stored == null -> "请输入有效数字"
                            else -> validateNumber(stored, meta) ?: run {
                                viewModel.setValue(ns, path, stored)
                                null
                            }
                        }
                    },
                    onReset = if (modified) {
                        { viewModel.unsetValue(ns, path) }
                    } else {
                        null
                    },
                )
            }
        }

        FieldKind.TEXT -> {
            SettingsRow(
                title = label,
                subtitle = subtitle,
                subtitleColor = if (error != null) Dshm.colors.danger else null,
                value = current as? String,
                enabled = enabled,
                onClick = { sheet = FieldSheet.TEXT },
                modifier = indent,
            )
            if (sheet == FieldSheet.TEXT) {
                SettingsTextSheet(
                    title = label,
                    initial = current as? String ?: "",
                    subtitle = description,
                    multiline = meta.role == "textarea" || (current as? String)?.contains('\n') == true,
                    onDismiss = { sheet = null },
                    onSave = { text ->
                        viewModel.setValue(ns, path, text)
                        null
                    },
                    onReset = if (modified) {
                        { viewModel.unsetValue(ns, path) }
                    } else {
                        null
                    },
                )
            }
        }

        FieldKind.SECRET -> {
            val configured = viewModel.secretConfigured(ns, path)
            val secretWritable = viewModel.secretWritable(ns, path)
            SettingsRow(
                title = label,
                subtitle = subtitle,
                subtitleColor = if (error != null) Dshm.colors.danger else null,
                value = stringResource(
                    if (configured) R.string.settings_secret_set else R.string.settings_secret_unset,
                ),
                enabled = secretWritable && !saving,
                onClick = { sheet = FieldSheet.SECRET },
                modifier = indent,
            )
            if (sheet == FieldSheet.SECRET) {
                SettingsSecretSheet(
                    title = label,
                    configured = configured,
                    subtitle = description,
                    onDismiss = { sheet = null },
                    onSave = { value ->
                        viewModel.saveSecret(ns, path, value)
                        null
                    },
                    onClear = if (configured) {
                        { viewModel.clearSecret(ns, path) }
                    } else {
                        null
                    },
                )
            }
        }

        FieldKind.LIST -> {
            val items = (current as? JSONArray)?.let { array ->
                buildList { for (i in 0 until array.length()) array.opt(i)?.toString()?.let(::add) }
            }.orEmpty()
            val inner = node.inner?.effective
            val numeric = inner?.type == "number" || (inner?.type == "const" && inner.constValue is Number)
            SettingsRow(
                title = label,
                subtitle = subtitle,
                subtitleColor = if (error != null) Dshm.colors.danger else null,
                value = stringResource(R.string.settings_list_count, items.size),
                enabled = enabled,
                onClick = { sheet = FieldSheet.LIST },
                modifier = indent,
            )
            if (sheet == FieldSheet.LIST) {
                SettingsListSheet(
                    title = label,
                    initial = items,
                    numeric = numeric,
                    subtitle = description,
                    onDismiss = { sheet = null },
                    onSave = { values ->
                        viewModel.setValue(ns, path, JSONArray(values))
                        null
                    },
                    onReset = if (modified) {
                        { viewModel.unsetValue(ns, path) }
                    } else {
                        null
                    },
                )
            }
        }

        FieldKind.OBJECT_LIST, FieldKind.DICT, FieldKind.JSON -> {
            val expectArray = when (field.kind) {
                FieldKind.OBJECT_LIST -> true
                FieldKind.DICT -> false
                else -> when (node.effective.type) {
                    "array" -> true
                    "object", "dict" -> false
                    else -> null
                }
            }
            SettingsRow(
                title = label,
                subtitle = subtitle,
                subtitleColor = if (error != null) Dshm.colors.danger else null,
                value = formatFieldValue(path.lastOrNull().orEmpty(), node, current, language),
                enabled = enabled,
                onClick = { sheet = FieldSheet.JSON },
                modifier = indent,
            )
            if (sheet == FieldSheet.JSON) {
                SettingsJsonSheet(
                    title = label,
                    initial = jsonText(current, expectArray),
                    expectArray = expectArray,
                    subtitle = description,
                    onDismiss = { sheet = null },
                    onSave = { parsed ->
                        viewModel.setValue(ns, path, parsed)
                        null
                    },
                    onReset = if (modified) {
                        { viewModel.unsetValue(ns, path) }
                    } else {
                        null
                    },
                )
            }
        }

        FieldKind.READ_ONLY, FieldKind.HIDDEN, FieldKind.GROUP -> {
            SettingsReadOnlyValue(
                label = label,
                value = if (present) formatFieldValue(path.lastOrNull().orEmpty(), node, current, language) else "",
                subtitle = description,
            )
        }
    }
}

/** 设置 Hub 行右侧的值：取一个字段格式化后的文本。 */
fun engineValueText(namespace: SettingsNamespace?, path: List<String>, language: String): String? {
    if (namespace == null) return null
    val node = schemaAt(namespace.schema, path) ?: return null
    return formatFieldValue(path.lastOrNull().orEmpty(), node, namespace.valueOf(path), language)
}

private fun labelOf(ns: String, path: List<String>, node: SettingsSchemaNode, language: String): String =
    node.meta.title?.resolve(language) ?: SettingsCopy.fieldLabel(ns, path, language)

private fun enumLabel(value: Any?, language: String): String = when (value) {
    null -> if (SettingsCopy.isChinese(language)) "默认" else "Default"
    is Boolean -> if (value) "On" else "Off"
    else -> SettingsCopy.humanize(value.toString().replace('-', ' '))
}

private fun choiceLabel(node: SettingsSchemaNode, value: Any?, language: String): String =
    if (value == null) {
        if (SettingsCopy.isChinese(language)) "未设置" else "Not set"
    } else {
        enumLabel(value, language)
    }

private fun bitsetLabel(node: SettingsSchemaNode, value: Any?): String {
    val mask = (value as? Number)?.toInt() ?: 0
    if (mask == 0) return "—"
    return node.effective.bits.filterValues { it != 0 && (it and mask) == it }.keys.joinToString(", ")
}

private fun formatFieldValue(key: String, node: SettingsSchemaNode, value: Any?, language: String): String {
    if (value == null) return if (SettingsCopy.isChinese(language)) "未设置" else "Not set"
    return when (value) {
        is Boolean -> if (SettingsCopy.isChinese(language)) {
            if (value) "开" else "关"
        } else {
            if (value) "On" else "Off"
        }
        is Number -> {
            val unit = unitFor(key, node)
            when (unit) {
                FieldUnit.MILLIS -> formatSeconds(value.toDouble() / 1000)
                FieldUnit.SECONDS -> formatSeconds(value.toDouble())
                FieldUnit.BYTES -> formatBytes(value.toDouble())
                FieldUnit.RATIO -> "${(value.toDouble() * 100).roundToInt()}%"
                FieldUnit.TOKENS -> "${value.toLong()} tokens"
                FieldUnit.PLAIN -> trimNumber(value.toDouble())
            }
        }
        is String -> value
        is JSONArray -> stringResourceCount(value.length(), language)
        is JSONObject -> stringResourceCount(value.length(), language)
        else -> value.toString()
    }
}

private fun stringResourceCount(count: Int, language: String): String =
    if (SettingsCopy.isChinese(language)) "$count 项" else "$count items"

private fun trimNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

private fun formatBytes(bytes: Double): String {
    val b = bytes.roundToLong()
    return when {
        b >= 1024L * 1024 * 1024 -> "${(b / (1024.0 * 1024 * 1024)).roundToInt()} GB"
        b >= 1024L * 1024 -> "${(b / (1024.0 * 1024)).roundToInt()} MB"
        b >= 1024L -> "${(b / 1024.0).roundToInt()} KB"
        else -> "$b B"
    }
}

private fun formatSeconds(seconds: Double): String = when {
    seconds >= 3600 -> "${(seconds / 3600).let { if (it % 1.0 == 0.0) it.toInt() else it } } 小时"
    seconds >= 60 -> "${(seconds / 60).let { if (it % 1.0 == 0.0) it.toInt() else it } } 分钟"
    else -> "${trimNumber(seconds)} 秒"
}

private fun unitHint(unit: FieldUnit, language: String): String? {
    val zh = SettingsCopy.isChinese(language)
    return when (unit) {
        FieldUnit.MILLIS, FieldUnit.SECONDS -> if (zh) "单位：秒" else "Seconds"
        FieldUnit.BYTES -> if (zh) "单位：字节" else "Bytes"
        FieldUnit.RATIO -> "%"
        FieldUnit.TOKENS -> "tokens"
        FieldUnit.PLAIN -> null
    }
}

private fun editNumberText(value: Any?, unit: FieldUnit): String {
    val number = (value as? Number)?.toDouble() ?: return ""
    val edited = when (unit) {
        FieldUnit.MILLIS -> number / 1000
        FieldUnit.RATIO -> number * 100
        else -> number
    }
    return trimNumber(edited)
}

private fun parseEditedNumber(text: String, unit: FieldUnit): Number? {
    val typed = text.trim().toDoubleOrNull() ?: return null
    val stored = when (unit) {
        FieldUnit.MILLIS -> (typed * 1000).roundToLong()
        FieldUnit.RATIO -> typed / 100
        else -> typed
    }
    return if (stored is Double && stored % 1.0 == 0.0) stored.toLong() else stored
}

private fun validateNumber(stored: Number, meta: com.dshm.bot.data.remote.FieldMeta): String? {
    val value = stored.toDouble()
    meta.min?.let { if (value < it) return "不能小于 ${trimNumber(it)}" }
    meta.max?.let { if (value > it) return "不能大于 ${trimNumber(it)}" }
    meta.step?.takeIf { it > 0 }?.let { step ->
        val base = meta.min ?: 0.0
        val steps = (value - base) / step
        if (abs(steps - steps.roundToInt()) > 1e-9) return "必须是 ${trimNumber(step)} 的整数倍"
    }
    return null
}

private fun jsonText(value: Any?, expectArray: Boolean?): String = when (value) {
    is JSONArray -> value.toString(2)
    is JSONObject -> value.toString(2)
    null -> if (expectArray == true) "[]" else "{}"
    else -> value.toString()
}
