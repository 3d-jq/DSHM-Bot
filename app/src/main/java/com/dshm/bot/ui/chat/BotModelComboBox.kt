package com.dshm.bot.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm

/**
 * 模型 Combobox：点开后从当前 Bot 可用模型里选一项，选中立即回传；不支持搜索输入。
 * 新建 Bot、底部面板、完整设置页都复用这一个控件，避免三套选择器行为不一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotModelComboBox(
    models: List<BotModelOption>,
    selectedRef: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.bot_settings_model),
    defaultLabel: String = stringResource(R.string.settings_value_default),
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = models.firstOrNull { it.ref == selectedRef }?.name ?: defaultLabel
    val shape = RoundedCornerShape(Dshm.radius.md)
    val menuShape = RoundedCornerShape(Dshm.radius.lg)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = shape,
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = menuShape,
            containerColor = Dshm.colors.surface,
            border = androidx.compose.foundation.BorderStroke(
                Dshm.spacing.hairline,
                Dshm.colors.divider,
            ),
            shadowElevation = Dshm.spacing.cardElevation,
        ) {
            if (models.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.room_settings_no_options)) },
                    enabled = false,
                    onClick = {},
                )
            } else {
                models.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(option.ref)
                        },
                    )
                }
            }
        }
    }
}
