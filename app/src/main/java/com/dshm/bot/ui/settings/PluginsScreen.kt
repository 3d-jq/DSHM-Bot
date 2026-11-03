package com.dshm.bot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm

/** 插件页：引擎 `pluginInventory/list` 的真实插件清单（名称 + 生效状态）。 */
@Composable
fun PluginsScreen(viewModel: EngineSettingsViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    SettingsPageScaffold(title = stringResource(R.string.settings_plugins), onBack = onBack) {
        when {
            ui.loading -> CircularProgressIndicator(color = Dshm.colors.accent)
            ui.error != null -> Text(ui.error.orEmpty(), style = Dshm.typography.rowPreview, color = Dshm.colors.danger)
            ui.plugins.isEmpty() -> Text(
                text = stringResource(R.string.info_engine_required),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
            )
        }
        val shape = RoundedCornerShape(Dshm.radius.lg)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Dshm.colors.surface)
                .border(Dshm.spacing.hairline, Dshm.colors.divider, shape),
        ) {
            ui.plugins.forEachIndexed { index, plugin ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = plugin.moduleName.ifEmpty { plugin.entryId },
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(
                            if (plugin.enabled) R.string.settings_value_on else R.string.settings_value_off,
                        ),
                        style = Dshm.typography.rowPreview,
                        color = if (plugin.enabled) Dshm.colors.accent else Dshm.colors.textTertiary,
                    )
                }
                if (index != ui.plugins.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = Dshm.spacing.lg),
                        thickness = Dshm.spacing.hairline,
                        color = Dshm.colors.divider,
                    )
                }
            }
        }
    }
}
