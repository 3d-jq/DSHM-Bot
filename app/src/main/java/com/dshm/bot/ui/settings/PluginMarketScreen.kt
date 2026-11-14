package com.dshm.bot.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.MarketPlugin
import com.dshm.bot.ui.settings.SettingsPrimaryButton
import com.dshm.bot.ui.settings.SettingsSecondaryButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row

/**
 * 「插件市场」页：读远程市场索引（格式 1），装 / 卸插件。
 *
 * 结构（对齐 dsh-Go `app/lib/ui/plugins/market_tab.dart` 的列表形态，
 * 但走本项目的设置子页骨架）：
 * - 顶部：索引来源 + 刷新；
 * - 索引读不出来：`SettingsError` 说明原因并给「重试」；
 * - 每行一个插件：名字、描述、版本/作者、已安装/未安装、危险提示；
 * - 未安装的行有「安装」，已安装的行有「卸载」（卸载前二次确认）。
 *
 * 索引里声明了要批准构建脚本的包，安装前会弹确认。
 */
/**
 * 插件市场面板：索引来源 → 错误/空态 → 插件列表，外加装卸的弹窗。
 *
 * 既是「插件市场」独立页的内容，也是「插件」页里「插件市场」那一段的内容。
 */
@Composable
fun PluginMarketPanel(
    viewModel: PluginMarketViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val language = settingsLanguage()
    var uninstalling by remember { mutableStateOf<MarketPlugin?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        // 索引来源：这一页的数据来自哪里，刷新时用的是它。
        SettingsSection {
            SettingsRow(
                title = stringResource(R.string.plugins_market_index_source),
                subtitle = state.indexSource,
                value = if (state.indexSource == null) {
                    stringResource(R.string.plugins_market_index_none)
                } else {
                    null
                },
            )
        }

        val error = state.error
        if (error != null) {
            SettingsError(
                text = when (error) {
                    PluginMarketError.EngineUnavailable -> stringResource(R.string.plugins_market_error_engine)
                    is PluginMarketError.IndexUnreadable ->
                        stringResource(R.string.plugins_market_error_index, error.reason)
                    is PluginMarketError.ActionFailed ->
                        stringResource(R.string.plugins_market_error_action, error.reason)
                },
                onRetry = viewModel::refresh,
            )
        }

        when {
            state.loading && state.index == null -> {
                SettingsHint(stringResource(R.string.plugins_market_loading))
                SettingsLoading()
            }
            state.index?.plugins.isNullOrEmpty() -> {
                SettingsEmpty(stringResource(R.string.plugins_market_empty))
            }
            else -> {
                val notice = state.notice
                if (notice != null) {
                    SettingsHint(noticeText(notice))
                }
                // 正在装：给一个取消入口（引擎那边是 cancelInstall）。
                if (state.busy.isNotEmpty() && state.installRequestId != null) {
                    SettingsSection {
                        SettingsRow(
                            title = stringResource(R.string.plugins_market_cancel_install),
                            subtitle = stringResource(R.string.plugins_market_cancel_install_hint),
                            value = stringResource(R.string.plugins_market_installing),
                            onClick = viewModel::cancelInstall,
                        )
                    }
                }
                val plugins = state.index?.plugins.orEmpty()
                SettingsSection(
                    footer = if (plugins.any { it.dangerous }) {
                        stringResource(R.string.plugins_market_danger_hint)
                    } else {
                        null
                    },
                ) {
                    plugins.forEachIndexed { index, plugin ->
                        if (index > 0) SettingsRowDivider()
                        PluginMarketRow(
                            plugin = plugin,
                            language = language,
                            installed = isMarketPluginInstalled(plugin.name, state.installed),
                            busy = plugin.name in state.busy,
                            onInstall = { viewModel.install(plugin.name) },
                            onUninstall = { uninstalling = plugin },
                        )
                    }
                }
            }
        }
    }

    // 卸载二次确认。
    val target = uninstalling
    if (target != null) {
        AlertDialog(
            onDismissRequest = { uninstalling = null },
            containerColor = Dshm.colors.surface,
            title = {
                Text(
                    text = stringResource(R.string.plugins_market_uninstall_confirm_title, target.displayTitle(language)),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.plugins_market_uninstall_confirm_body),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                ) {
                    SettingsSecondaryButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = { uninstalling = null },
                        modifier = Modifier.weight(1f),
                        fillWidth = false,
                        onBackground = Dshm.colors.surface,
                    )
                    SettingsPrimaryButton(
                        text = stringResource(R.string.plugins_market_uninstall),
                        onClick = {
                            uninstalling = null
                            viewModel.uninstall(target.name)
                        },
                        modifier = Modifier.weight(1f),
                        fillWidth = false,
                        danger = true,
                    )
                }
            },
        )
    }

    // 索引声明了要批准构建脚本：先问一句再装。
    val pendingInstall = state.pendingInstall
    if (pendingInstall != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearNotice() },
            containerColor = Dshm.colors.surface,
            title = {
                Text(
                    text = stringResource(R.string.plugins_market_build_approval_title),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.plugins_market_build_approval_body,
                        pendingInstall.approvedBuilds.joinToString("、"),
                    ),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                ) {
                    SettingsSecondaryButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = { viewModel.clearNotice() },
                        modifier = Modifier.weight(1f),
                        fillWidth = false,
                        onBackground = Dshm.colors.surface,
                    )
                    SettingsPrimaryButton(
                        text = stringResource(R.string.plugins_market_build_approval_action),
                        onClick = { viewModel.confirmInstall() },
                        modifier = Modifier.weight(1f),
                        fillWidth = false,
                        danger = false,
                    )
                }
            },
        )
    }

    // 装完了但构建脚本被拦下：问用户要不要批准后重试。
    val pendingApproval = state.pendingApproval
    if (pendingApproval != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearNotice() },
            containerColor = Dshm.colors.surface,
            title = {
                Text(
                    text = stringResource(R.string.plugins_market_build_approval_title),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.plugins_market_build_approval_body,
                        pendingApproval.pendingBuilds.joinToString("、"),
                    ),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                ) {
                    SettingsSecondaryButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = { viewModel.clearNotice() },
                        modifier = Modifier.weight(1f),
                        fillWidth = false,
                        onBackground = Dshm.colors.surface,
                    )
                    SettingsPrimaryButton(
                        text = stringResource(R.string.plugins_market_build_approval_action),
                        onClick = { viewModel.approvePendingBuilds() },
                        modifier = Modifier.weight(1f),
                        fillWidth = false,
                        danger = false,
                    )
                }
            },
        )
    }
}

/** 一次装 / 卸之后的一次性提示（文案走 stringResource）。 */
@Composable
internal fun noticeText(notice: PluginMarketNotice): String = when (notice) {
    is PluginMarketNotice.Installed -> stringResource(R.string.plugins_market_installed_notice, notice.pluginName)
    is PluginMarketNotice.Removed -> stringResource(R.string.plugins_market_removed_notice, notice.pluginName)
    PluginMarketNotice.InstallCancelled -> stringResource(R.string.plugins_market_install_cancelled)
    PluginMarketNotice.InstallTooLate -> stringResource(R.string.plugins_market_install_too_late)
}

/**
 * 市场里的一个插件：名字、描述、版本/作者、装没装、有没有风险标记，右侧是动作。
 */
@Composable
internal fun PluginMarketRow(
    plugin: MarketPlugin,
    language: String,
    installed: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    val title = plugin.displayTitle(language)
    val description = plugin.description?.resolve(language)?.takeIf { it.isNotBlank() }
    val version = plugin.versions.firstOrNull()?.version
    val meta = listOfNotNull(
        version?.let { stringResource(R.string.plugins_market_version, it) },
        plugin.author?.takeIf { it.isNotBlank() }
            ?.let { stringResource(R.string.plugins_market_author, it) },
    ).joinToString(" · ")
    val subtitle = listOfNotNull(description, meta).joinToString("\n")
    SettingsRow(
        title = title,
        subtitle = subtitle.takeIf { it.isNotBlank() },
        subtitleColor = if (plugin.dangerous) Dshm.colors.danger else null,
        badge = if (plugin.dangerous) {
            { MarketDangerBadge() }
        } else {
            null
        },
        value = stringResource(
            if (installed) R.string.plugins_market_installed else R.string.plugins_market_not_installed,
        ),
        trailing = {
            if (busy) {
                CircularProgressIndicator(
                    color = Dshm.colors.accent,
                    strokeWidth = Dshm.spacing.hairline + Dshm.spacing.xs / 2,
                    modifier = Modifier.size(Dshm.spacing.topBarIcon),
                )
            } else if (installed) {
                MarketActionButton(
                    text = stringResource(R.string.plugins_market_uninstall),
                    color = Dshm.colors.danger,
                    onClick = onUninstall,
                )
            } else {
                MarketActionButton(
                    text = stringResource(R.string.plugins_market_install),
                    color = Dshm.colors.accent,
                    onClick = onInstall,
                )
            }
        },
    )
}

/**
 * 市场读 / 装 / 卸过程中要弹的三个确认框。
 *
 * 列表本身由调用方的 `LazyListScope` 懒渲染（8021 个插件不能一次性全画），
 * 这里只负责和状态层交互的弹窗。
 */
@Composable
fun PluginMarketDialogs(
    viewModel: PluginMarketViewModel,
    uninstalling: MarketPlugin?,
    onDismissUninstall: () -> Unit,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val language = settingsLanguage()

    if (uninstalling != null) {
        AlertDialog(
            onDismissRequest = onDismissUninstall,
            containerColor = Dshm.colors.surface,
            title = {
                Text(
                    text = stringResource(
                        R.string.plugins_market_uninstall_confirm_title,
                        uninstalling.displayTitle(language),
                    ),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.plugins_market_uninstall_confirm_body),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                ) {
                    SettingsSecondaryButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = onDismissUninstall,
                        modifier = Modifier.weight(1f),
                        fillWidth = false,
                        onBackground = Dshm.colors.surface,
                    )
                    SettingsPrimaryButton(
                        text = stringResource(R.string.plugins_market_uninstall),
                        onClick = {
                            val target = uninstalling
                            onDismissUninstall()
                            viewModel.uninstall(target.name)
                        },
                        modifier = Modifier.weight(1f),
                        fillWidth = false,
                        danger = true,
                    )
                }
            },
        )
    }

    val pendingInstall = state.pendingInstall
    if (pendingInstall != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearNotice() },
            containerColor = Dshm.colors.surface,
            title = {
                Text(
                    text = stringResource(R.string.plugins_market_build_approval_title),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.plugins_market_build_approval_body,
                        pendingInstall.approvedBuilds.joinToString("、"),
                    ),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                ) {
                    SettingsSecondaryButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = { viewModel.clearNotice() },
                        modifier = Modifier.weight(1f),
                        fillWidth = false,
                        onBackground = Dshm.colors.surface,
                    )
                    SettingsPrimaryButton(
                        text = stringResource(R.string.plugins_market_build_approval_action),
                        onClick = { viewModel.confirmInstall() },
                        modifier = Modifier.weight(1f),
                        fillWidth = false,
                        danger = false,
                    )
                }
            },
        )
    }

    val pendingApproval = state.pendingApproval
    if (pendingApproval != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearNotice() },
            containerColor = Dshm.colors.surface,
            title = {
                Text(
                    text = stringResource(R.string.plugins_market_build_approval_title),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.plugins_market_build_approval_body,
                        pendingApproval.pendingBuilds.joinToString("、"),
                    ),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                ) {
                    SettingsSecondaryButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = { viewModel.clearNotice() },
                        modifier = Modifier.weight(1f),
                        fillWidth = false,
                        onBackground = Dshm.colors.surface,
                    )
                    SettingsPrimaryButton(
                        text = stringResource(R.string.plugins_market_build_approval_action),
                        onClick = { viewModel.approvePendingBuilds() },
                        modifier = Modifier.weight(1f),
                        fillWidth = false,
                        danger = false,
                    )
                }
            },
        )
    }
}

/**
 * 行尾的动作：紧凑文本，不用 Material 的 TextButton（默认最小高 40dp 会撑高行）。
 *
 * 字号跟右侧的值一样（settingsRowValue），一行里不出现第三种字号。
 */
@Composable
private fun MarketActionButton(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = Dshm.typography.settingsRowValue,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** 「有风险」的小标签。 */
@Composable
private fun MarketDangerBadge() {
    Text(
        text = stringResource(R.string.plugins_market_danger),
        style = Dshm.typography.rowTime,
        color = Dshm.colors.danger,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
