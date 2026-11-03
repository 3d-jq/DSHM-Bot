package com.dshm.bot.ui.settings

import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.domain.model.PerformanceMode

/** 性能：自动 / 完整 / 轻量。轻量会关闭整屏转场，减少动效。 */
@Composable
fun PerformanceSettingsScreen(
    current: PerformanceMode,
    onSelect: (PerformanceMode) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(
        title = stringResource(R.string.settings_performance),
        onBack = onBack,
    ) {
        item {
            SettingsSection(
                title = stringResource(R.string.settings_performance_mode),
                footer = stringResource(R.string.settings_performance_hint),
            ) {
                PerformanceMode.entries.forEachIndexed { index, mode ->
                    if (index > 0) SettingsRowDivider()
                    SettingsRow(
                        title = performanceLabel(mode),
                        subtitle = when (mode) {
                            PerformanceMode.AUTO -> stringResource(R.string.settings_performance_auto_hint)
                            PerformanceMode.FULL -> stringResource(R.string.settings_performance_full_hint)
                            PerformanceMode.LIGHT -> stringResource(R.string.settings_performance_light_hint)
                        },
                        trailing = {
                            RadioButton(selected = mode == current, onClick = { onSelect(mode) })
                        },
                        onClick = { onSelect(mode) },
                    )
                }
            }
        }
    }
}
