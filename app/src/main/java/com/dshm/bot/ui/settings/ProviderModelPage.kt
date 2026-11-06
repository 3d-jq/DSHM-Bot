package com.dshm.bot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.ModelDefaults
import com.dshm.bot.data.remote.ProviderModelSpec
import com.dshm.bot.data.remote.formatTokens
import com.dshm.bot.data.remote.isValidTokenCount
import com.dshm.bot.data.remote.parseTokenCount

/** 上下文长度的快捷预设（模型厂商的常见规格）。 */
private val contextPresets = listOf(32768, 65536, 131072, 200000, 262144, 1000000)

/** 最大输出的快捷预设。 */
private val maxOutputPresets = listOf(8192, 16384, 32768, 65536, 131072)

/** 第一次打开思考开关时默认勾选的档位。 */
private val initialThinkingLevels = setOf("low", "medium", "high")

/**
 * 页面列出的思考档位（多选）。`off` 不在其中 —— 「不思考」由思考开关决定；
 * `minimal` 是旧模型的档位，别处声明的会原样保留，页面不列它。
 */
private val offeredThinkingLevels = listOf("low", "medium", "high", "xhigh", "max")

/** 预设胶囊每行的列数：六个一列在窄屏上放不下（`128K` 会被挤掉）。 */
private const val PRESET_COLUMNS = 3

/**
 * 模型设置页，对齐 dsh-Go `app/lib/ui/providers/provider_model_page.dart`：
 * 基本信息（名称）→ Token 上限（上下文长度 / 最大输出）→ 能力（输入类型 / 思考 / 档位）。
 *
 * 页面只有底部条那一个主操作：
 * - 从渠道详情页进来是「保存」，直接写引擎；
 * - 从渠道表单进来是「完成」，把改好的规格交回表单，跟着表单一起保存。
 */
@Composable
fun ProviderModelPage(
    model: ProviderModelSpec,
    defaults: ModelDefaults,
    submitLabel: String,
    onSubmit: (ProviderModelSpec) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    saving: Boolean = false,
    error: String? = null,
    reduceMotion: Boolean = false,
) {
    val form = remember(model, defaults) { ModelFormState(model, defaults) }
    val tokenInvalid = stringResource(R.string.settings_model_item_token_invalid)
    val needLevel = stringResource(R.string.settings_model_item_need_level)
    val inputText = stringResource(R.string.settings_model_item_input_text)
    val inputTextImage = stringResource(R.string.settings_model_item_input_text_image)

    SettingsPage(
        title = model.label,
        subtitle = model.label.takeIf { it != model.id },
        onBack = onBack,
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Dshm.colors.surface)
                    // 底部保存条要避开系统导航条（dsh-Go 的 FormBottomBar 走 SafeArea）。
                    .navigationBarsPadding()
                    .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
            ) {
                SettingsPrimaryButton(
                    text = submitLabel,
                    enabled = !saving,
                    onClick = {
                        form.contextError = !isValidTokenCount(form.contextText)
                        form.maxOutputError = !isValidTokenCount(form.maxOutputText)
                        form.levelError = form.thinking && form.levels.isEmpty()
                        if (!form.contextError && !form.maxOutputError && !form.levelError) {
                            onSubmit(form.result(model))
                        }
                    },
                )
            }
        },
    ) {
        item {
            SettingsSection(title = stringResource(R.string.settings_model_item_basic)) {
                ProviderField(
                    label = stringResource(R.string.settings_model_item_name),
                    value = form.name,
                    onValueChange = { form.name = it },
                    placeholder = model.id,
                    // 每张卡的第一个字段：字段组顶部留白 12dp。
                    top = Dshm.spacing.md,
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_model_item_limits)) {
                CapacityField(
                    label = stringResource(R.string.settings_model_item_context),
                    text = form.contextText,
                    onTextChange = { raw ->
                        form.contextText = filterTokenText(raw)
                        form.contextWindow = parseTokenCount(form.contextText)
                        form.contextError = false
                    },
                    value = form.contextWindow,
                    presets = contextPresets,
                    fallback = defaults.contextWindow,
                    error = if (form.contextError) tokenInvalid else null,
                    onPick = { preset ->
                        // 再点一次选中的那个 = 清空，回到渠道默认值。
                        val current = form.contextWindow
                        val again = current != null && formatTokens(current) == formatTokens(preset)
                        val next = if (again) null else preset
                        form.contextWindow = next
                        form.contextText = next?.let { formatTokens(it) }.orEmpty()
                        form.contextError = false
                    },
                    top = Dshm.spacing.md,
                )
                CapacityField(
                    label = stringResource(R.string.settings_model_item_max_output),
                    text = form.maxOutputText,
                    onTextChange = { raw ->
                        form.maxOutputText = filterTokenText(raw)
                        form.maxTokens = parseTokenCount(form.maxOutputText)
                        form.maxOutputError = false
                    },
                    value = form.maxTokens,
                    presets = maxOutputPresets,
                    fallback = defaults.maxTokens,
                    error = if (form.maxOutputError) tokenInvalid else null,
                    onPick = { preset ->
                        val current = form.maxTokens
                        val again = current != null && formatTokens(current) == formatTokens(preset)
                        val next = if (again) null else preset
                        form.maxTokens = next
                        form.maxOutputText = next?.let { formatTokens(it) }.orEmpty()
                        form.maxOutputError = false
                    },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_model_item_capabilities)) {
                // 输入类型：文本 / 文本 + 图片。
                Column(
                    modifier = Modifier.padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
                ) {
                    Text(
                        text = stringResource(R.string.settings_model_item_input_type),
                        style = Dshm.typography.formFieldLabel,
                        color = Dshm.colors.textPrimary,
                        modifier = Modifier.padding(start = Dshm.spacing.xs),
                    )
                    Spacer(Modifier.height(Dshm.spacing.xs))
                    SettingsSegmentedPill(
                        options = listOf(inputText, inputTextImage),
                        selected = if (form.images) inputTextImage else inputText,
                        onSelect = { form.images = it != inputText },
                        reduceMotion = reduceMotion,
                    )
                }

                // 思考：开关在标题行右侧，展开后是多选的档位。
                Column(
                    modifier = Modifier.padding(
                        start = Dshm.spacing.lg,
                        end = Dshm.spacing.lg,
                        bottom = Dshm.spacing.lg,
                    ),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.settings_model_item_thinking),
                            style = Dshm.typography.formFieldLabel,
                            color = Dshm.colors.textPrimary,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = Dshm.spacing.xs),
                        )
                        Switch(
                            checked = form.thinking,
                            onCheckedChange = { form.toggleThinking(it) },
                        )
                    }
                    if (form.thinking) {
                        Spacer(Modifier.height(Dshm.spacing.md))
                        Text(
                            text = stringResource(R.string.settings_model_item_levels),
                            style = Dshm.typography.formFieldLabel,
                            color = Dshm.colors.textPrimary,
                            modifier = Modifier.padding(start = Dshm.spacing.xs),
                        )
                        Spacer(Modifier.height(Dshm.spacing.xs))
                        offeredThinkingLevels.forEach { level ->
                            SettingsLevelRow(
                                label = effortLabel(level),
                                checked = level in form.levels,
                                onClick = { form.toggleLevel(level) },
                            )
                        }
                        if (form.levelError) {
                            Text(
                                text = needLevel,
                                style = Dshm.typography.rowPreview,
                                color = Dshm.colors.danger,
                                modifier = Modifier.padding(
                                    start = Dshm.spacing.xs,
                                    top = Dshm.spacing.xs,
                                ),
                            )
                        }
                    }
                }
            }
        }

        if (error != null) {
            item { SettingsError(error) }
        }
    }
}

/**
 * 一个容量字段：输入框 + 一排快捷胶囊。
 * 胶囊上写着缩写（`128K`），选中的是当前值那一个；再点一次把它清空，回到渠道默认值。
 */
@Composable
private fun CapacityField(
    label: String,
    text: String,
    onTextChange: (String) -> Unit,
    value: Int?,
    presets: List<Int>,
    fallback: Int,
    error: String?,
    onPick: (Int) -> Unit,
    top: Dp = Dshm.spacing.none,
) {
    Column(modifier = Modifier.padding(horizontal = Dshm.spacing.lg, vertical = top)) {
        Text(
            text = label,
            style = Dshm.typography.formFieldLabel,
            color = Dshm.colors.textPrimary,
            modifier = Modifier.padding(start = Dshm.spacing.xs),
        )
        Spacer(Modifier.height(Dshm.spacing.xs))
        SettingsInput(
            value = text,
            onValueChange = onTextChange,
            placeholder = stringResource(R.string.settings_model_item_default_size, formatTokens(fallback)),
            keyboardType = KeyboardType.Text,
        )
        if (error != null) {
            Spacer(Modifier.height(Dshm.spacing.xs))
            Text(
                text = error,
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.danger,
                modifier = Modifier.padding(start = Dshm.spacing.xs),
            )
        }
        Spacer(Modifier.height(Dshm.spacing.sm))
        SettingsCapsuleGrid(
            columns = PRESET_COLUMNS,
            children = presets.map { preset ->
                {
                    SettingsChoiceCapsule(
                        label = formatTokens(preset),
                        selected = value != null && formatTokens(value) == formatTokens(preset),
                        onClick = { onPick(preset) },
                    )
                }
            },
        )
        Spacer(Modifier.height(Dshm.spacing.lg))
    }
}

/** 思考档位的中文名。 */
@Composable
private fun effortLabel(level: String): String = when (level) {
    "off" -> stringResource(R.string.settings_model_effort_off)
    "minimal" -> stringResource(R.string.settings_model_effort_minimal)
    "low" -> stringResource(R.string.settings_model_effort_low)
    "medium" -> stringResource(R.string.settings_model_effort_medium)
    "high" -> stringResource(R.string.settings_model_effort_high)
    "xhigh" -> stringResource(R.string.settings_model_effort_xhigh)
    "max" -> stringResource(R.string.settings_model_effort_max)
    else -> level
}

/** 容量输入框只收数字和容量单位（`128K` / `1.5M`），和 dsh-Go 的输入过滤一致。 */
private fun filterTokenText(raw: String): String =
    raw.filter { it.isDigit() || it == '.' || it == ',' || it == '_' || it.isWhitespace() || it.lowercaseChar() == 'k' || it.lowercaseChar() == 'm' }

/**
 * 模型设置页的表单状态。
 *
 * 字段没改过时保留引擎里的原样（输入类型列表、档位值），
 * 改过才写新的值回去。
 */
private class ModelFormState(model: ProviderModelSpec, defaults: ModelDefaults) {

    var name by mutableStateOf(model.name.orEmpty())
    var contextWindow by mutableStateOf(model.contextWindow)
    var maxTokens by mutableStateOf(model.maxTokens)
    var contextText by mutableStateOf(model.contextWindow?.let { formatTokens(it) }.orEmpty())
    var maxOutputText by mutableStateOf(model.maxTokens?.let { formatTokens(it) }.orEmpty())
    var images by mutableStateOf(model.acceptsImages(defaults.input))
    var thinking by mutableStateOf(model.thinks)
    var levels by mutableStateOf(model.thinkingLevels.filter { it != "off" }.toSet())
    var contextError by mutableStateOf(false)
    var maxOutputError by mutableStateOf(false)
    var levelError by mutableStateOf(false)

    /** 输入类型没改过时保留引擎里的原样。 */
    private val initialImages = model.acceptsImages(defaults.input)

    /** 模型声明过的档位（`off` 也算）；模型不思考时是空集。 */
    private val initialLevels = if (model.thinks) model.thinkingLevels.toSet() else emptySet()

    /**
     * 聊天里的模型选择器要不要有「不思考」：本页新建的模型有；
     * 别处声明的（一个总是思考的模型）保持原样。
     */
    private val offersOff = !model.thinks || initialLevels.contains("off")

    /** 写回引擎的思考档位。 */
    private val declared: Set<String>
        get() = if (!thinking) {
            emptySet()
        } else {
            buildSet {
                if (offersOff) add("off")
                addAll(levels)
            }
        }

    fun toggleThinking(on: Boolean) {
        thinking = on
        if (on && levels.isEmpty()) levels = initialThinkingLevels
        levelError = false
    }

    fun toggleLevel(level: String) {
        levels = if (level in levels) levels - level else levels + level
        levelError = false
    }

    fun result(original: ProviderModelSpec): ProviderModelSpec {
        val spec = original.copy(
            name = name.trim().ifEmpty { null },
            contextWindow = contextWindow,
            maxTokens = maxTokens,
            input = when {
                images == initialImages -> original.input
                images -> listOf("text", "image")
                else -> listOf("text")
            },
        )
        // 档位没变就不动它，引擎里配好的值保持原样。
        return if (declared != initialLevels) spec.withReasoningEfforts(declared) else spec
    }
}
