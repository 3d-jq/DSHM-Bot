package com.dshm.bot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.jsonEquals
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import com.dshm.bot.ui.components.rememberWindowHeightDp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    title: String,
    onDismiss: () -> Unit,
    heightFraction: Float = 1f,
    /** 固定在面板底部的操作区（固定高度的面板里它不会被内容顶走）。 */
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 和对话名字胶囊的面板一致：skipPartiallyExpanded = true —— 面板没有
    // 「半屏锚点」那个中间态，高度只由内容决定，往上拖不会越拉越高。
    val screenHeight = rememberWindowHeightDp()
    val fixed = heightFraction < 1f
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Dshm.colors.surface,
        contentColor = Dshm.colors.textPrimary,
        scrimColor = Dshm.colors.scrim,
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(top = Dshm.spacing.md), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .width(Dshm.spacing.xxxl)
                        .height(Dshm.spacing.xs)
                        .clip(CircleShape)
                        .background(Dshm.colors.divider),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fixed) Modifier.height(screenHeight * heightFraction) else Modifier)
                .imePadding()
                .padding(horizontal = Dshm.spacing.lg)
                .padding(top = Dshm.spacing.sm, bottom = Dshm.spacing.xxxl),
        ) {
            Text(title, style = Dshm.typography.sheetTitle, color = Dshm.colors.textPrimary)
            Spacer(Modifier.height(Dshm.spacing.md))
            if (fixed) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    content()
                }
                if (bottomBar != null) {
                    Spacer(Modifier.height(Dshm.spacing.md))
                    bottomBar()
                }
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun SheetActions(
    saveText: String = stringResource(R.string.action_save),
    cancelText: String = stringResource(R.string.action_cancel),
    saveEnabled: Boolean = true,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    extra: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Dshm.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (extra != null) extra()
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onCancel) {
            Text(cancelText, color = Dshm.colors.textSecondary, style = Dshm.typography.sheetAction)
        }
        SettingsPrimaryButton(
            text = saveText,
            onClick = onSave,
            enabled = saveEnabled,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun SettingsTextSheet(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> String?,
    subtitle: String? = null,
    placeholder: String? = null,
    multiline: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onReset: (() -> Unit)? = null,
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    SettingsSheet(title = title, onDismiss = onDismiss) {
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
                modifier = Modifier.padding(bottom = Dshm.spacing.sm),
            )
        }
        SettingsInput(
            value = text,
            onValueChange = {
                text = it
                error = null
            },
            placeholder = placeholder,
            singleLine = !multiline,
            keyboardType = keyboardType,
            visualTransformation = visualTransformation,
        )
        if (error != null) {
            Text(
                text = error.orEmpty(),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.danger,
                modifier = Modifier.padding(top = Dshm.spacing.sm),
            )
        }
        SheetActions(
            onCancel = onDismiss,
            onSave = {
                val problem = onSave(text)
                if (problem == null) onDismiss() else error = problem
            },
            extra = if (onReset != null) {
                {
                    TextButton(onClick = {
                        onReset()
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.settings_reset_default), color = Dshm.colors.accent)
                    }
                }
            } else {
                null
            },
        )
    }
}

@Composable
fun SettingsSecretSheet(
    title: String,
    configured: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> String?,
    onClear: (() -> Unit)? = null,
    subtitle: String? = null,
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    SettingsSheet(title = title, onDismiss = onDismiss) {
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
                modifier = Modifier.padding(bottom = Dshm.spacing.sm),
            )
        }
        SettingsInput(
            value = text,
            onValueChange = {
                text = it
                error = null
            },
            placeholder = if (configured) stringResource(R.string.settings_secret_keep_hint) else stringResource(R.string.settings_secret_enter_hint),
            keyboardType = KeyboardType.Password,
            visualTransformation = PasswordVisualTransformation(),
        )
        if (error != null) {
            Text(
                text = error.orEmpty(),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.danger,
                modifier = Modifier.padding(top = Dshm.spacing.sm),
            )
        }
        SheetActions(
            saveEnabled = text.isNotBlank(),
            onCancel = onDismiss,
            onSave = {
                val problem = onSave(text)
                if (problem == null) onDismiss() else error = problem
            },
            extra = if (configured && onClear != null) {
                {
                    SettingsDangerButton(
                        text = stringResource(R.string.settings_secret_clear),
                        onClick = {
                            onClear()
                            onDismiss()
                        },
                    )
                }
            } else {
                null
            },
        )
    }
}

data class SettingsChoice(val value: Any?, val label: String, val subtitle: String? = null)

@Composable
fun SettingsChoiceSheet(
    title: String,
    options: List<SettingsChoice>,
    selected: Any?,
    onDismiss: () -> Unit,
    onPick: (Any?) -> Unit,
    onReset: (() -> Unit)? = null,
) {
    SettingsSheet(title = title, onDismiss = onDismiss) {
        Column {
            options.forEachIndexed { index, option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(Dshm.radius.md))
                        .padding(vertical = Dshm.spacing.xs)
                        .then(
                            Modifier.clickableNoRipple {
                                onPick(option.value)
                                onDismiss()
                            },
                        )
                        .padding(horizontal = Dshm.spacing.sm, vertical = Dshm.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = jsonEquals(option.value, selected),
                        onClick = {
                            onPick(option.value)
                            onDismiss()
                        },
                    )
                    Spacer(Modifier.width(Dshm.spacing.sm))
                    Column(Modifier.weight(1f)) {
                        Text(option.label, style = Dshm.typography.rowTitle, color = Dshm.colors.textPrimary)
                        if (option.subtitle != null) {
                            Text(
                                option.subtitle,
                                style = Dshm.typography.rowPreview,
                                color = Dshm.colors.textSecondary,
                            )
                        }
                    }
                }
            }
            if (onReset != null) {
                TextButton(onClick = {
                    onReset()
                    onDismiss()
                }) {
                    Text(stringResource(R.string.settings_reset_default), color = Dshm.colors.accent)
                }
            }
        }
    }
}

@Composable
fun SettingsListSheet(
    title: String,
    initial: List<String>,
    numeric: Boolean,
    onDismiss: () -> Unit,
    onSave: (List<Any>) -> String?,
    subtitle: String? = null,
    onReset: (() -> Unit)? = null,
) {
    var text by remember { mutableStateOf(initial.joinToString("\n")) }
    var error by remember { mutableStateOf<String?>(null) }
    SettingsSheet(title = title, onDismiss = onDismiss) {
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
                modifier = Modifier.padding(bottom = Dshm.spacing.sm),
            )
        }
        SettingsInput(
            value = text,
            onValueChange = {
                text = it
                error = null
            },
            placeholder = stringResource(R.string.settings_list_placeholder),
            singleLine = false,
        )
        if (error != null) {
            Text(
                text = error.orEmpty(),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.danger,
                modifier = Modifier.padding(top = Dshm.spacing.sm),
            )
        }
        SheetActions(
            onCancel = onDismiss,
            onSave = {
                val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val values = mutableListOf<Any>()
                for (line in lines) {
                    if (numeric) {
                        val number = line.toDoubleOrNull()
                        if (number == null) {
                            error = "「$line」不是数字"
                            return@SheetActions
                        }
                        values.add(if (number % 1.0 == 0.0) number.toInt() else number)
                    } else {
                        values.add(line)
                    }
                }
                val problem = onSave(values)
                if (problem == null) onDismiss() else error = problem
            },
            extra = if (onReset != null) {
                {
                    TextButton(onClick = {
                        onReset()
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.settings_reset_default), color = Dshm.colors.accent)
                    }
                }
            } else {
                null
            },
        )
    }
}

/** `expectArray`：true 只收数组，false 只收对象，null 收任意 JSON。 */
@Composable
fun SettingsJsonSheet(
    title: String,
    initial: String,
    expectArray: Boolean?,
    onDismiss: () -> Unit,
    onSave: (Any) -> String?,
    subtitle: String? = null,
    onReset: (() -> Unit)? = null,
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    val invalidJson = stringResource(R.string.settings_json_invalid)
    SettingsSheet(title = title, onDismiss = onDismiss) {
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
                modifier = Modifier.padding(bottom = Dshm.spacing.sm),
            )
        }
        SettingsInput(
            value = text,
            onValueChange = {
                text = it
                error = null
            },
            placeholder = when (expectArray) {
                true -> "[]"
                false -> "{}"
                null -> "{}"
            },
            singleLine = false,
        )
        if (error != null) {
            Text(
                text = error.orEmpty(),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.danger,
                modifier = Modifier.padding(top = Dshm.spacing.sm),
            )
        }
        SheetActions(
            onCancel = onDismiss,
            onSave = {
                val parsed = runCatching { JSONTokener(text).nextValue() }.getOrNull()
                if (parsed == null) {
                    error = invalidJson
                    return@SheetActions
                }
                if (expectArray == true && parsed !is JSONArray) {
                    error = "需要一个 JSON 数组"
                    return@SheetActions
                }
                if (expectArray == false && parsed !is JSONObject) {
                    error = "需要一个 JSON 对象"
                    return@SheetActions
                }
                val problem = onSave(parsed)
                if (problem == null) onDismiss() else error = problem
            },
            extra = if (onReset != null) {
                {
                    TextButton(onClick = {
                        onReset()
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.settings_reset_default), color = Dshm.colors.accent)
                    }
                }
            } else {
                null
            },
        )
    }
}

@Composable
fun SettingsBitsetSheet(
    title: String,
    bits: Map<String, Int>,
    value: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
    onReset: (() -> Unit)? = null,
) {
    var selected by remember(value) {
        mutableStateOf(bits.filterValues { (it and value) == it && it != 0 }.keys.toSet())
    }
    SettingsSheet(title = title, onDismiss = onDismiss) {
        Column {
            for ((name, mask) in bits) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickableNoRipple {
                            selected = if (name in selected) selected - name else selected + name
                        }
                        .padding(vertical = Dshm.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = name in selected,
                        onCheckedChange = {
                            selected = if (name in selected) selected - name else selected + name
                        },
                    )
                    Spacer(Modifier.width(Dshm.spacing.sm))
                    Text(name, style = Dshm.typography.rowTitle, color = Dshm.colors.textPrimary)
                }
            }
            if (onReset != null) {
                TextButton(onClick = {
                    onReset()
                    onDismiss()
                }) {
                    Text(stringResource(R.string.settings_reset_default), color = Dshm.colors.accent)
                }
            }
            SheetActions(
                onCancel = onDismiss,
                onSave = {
                    val mask = bits.filterKeys { it in selected }.values.fold(0) { acc, bit -> acc or bit }
                    onSave(mask)
                    onDismiss()
                },
            )
        }
    }
}

/** 轻量无涟漪点击，用于弹层里的整行选项。 */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    clickable(
        interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
        indication = null,
        onClick = onClick,
    )

@Composable
fun SettingsSheetFootnote(text: String) {
    Text(
        text = text,
        style = Dshm.typography.rowPreview,
        color = Dshm.colors.textTertiary,
        textAlign = TextAlign.Start,
        modifier = Modifier.padding(top = Dshm.spacing.sm),
    )
}
