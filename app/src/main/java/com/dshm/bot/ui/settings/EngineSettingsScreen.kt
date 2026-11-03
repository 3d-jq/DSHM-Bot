package com.dshm.bot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.DshEngineSettingsRepository

/** 引擎参数：读取 `settings/describe`，布尔项直接 `settings/mutate`（对 dsh-Go engine settings）。 */
@Composable
fun EngineSettingsScreen(viewModel: EngineSettingsViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    SettingsPageScaffold(title = stringResource(R.string.settings_engine_params), onBack = onBack) {
        when {
            ui.loading -> CircularProgressIndicator(color = Dshm.colors.accent)
            ui.error != null -> Text(
                text = ui.error.orEmpty(),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.danger,
            )
            ui.namespaces.isEmpty() -> Text(
                text = stringResource(R.string.info_engine_required),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.md)) {
            ui.namespaces.forEach { namespace ->
                NamespaceCard(
                    namespace = namespace,
                    onToggle = { key, value -> viewModel.toggleBoolean(namespace, key, value) },
                )
            }
        }
    }
}

@Composable
internal fun NamespaceCard(
    namespace: DshEngineSettingsRepository.Namespace,
    onToggle: (String, Boolean) -> Unit,
) {
    val keys = namespace.value.keys().asSequence().sorted().toList()
    val shape = RoundedCornerShape(Dshm.radius.lg)
    Column {
        Text(
            text = namespace.ns,
            style = Dshm.typography.rowPreview,
            color = Dshm.colors.textSecondary,
            modifier = Modifier.padding(start = Dshm.spacing.sm, bottom = Dshm.spacing.xs),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = Dshm.spacing.cardElevation,
                    shape = shape,
                    ambientColor = Dshm.colors.shadowCard,
                    spotColor = Dshm.colors.shadowCard,
                )
                .clip(shape)
                .background(Dshm.colors.surface)
                .border(Dshm.spacing.hairline, Dshm.colors.divider, shape),
        ) {
            keys.forEachIndexed { index, key ->
                val value = namespace.value.opt(key)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = key,
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    when (value) {
                        is Boolean -> Switch(
                            checked = value,
                            onCheckedChange = { onToggle(key, it) },
                        )
                        is String -> Text(
                            text = value,
                            style = Dshm.typography.rowPreview,
                            color = Dshm.colors.textTertiary,
                        )
                        is Number -> Text(
                            text = value.toString(),
                            style = Dshm.typography.rowPreview,
                            color = Dshm.colors.textTertiary,
                        )
                        else -> Spacer(Modifier.width(Dshm.spacing.xs))
                    }
                }
                if (index != keys.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = Dshm.spacing.lg),
                        thickness = Dshm.spacing.hairline,
                        color = Dshm.colors.divider,
                    )
                }
            }
            if (keys.isEmpty()) {
                Text(
                    text = stringResource(R.string.info_engine_required),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textTertiary,
                    modifier = Modifier.padding(Dshm.spacing.lg),
                )
            }
        }
    }
}

/** 按命名空间过滤的引擎设置页（模型/权限/联网搜索等复用）。 */
@Composable
fun EngineNamespacesScreen(
    viewModel: EngineSettingsViewModel,
    title: String,
    filter: (String) -> Boolean,
    emptyHint: String,
    onBack: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    SettingsPageScaffold(title = title, onBack = onBack) {
        when {
            ui.loading -> CircularProgressIndicator(color = Dshm.colors.accent)
            ui.error != null -> Text(
                text = ui.error.orEmpty(),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.danger,
            )
        }
        val matched = ui.namespaces.filter { filter(it.ns) }
        if (!ui.loading && matched.isEmpty()) {
            Text(
                text = emptyHint,
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.md)) {
            matched.forEach { namespace ->
                NamespaceCard(
                    namespace = namespace,
                    onToggle = { key, value -> viewModel.toggleBoolean(namespace, key, value) },
                )
            }
        }
    }
}

