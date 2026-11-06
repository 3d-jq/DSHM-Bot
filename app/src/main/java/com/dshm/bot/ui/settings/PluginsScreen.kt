package com.dshm.bot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.PluginBundle
import com.dshm.bot.data.remote.PluginBundleGroup
import kotlin.math.abs

// 插件：DSH 官方可选包 + 第三方已安装包，结构与 dsh-Go 一致（分组、官方徽标、开关）。
@Composable
fun PluginsScreen(
    viewModel: EngineSettingsViewModel,
    onBack: () -> Unit,
    onRestartEngine: () -> Unit = {},
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val language = settingsLanguage()
    var openNs by rememberSaveable { mutableStateOf<String?>(null) }
    var actionError by rememberSaveable { mutableStateOf<String?>(null) }

    val namespace = openNs?.let { viewModel.namespace(it) }
    if (namespace != null) {
        EngineNamespaceScreen(
            viewModel = viewModel,
            ns = namespace.ns,
            language = language,
            onBack = { openNs = null },
        )
        return
    }

    val bundles = state.bundles
    val installed = bundles.filter { it.group == PluginBundleGroup.INSTALLED }
    val official = bundles.filter { it.group == PluginBundleGroup.OFFICIAL }

    SettingsPage(
        title = stringResource(R.string.settings_plugins),
        subtitle = stringResource(R.string.plugins_manage_hint),
        onBack = onBack,
        actions = { SettingsRefreshButton(onClick = viewModel::refresh) },
    ) {
        when {
            state.loading && bundles.isEmpty() -> item { SettingsLoading() }
            state.error != null && bundles.isEmpty() -> item {
                SettingsError(state.error.orEmpty(), onRetry = viewModel::refresh)
            }
            bundles.isEmpty() -> item {
                SettingsEmpty(stringResource(R.string.settings_plugins_read_only))
            }
            else -> {

                if (actionError != null) {
                    item { SettingsError(actionError.orEmpty()) }
                }
                if (state.pluginRestartNeeded) {
                    item {
                        SettingsSection {
                            SettingsRow(
                                title = stringResource(R.string.plugins_restart_needed_title),
                                subtitle = stringResource(R.string.plugins_restart_needed_hint),
                                value = stringResource(R.string.plugins_restart_now),
                                onClick = {
                                    viewModel.clearPluginRestart()
                                    onRestartEngine()
                                },
                            )
                        }
                    }
                }
                if (installed.isNotEmpty()) {
                    item {
                        SettingsSection(title = stringResource(R.string.plugins_group_installed)) {
                            installed.forEachIndexed { index, bundle ->
                                if (index > 0) SettingsRowDivider()
                                BundleRow(
                                    bundle = bundle,
                                    language = language,
                                    hasSettings = viewModel.namespace(bundle.name) != null,
                                    onOpenSettings = { openNs = bundle.name },
                                    onToggle = { enabled ->
                                        actionError = null
                                        viewModel.setPluginEnabled(bundle.name, enabled) { error ->
                                            if (error != null) actionError = error
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                if (official.isNotEmpty()) {
                    item {
                        SettingsSection(
                            title = stringResource(R.string.plugins_group_official),
                            footer = stringResource(R.string.plugins_official_hint),
                        ) {
                            official.forEachIndexed { index, bundle ->
                                if (index > 0) SettingsRowDivider()
                                BundleRow(
                                    bundle = bundle,
                                    language = language,
                                    hasSettings = viewModel.namespace(bundle.name) != null,
                                    onOpenSettings = { openNs = bundle.name },
                                    onToggle = { enabled ->
                                        actionError = null
                                        viewModel.setPluginEnabled(bundle.name, enabled) { error ->
                                            if (error != null) actionError = error
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                // 更新 / 卸载：dsh-Go 有、当前版本未接入，明确标注不可用
                item {
                    SettingsSection(title = stringResource(R.string.settings_plugins_update_section)) {
                        SettingsRow(
                            title = stringResource(R.string.settings_plugins_update),
                            enabled = false,
                            subtitle = stringResource(R.string.settings_unavailable_hint),
                            value = stringResource(R.string.settings_unavailable),
                        )
                        SettingsRowDivider()
                        SettingsRow(
                            title = stringResource(R.string.settings_plugins_uninstall),
                            enabled = false,
                            subtitle = stringResource(R.string.settings_unavailable_hint),
                            value = stringResource(R.string.settings_unavailable),
                        )
                    }
                }

                // 插件自己的设置项：引擎提供的 namespace 表单
                val configs = viewModel.pluginConfigNamespaces()
                if (configs.isNotEmpty()) {
                    item {
                        SettingsSection(
                            title = stringResource(R.string.settings_plugin_configs),
                            footer = stringResource(R.string.settings_engine_required),
                        ) {
                            configs.forEachIndexed { index, ns ->
                                if (index > 0) SettingsRowDivider()
                                SettingsRow(
                                    title = SettingsCopy.namespaceTitle(ns.ns, language),
                                    subtitle = ns.schema.meta.description?.resolve(language),
                                    onClick = { openNs = ns.ns },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BundleRow(
    bundle: PluginBundle,
    language: String,
    hasSettings: Boolean,
    onOpenSettings: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val title = bundle.meta?.title?.resolve(language)?.takeIf { it.isNotBlank() }
        ?: SettingsCopy.humanize(bundle.name)
    val description = bundle.meta?.description?.resolve(language)?.takeIf { it.isNotBlank() }
        ?: bundle.description
    val subtitle = listOfNotNull(bundle.version, bundle.error ?: description).joinToString(" · ")
    SettingsRow(
        title = title,
        leading = { PluginAvatar(name = bundle.name, title = title) },
        badge = if (bundle.official) {
            { OfficialBadge() }
        } else {
            null
        },
        subtitle = subtitle.takeIf { it.isNotBlank() },
        subtitleColor = if (bundle.error != null) Dshm.colors.danger else null,
        trailing = {
            if (bundle.switchable) {
                Switch(checked = bundle.enabled, onCheckedChange = onToggle)
            } else {
                Text(
                    text = stringResource(
                        if (bundle.readOnlyReason != null) {
                            R.string.plugins_read_only_badge
                        } else {
                            R.string.plugins_not_installed
                        },
                    ),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textTertiary,
                )
            }
        },
        onClick = if (hasSettings) onOpenSettings else null,
    )
}

@Composable
private fun OfficialBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Dshm.radius.xs))
            .background(Dshm.colors.surfaceMuted)
            .padding(horizontal = Dshm.spacing.sm, vertical = Dshm.spacing.xxs),
    ) {
        Text(
            text = stringResource(R.string.plugins_badge_official),
            style = Dshm.typography.rowTime,
            color = Dshm.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PluginAvatar(name: String, title: String) {
    val palette = listOf(
        Dshm.colors.accent,
        Dshm.colors.success,
        Dshm.colors.warning,
        Dshm.colors.danger,
        Dshm.colors.textSecondary,
    )
    val index = abs(name.hashCode()) % palette.size
    val color = palette[index]
    Box(
        modifier = Modifier
            .size(Dshm.spacing.composerKey)
            .clip(RoundedCornerShape(Dshm.radius.sm))
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.trim().take(1).uppercase().ifEmpty { "?" },
            style = Dshm.typography.rowTitle,
            color = color,
        )
    }
}
