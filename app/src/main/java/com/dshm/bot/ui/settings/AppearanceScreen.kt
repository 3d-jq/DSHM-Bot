package com.dshm.bot.ui.settings

import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.domain.model.ThemeMode

/** 外观：主题（跟随系统 / 浅色 / 深色）。 */
@Composable
fun AppearanceScreen(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(
        title = stringResource(R.string.settings_group_appearance),
        onBack = onBack,
    ) {
        item {
            SettingsSection(
                title = stringResource(R.string.settings_theme),
                footer = stringResource(R.string.settings_appearance_hint),
            ) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    if (index > 0) SettingsRowDivider()
                    SettingsRow(
                        title = themeLabel(mode),
                        trailing = {
                            RadioButton(
                                selected = mode == themeMode,
                                onClick = { onThemeChange(mode) },
                            )
                        },
                        onClick = { onThemeChange(mode) },
                    )
                }
            }
        }
    }
}
