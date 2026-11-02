package com.dshm.bot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.domain.model.ThemeMode

/** 外观设置（参照 dsh-Go appearance_page）：主题三选一，后续配色/图标也放这里。 */
@Composable
fun AppearanceScreen(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(
        title = stringResource(R.string.settings_group_appearance),
        onBack = onBack,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.md)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Dshm.radius.lg))
                    .background(Dshm.colors.surface)
                    .padding(vertical = Dshm.spacing.xs),
            ) {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onThemeChange(mode) }
                            .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == themeMode, onClick = { onThemeChange(mode) })
                        Spacer(Modifier.width(Dshm.spacing.sm))
                        Text(
                            text = themeLabelOf(mode),
                            style = Dshm.typography.rowTitle,
                            color = Dshm.colors.textPrimary,
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.settings_appearance_hint),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textTertiary,
            )
        }
    }
}

@Composable
internal fun themeLabelOf(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    },
)
