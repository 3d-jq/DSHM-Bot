package com.dshm.bot.ui.settings

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm

// 「子代理」命名空间：它的页面是专门做的（模型选择要读模型目录）。
private const val SUBAGENT_NS = "subagent"

// 引擎设置 Hub（对齐 dsh-Go EngineSettingsHubPage）：
// 子代理 / Agent 循环 / 命令执行 → 打开 schema 表单；
// 插件清单与插件自带设置 → 各自的 namespace。
@Composable
fun EngineSettingsScreen(
    viewModel: EngineSettingsViewModel,
    onBack: () -> Unit,
    onOpenPlugins: () -> Unit = {},
    onOpenProviders: () -> Unit = {},
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val language = settingsLanguage()
    var openNs by rememberSaveable { mutableStateOf<String?>(null) }
    var openInventory by rememberSaveable { mutableStateOf(false) }

    // 插件清单是引擎参数页内部的一层，点条目直接进它的设置表单。
    if (openInventory) {
        PluginInventoryScreen(
            viewModel = viewModel,
            onBack = { openInventory = false },
            onOpenSettings = { ns, _ -> openInventory = false; openNs = ns },
        )
        return
    }

    // 「子代理」有专门的页（运行限制 + 模型选择），不走通用 schema 表单。
    if (openNs == SUBAGENT_NS) {
        SubagentSettingsScreen(
            viewModel = viewModel,
            onBack = { openNs = null },
            onOpenProviders = onOpenProviders,
        )
        return
    }

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

    SettingsPage(
        title = stringResource(R.string.settings_engine_params),
        onBack = onBack,
        actions = {
            SettingsRefreshButton(onClick = viewModel::refresh)
        },
    ) {
        when {
            state.loading && state.description == null -> item { SettingsLoading() }
            state.error != null && state.description == null -> item {
                SettingsError(state.error.orEmpty(), onRetry = viewModel::refresh)
            }
            state.description == null -> item {
                SettingsEmpty(stringResource(R.string.settings_engine_required))
            }
            else -> {
                item {
                    val subagent = viewModel.namespace("subagent")
                    val agentLoop = viewModel.namespace("agent-loop")
                    val shellNs = viewModel.shellNamespace()
                    val shell = shellNs?.let { viewModel.namespace(it) }
                    // dsh-Go：卡内行间不画分割线，只靠行内边距分组（SheetGroup）。
                    SettingsSection(title = stringResource(R.string.engine_settings_run)) {
                        if (subagent != null) {
                            SettingsRow(
                                title = SettingsCopy.namespaceTitle("subagent", language),
                                icon = R.drawable.ic_lucide_users,
                                value = engineValueText(subagent, listOf("maxDepth"), language),
                                onClick = { openNs = "subagent" },
                            )
                        }
                        if (agentLoop != null) {
                            SettingsRow(
                                title = SettingsCopy.namespaceTitle("agent-loop", language),
                                icon = R.drawable.ic_lucide_code,
                                value = engineValueText(agentLoop, listOf("maxParallelToolCalls"), language),
                                onClick = { openNs = "agent-loop" },
                            )
                        }
                        if (shellNs != null) {
                            SettingsRow(
                                title = SettingsCopy.namespaceTitle(shellNs, language),
                                icon = R.drawable.ic_lucide_terminal,
                                value = engineValueText(shell, listOf("timeoutMs"), language),
                                onClick = { openNs = shellNs },
                            )
                        }
                    }
                }

                item {
                    val plugins = state.plugins?.entries.orEmpty()
                    val configs = viewModel.pluginConfigNamespaces()
                    // dsh-Go：插件行的标题取插件清单里的显示名，不是 namespace 原始名。
                    val titleOf: (String) -> String = { ns ->
                        plugins.firstOrNull { it.rowId == ns }?.meta?.title?.resolve(language)
                            ?: state.bundles
                                .firstOrNull { bundle -> bundle.name == ns }?.meta?.title?.resolve(language)
                            ?: SettingsCopy.namespaceTitle(ns, language)
                    }
                    SettingsSection(title = stringResource(R.string.engine_settings_plugins)) {
                        SettingsRow(
                            title = stringResource(R.string.settings_plugin_inventory),
                            icon = R.drawable.ic_lucide_puzzle,
                            onClick = { openInventory = true },
                        )
                        configs.forEach { ns ->
                            SettingsRow(
                                title = titleOf(ns.ns),
                                icon = R.drawable.ic_lucide_puzzle,
                                subtitle = if (!ns.writable) {
                                    stringResource(R.string.settings_engine_read_only)
                                } else {
                                    null
                                },
                                onClick = { openNs = ns.ns },
                            )
                        }
                    }
                }
            }
        }
    }
}

// 单个 namespace 的 schema 表单页（引擎 Hub / 插件页共用）。
@Composable
fun EngineNamespaceScreen(
    viewModel: EngineSettingsViewModel,
    ns: String,
    language: String,
    onBack: () -> Unit,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val namespace = state.description?.namespace(ns)
    SettingsPage(
        title = SettingsCopy.namespaceTitle(ns, language),
        onBack = onBack,
        actions = {
            SettingsRefreshButton(onClick = viewModel::refresh)
        },
    ) {
        if (namespace == null) {
            item { SettingsEmpty(stringResource(R.string.info_engine_required)) }
        } else {
            item {
                EngineNamespaceSection(
                    viewModel = viewModel,
                    namespace = namespace,
                    language = language,
                    showTitle = false,
                )
            }
            val extra = when (ns) {
                "subagent" -> listOf("subagent-model-selection-settings")
                else -> emptyList()
            }
            extra.forEach { extraNs ->
                val extraNamespace = state.description?.namespace(extraNs)
                if (extraNamespace != null) {
                    item {
                        EngineNamespaceSection(
                            viewModel = viewModel,
                            namespace = extraNamespace,
                            language = language,
                        )
                    }
                }
            }
            item {
                SettingsHint(stringResource(R.string.settings_engine_revision_one, namespace.revision))
            }
        }
    }
}
