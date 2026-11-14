package com.dshm.bot.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.MarketPlugin
import com.dshm.bot.data.remote.PluginBundle
import com.dshm.bot.data.remote.PluginBundleGroup
import coil.compose.AsyncImage
import kotlin.math.abs

// 插件：DSH 官方可选包 + 第三方已安装包，结构与 dsh-Go 一致（分组、官方徽标、开关）。
/** 插件页的两个分段（对齐 dsh-Go 的 PluginsTab）。 */
enum class PluginsTab { INSTALLED, MARKET }

@Composable
fun PluginsScreen(
    viewModel: EngineSettingsViewModel,
    onBack: () -> Unit,
    onRestartEngine: () -> Unit = {},
    marketViewModel: PluginMarketViewModel? = null,
) {
    // dsh-Go：一个页面两个分段 ——「已安装」/「插件市场」。
    var tab by rememberSaveable { mutableStateOf(PluginsTab.INSTALLED) }
    // 分段文案要先取好：点击回调里不能再调 stringResource。
    val installedLabel = stringResource(R.string.plugins_tab_installed)
    val marketLabel = stringResource(R.string.plugins_market_title)
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val language = settingsLanguage()
    val marketState = marketViewModel?.ui?.collectAsStateWithLifecycle()?.value
    var marketQuery by rememberSaveable { mutableStateOf("") }
    var marketUninstall by remember { mutableStateOf<MarketPlugin?>(null) }
    var openNs by rememberSaveable { mutableStateOf<String?>(null) }

    // 插件设置表单是这一页的内层页：系统返回先收它，别让顶层弹掉设置栈。
    BackHandler(enabled = openNs != null) { openNs = null }

    var detailName by rememberSaveable { mutableStateOf<String?>(null) }
    var actionError by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf<String?>(null) }
    var uninstallFor by remember { mutableStateOf<PluginBundle?>(null) }

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
    val detailBundle = detailName?.let { name -> bundles.firstOrNull { it.name == name } }

    // 切到市场时再拉一次索引；页面首次进入「已安装」不额外打网络。
    LaunchedEffect(tab) {
        if (tab == PluginsTab.MARKET) marketViewModel?.refresh()
    }

    SettingsPage(
        title = stringResource(R.string.settings_plugins),
        onBack = onBack,
        actions = {
            SettingsRefreshButton(
                onClick = {
                    viewModel.refresh()
                    if (tab == PluginsTab.MARKET) marketViewModel?.refresh()
                },
            )
        },
    ) {
        item {
            SettingsSegmentedPill(
                options = listOf(installedLabel, marketLabel),
                selected = if (tab == PluginsTab.INSTALLED) installedLabel else marketLabel,
                onSelect = { picked ->
                    tab = if (picked == installedLabel) PluginsTab.INSTALLED else PluginsTab.MARKET
                },
                modifier = Modifier.padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
            )
        }
        if (tab == PluginsTab.INSTALLED) {
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
                                    onOpenDetail = { detailName = bundle.name },
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
                                    onOpenDetail = { detailName = bundle.name },
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

            }
        }
        } else {
            val market = marketState
            if (marketViewModel == null || market == null) {
                item { SettingsEmpty(stringResource(R.string.plugins_market_hint)) }
            } else {
                item {
                    SettingsSection {
                        SettingsRow(
                            title = stringResource(R.string.plugins_market_index_source),
                            subtitle = market.indexSource,
                            value = if (market.indexSource == null) {
                                stringResource(R.string.plugins_market_index_none)
                            } else {
                                null
                            },
                        )
                    }
                }

                val error = market.error
                if (error != null) {
                    item {
                        SettingsError(
                            text = when (error) {
                                PluginMarketError.EngineUnavailable ->
                                    stringResource(R.string.plugins_market_error_engine)
                                is PluginMarketError.IndexUnreadable ->
                                    stringResource(R.string.plugins_market_error_index, error.reason)
                                is PluginMarketError.ActionFailed ->
                                    stringResource(R.string.plugins_market_error_action, error.reason)
                            },
                            onRetry = marketViewModel::refresh,
                        )
                    }
                }

                if (market.index?.plugins?.isNotEmpty() == true) {
                    item {
                        SettingsInput(
                            value = marketQuery,
                            onValueChange = { marketQuery = it },
                            placeholder = stringResource(R.string.plugins_market_search),
                        )
                    }
                }

                when {
                    market.loading && market.index == null -> {
                        item { SettingsHint(stringResource(R.string.plugins_market_loading)) }
                        item { SettingsLoading() }
                    }
                    market.index?.plugins.isNullOrEmpty() ->
                        item { SettingsEmpty(stringResource(R.string.plugins_market_empty)) }
                    else -> {
                        market.notice?.let { notice ->
                            item { SettingsHint(noticeText(notice)) }
                        }
                        if (market.busy.isNotEmpty() && market.installRequestId != null) {
                            item {
                                SettingsSection {
                                    SettingsRow(
                                        title = stringResource(R.string.plugins_market_cancel_install),
                                        subtitle = stringResource(R.string.plugins_market_cancel_install_hint),
                                        value = stringResource(R.string.plugins_market_installing),
                                        onClick = marketViewModel::cancelInstall,
                                    )
                                }
                            }
                        }

                        val query = marketQuery.trim().lowercase()
                        val plugins = market.index?.plugins.orEmpty().filter { plugin ->
                            query.isEmpty() ||
                                plugin.name.lowercase().contains(query) ||
                                plugin.displayTitle(language).lowercase().contains(query) ||
                                plugin.description?.resolve(language)?.contains(query, ignoreCase = true) == true
                        }
                        if (plugins.isEmpty()) {
                            item { SettingsEmpty(stringResource(R.string.plugins_market_no_results)) }
                        } else {
                            // 8021 个插件不能一次性全画：按 20 条一块，交给 LazyColumn 懒加载。
                            plugins.chunked(20).forEach { chunk ->
                                item(key = "market:${chunk.first().name}") {
                                    SettingsSection {
                                        chunk.forEachIndexed { index, plugin ->
                                            if (index > 0) SettingsRowDivider()
                                            PluginMarketRow(
                                                plugin = plugin,
                                                language = language,
                                                installed = isMarketPluginInstalled(plugin.name, market.installed),
                                                busy = plugin.name in market.busy,
                                                onInstall = { marketViewModel.install(plugin.name) },
                                                onUninstall = { marketUninstall = plugin },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val detail = detailBundle
    if (detail != null && openNs == null) {
        PluginBundleSheet(
            bundle = detail,
            language = language,
            viewModel = viewModel,
            onDismiss = { detailName = null },
            onOpenSettings = { ns ->
                detailName = null
                openNs = ns
            },
            onUninstall = { uninstallFor = detail },
        )
    }

    if (marketViewModel != null) {
        PluginMarketDialogs(
            viewModel = marketViewModel,
            uninstalling = marketUninstall,
            onDismissUninstall = { marketUninstall = null },
        )
    }

    // 卸载确认
    val removing = uninstallFor
    if (removing != null) {
        AlertDialog(
            onDismissRequest = { uninstallFor = null },
            containerColor = Dshm.colors.surface,
            title = {
                Text(
                    text = stringResource(R.string.plugins_uninstall_title, removing.name),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.plugins_uninstall_hint),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    uninstallFor = null
                    detailName = null
                    busy = removing.name
                    actionError = null
                    viewModel.removeBundle(removing.name) { error ->
                        busy = null
                        if (error != null) actionError = error
                    }
                }) {
                    Text(stringResource(R.string.action_delete), color = Dshm.colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { uninstallFor = null }) {
                    Text(stringResource(R.string.action_cancel), color = Dshm.colors.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun BundleRow(
    bundle: PluginBundle,
    language: String,
    onOpenDetail: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val title = bundle.meta?.title?.resolve(language)?.takeIf { it.isNotBlank() }
        ?: SettingsCopy.humanize(bundle.name)
    val description = bundle.meta?.description?.resolve(language)?.takeIf { it.isNotBlank() }
        ?: bundle.description
    val subtitle = listOfNotNull(bundle.version, bundle.error ?: description).joinToString(" · ")
    SettingsRow(
        title = title,
        leading = { PluginAvatar(name = bundle.name, title = title, icon = bundle.meta?.icon) },
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
        onClick = onOpenDetail,
    )
}

/**
 * 插件详情（对齐 dsh-Go `showBundleSheet` / `BundleSheet`）：
 * 大图标、名字、版本来源、启用开关、插件自己的设置、更新与卸载。
 */
@Composable
private fun PluginBundleSheet(
    bundle: PluginBundle,
    language: String,
    viewModel: EngineSettingsViewModel,
    onDismiss: () -> Unit,
    onOpenSettings: (String) -> Unit,
    onUninstall: () -> Unit,
) {
    val title = bundle.meta?.title?.resolve(language)?.takeIf { it.isNotBlank() }
        ?: SettingsCopy.humanize(bundle.name)
    val description = bundle.meta?.description?.resolve(language)?.takeIf { it.isNotBlank() }
        ?: bundle.description
    var actionError by remember(bundle.name) { mutableStateOf<String?>(null) }
    var busy by remember(bundle.name) { mutableStateOf(false) }
    val configs = if (bundle.enabled) {
        viewModel.pluginConfigNamespaces().filter { ns -> bundle.rows.any { it.rowId == ns.ns } }
    } else {
        emptyList()
    }

    SettingsSheet(title = stringResource(R.string.plugins_detail_title), onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PluginAvatar(
                name = bundle.name,
                title = title,
                icon = bundle.meta?.icon,
                size = Dshm.spacing.settingsAvatar,
            )
            Spacer(Modifier.size(Dshm.spacing.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = Dshm.typography.sheetTitle,
                        color = Dshm.colors.textPrimary,
                        maxLines = 2,
                    )
                    if (bundle.official) {
                        Spacer(Modifier.size(Dshm.spacing.xs))
                        OfficialBadge()
                    }
                }
                Text(
                    text = bundle.name,
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textTertiary,
                    maxLines = 1,
                )
            }
        }
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.size(Dshm.spacing.md))
            Text(
                text = description,
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
            )
        }
        if (bundle.error != null) {
            Spacer(Modifier.size(Dshm.spacing.sm))
            Text(
                text = bundle.error,
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.danger,
            )
        }

        if (bundle.switchable) {
            Spacer(Modifier.size(Dshm.spacing.lg))
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.plugins_detail_enable),
                    subtitle = stringResource(R.string.plugins_manage_hint),
                    trailing = {
                        Switch(
                            checked = bundle.enabled,
                            onCheckedChange = { enabled ->
                                actionError = null
                                viewModel.setPluginEnabled(bundle.name, enabled) { error ->
                                    if (error != null) actionError = error
                                }
                            },
                        )
                    },
                )
            }
        }

        Spacer(Modifier.size(Dshm.spacing.lg))
        SettingsSection {
            if (bundle.version != null) {
                SettingsRow(
                    title = stringResource(R.string.settings_version),
                    value = bundle.version,
                )
            }
            if (bundle.source != null) {
                if (bundle.version != null) SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.plugins_detail_source),
                    value = bundle.source,
                )
            }
            if (bundle.rows.isNotEmpty()) {
                if (bundle.version != null || bundle.source != null) SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.plugins_detail_components),
                    value = bundle.rows.size.toString(),
                )
            }
            bundle.readOnlyReason?.let { reason ->
                if (bundle.version != null || bundle.source != null || bundle.rows.isNotEmpty()) {
                    SettingsRowDivider()
                }
                SettingsRow(
                    title = stringResource(R.string.plugins_read_only_badge),
                    subtitle = reason,
                )
            }
        }

        if (configs.isNotEmpty()) {
            Spacer(Modifier.size(Dshm.spacing.lg))
            SettingsSection(
                title = stringResource(R.string.settings_plugin_configs),
                footer = stringResource(R.string.settings_engine_required),
            ) {
                configs.forEachIndexed { index, ns ->
                    if (index > 0) SettingsRowDivider()
                    SettingsRow(
                        title = SettingsCopy.namespaceTitle(ns.ns, language),
                        subtitle = ns.schema.meta.description?.resolve(language),
                        onClick = { onOpenSettings(ns.ns) },
                    )
                }
            }
        }

        val canUpdate = !bundle.installTargetSpec.isNullOrBlank()
        if (canUpdate || bundle.removable) {
            Spacer(Modifier.size(Dshm.spacing.lg))
            SettingsSection(
                title = stringResource(R.string.settings_plugins_update_section),
                footer = stringResource(R.string.settings_plugins_update_hint),
            ) {
                if (canUpdate) {
                    SettingsRow(
                        title = stringResource(R.string.settings_plugins_update),
                        subtitle = bundle.installTargetSpec,
                        trailing = {
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    actionError = null
                                    busy = true
                                    viewModel.reinstallBundle(bundle) { error ->
                                        busy = false
                                        if (error != null) actionError = error
                                    }
                                },
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_plugins_update),
                                    style = Dshm.typography.rowPreview,
                                    color = if (busy) Dshm.colors.textTertiary else Dshm.colors.accent,
                                )
                            }
                        },
                    )
                }
                if (canUpdate && bundle.removable) SettingsRowDivider()
                if (bundle.removable) {
                    SettingsRow(
                        title = stringResource(R.string.settings_plugins_uninstall),
                        subtitle = stringResource(R.string.plugins_uninstall_hint),
                        trailing = {
                            TextButton(enabled = !busy, onClick = onUninstall) {
                                Text(
                                    text = stringResource(R.string.settings_plugins_uninstall),
                                    style = Dshm.typography.rowPreview,
                                    color = Dshm.colors.danger,
                                )
                            }
                        },
                    )
                }
            }
        }

        val error = actionError
        if (error != null) {
            Spacer(Modifier.size(Dshm.spacing.md))
            SettingsError(error)
        }
    }
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
internal fun PluginAvatar(
    name: String,
    title: String,
    icon: String? = null,
    size: Dp = Dshm.spacing.composerKey,
) {
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
            .size(size)
            .clip(RoundedCornerShape(Dshm.radius.sm))
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.trim().take(1).uppercase().ifEmpty { "?" },
            style = Dshm.typography.rowTitle,
            color = color,
        )
        if (icon != null && (icon.startsWith("data:image/") || icon.startsWith("https://"))) {
            AsyncImage(
                model = icon,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
