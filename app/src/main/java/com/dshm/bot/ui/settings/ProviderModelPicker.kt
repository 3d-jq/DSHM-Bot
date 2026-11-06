package com.dshm.bot.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm

/**
 * dsh-Go 的模型选择面板（`ModelPickerSheet`）：
 * - 候选 ≤ [SEARCH_THRESHOLD] 时**默认全选**，超过才默认空选并给搜索框；
 * - 已经在渠道里的模型显示「已添加」，勾选框禁用，不能重复加；
 * - 顶部显示候选数量，全选按钮在「全选 / 全不选」之间切换。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    candidates: List<String>,
    alreadyAdded: Set<String> = emptySet(),
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selection by remember(candidates, alreadyAdded) {
        mutableStateOf(
            if (candidates.size <= SEARCH_THRESHOLD) {
                candidates.filter { it !in alreadyAdded }.toSet()
            } else {
                emptySet()
            },
        )
    }
    var query by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState()

    val visible = remember(candidates, query) {
        if (query.isBlank()) {
            candidates
        } else {
            candidates.filter { it.contains(query.trim(), ignoreCase = true) }
        }
    }
    val selectable = visible.filter { it !in alreadyAdded }
    val allPicked = selectable.isNotEmpty() && selectable.all { it in selection }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Dshm.colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dshm.spacing.lg)
                .padding(bottom = Dshm.spacing.xxl),
        ) {
            Text(
                text = stringResource(R.string.settings_model_picker_title),
                style = Dshm.typography.sheetTitle,
                color = Dshm.colors.textPrimary,
            )

            if (candidates.size > SEARCH_THRESHOLD) {
                Spacer(Modifier.height(Dshm.spacing.sm))
                SettingsInput(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.settings_model_search),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_model_picker_count, candidates.size),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        selection = if (allPicked) {
                            selection - selectable.toSet()
                        } else {
                            selection + selectable.toSet()
                        }
                    },
                    enabled = selectable.isNotEmpty(),
                ) {
                    Text(
                        text = stringResource(
                            if (allPicked) {
                                R.string.settings_model_select_none
                            } else {
                                R.string.settings_model_select_all
                            },
                        ),
                        color = Dshm.colors.accent,
                    )
                }
            }

            if (visible.isEmpty()) {
                Spacer(Modifier.height(Dshm.spacing.md))
                Text(
                    text = stringResource(R.string.settings_model_picker_empty),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textTertiary,
                )
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = Dshm.spacing.composerMinHeight * 5)
                        .verticalScroll(rememberScrollState()),
                ) {
                    visible.forEach { id ->
                        val added = id in alreadyAdded
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (added) {
                                        Modifier
                                    } else {
                                        Modifier.clickable {
                                            selection = if (id in selection) {
                                                selection - id
                                            } else {
                                                selection + id
                                            }
                                        }
                                    },
                                )
                                .padding(vertical = Dshm.spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = added || id in selection,
                                enabled = !added,
                                onCheckedChange = { checked ->
                                    selection = if (checked) selection + id else selection - id
                                },
                            )
                            Spacer(Modifier.width(Dshm.spacing.sm))
                            Text(
                                text = id,
                                style = Dshm.typography.settingsRowTitle,
                                color = if (added) Dshm.colors.textTertiary else Dshm.colors.textPrimary,
                            )
                            if (added) {
                                Spacer(Modifier.width(Dshm.spacing.sm))
                                Text(
                                    text = stringResource(R.string.settings_model_already_added),
                                    style = Dshm.typography.rowPreview,
                                    color = Dshm.colors.textTertiary,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(Dshm.spacing.md))
            SettingsPrimaryButton(
                text = stringResource(R.string.settings_model_picker_add, selection.size),
                enabled = selection.isNotEmpty(),
                onClick = { onConfirm(candidates.filter { it in selection }) },
            )
        }
    }
}

/** 候选超过这个数量才默认空选并给搜索框，和 dsh-Go 的 `shortList` 一致。 */
private const val SEARCH_THRESHOLD = 8
