package com.dshm.bot.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R

data class SettingsSearchEntry(
    val id: String,
    val title: String,
    val group: String,
    val value: String? = null,
    val keywords: String = "",
    val action: () -> Unit,
)

/** 设置搜索：按标题/分组/关键词过滤，点一行直接进入对应设置。 */
@Composable
fun SettingsSearchScreen(
    entries: List<SettingsSearchEntry>,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val needle = query.trim().lowercase()
    val filtered = if (needle.isEmpty()) {
        entries
    } else {
        entries.filter {
            it.title.lowercase().contains(needle) ||
                it.group.lowercase().contains(needle) ||
                it.keywords.lowercase().contains(needle)
        }
    }

    SettingsPage(title = stringResource(R.string.settings_search_title), onBack = onBack) {
        item {
            SettingsInput(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.settings_search_hint),
                modifier = Modifier.focusRequester(focusRequester),
            )
        }
        if (filtered.isEmpty()) {
            item { SettingsEmpty(stringResource(R.string.settings_search_empty)) }
        } else {
            filtered.groupBy { it.group }.forEach { (group, entries) ->
                item(key = "group-$group") {
                    androidx.compose.material3.Text(
                        text = group,
                        style = com.dshm.bot.core.design.Dshm.typography.rowPreview,
                        color = com.dshm.bot.core.design.Dshm.colors.textSecondary,
                        modifier = Modifier.padding(start = com.dshm.bot.core.design.Dshm.spacing.sm),
                    )
                }
                item(key = "rows-$group") {
                    SettingsSection {
                        entries.forEachIndexed { index, entry ->
                            if (index > 0) SettingsRowDivider()
                            SettingsRow(
                                title = entry.title,
                                value = entry.value,
                                onClick = {
                                    onBack()
                                    entry.action()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
