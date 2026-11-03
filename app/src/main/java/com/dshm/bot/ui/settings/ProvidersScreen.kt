package com.dshm.bot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.DshProvidersRepository

/** 模型渠道列表（对齐 dsh-Go providers_page：官方 / 订阅 / 自定义）。 */
@Composable
fun ProvidersScreen(viewModel: ProvidersViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    SettingsPageScaffold(title = stringResource(R.string.settings_model), onBack = onBack) {
        when {
            ui.loading -> CircularProgressIndicator(color = Dshm.colors.accent)
            ui.error != null -> Text(
                text = ui.error.orEmpty(),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.danger,
            )
        }
        if (ui.providers.isNotEmpty()) {
            Text(
                text = stringResource(R.string.providers_count, ui.providers.size),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
            )
        }
        Group(
            title = stringResource(R.string.providers_group_official),
            providers = ui.providers.filter { it.kind == DshProvidersRepository.Kind.OFFICIAL },
        )
        Group(
            title = stringResource(R.string.providers_group_subscription),
            providers = ui.providers.filter { it.kind == DshProvidersRepository.Kind.SUBSCRIPTION },
        )
        Group(
            title = stringResource(R.string.providers_group_custom),
            providers = ui.providers.filter { it.kind == DshProvidersRepository.Kind.CUSTOM },
        )
        if (!ui.loading && ui.error == null && ui.providers.isEmpty()) {
            Text(
                text = stringResource(R.string.info_engine_required),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun Group(title: String, providers: List<DshProvidersRepository.Provider>) {
    if (providers.isEmpty()) return
    Column {
        Text(
            text = title,
            style = Dshm.typography.rowPreview,
            color = Dshm.colors.textSecondary,
            modifier = Modifier.padding(start = Dshm.spacing.sm, bottom = Dshm.spacing.xs),
        )
        val shape = RoundedCornerShape(Dshm.radius.lg)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Dshm.colors.surface),
        ) {
            providers.forEachIndexed { index, provider ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { }
                        .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(Dshm.spacing.avatar)
                            .clip(CircleShape)
                            .background(Dshm.colors.surfaceMuted),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = provider.name.trim().take(1).uppercase(),
                            style = Dshm.typography.rowTitle,
                            color = Dshm.colors.textSecondary,
                        )
                    }
                    Spacer(Modifier.width(Dshm.spacing.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = provider.name,
                            style = Dshm.typography.rowTitle,
                            color = Dshm.colors.textPrimary,
                        )
                        Text(
                            text = provider.id,
                            style = Dshm.typography.rowPreview,
                            color = Dshm.colors.textTertiary,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(Dshm.spacing.xs)
                            .clip(CircleShape)
                            .background(Dshm.colors.success),
                    )
                    Spacer(Modifier.width(Dshm.spacing.sm))
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_chevron_right),
                        contentDescription = null,
                        tint = Dshm.colors.textTertiary,
                        modifier = Modifier.size(Dshm.spacing.topBarIcon),
                    )
                }
                if (index != providers.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = Dshm.spacing.lg),
                        thickness = Dshm.spacing.hairline,
                        color = Dshm.colors.divider,
                    )
                }
            }
        }
    }
}
