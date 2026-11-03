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

/**
 * 引擎设置 Hub（对齐 dsh-Go `EngineSettingsHubPage`）：
 * 子代理 / Agent 循环 / 命令执行 → 打开 schema 表单；
 * 插件清单与插件自带设置 → 各自的 namespace。
 */
@Composable
fun EngineSettingsScreen(
    viewModel: EngineSettingsViewModel,
    onBack: () -> Unit,
    onOpenPlugins: () -> Unit = {},
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val language = settingsLanguage()
    var openNs by rememberSaveable { mutableStateOf<String?>(null) }

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
                SettingsEmpty(stringResource(R.string.info_engine_required))
            }
            else -> {
                item {
                    val subagent = viewModel.namespace("subagent")
                    val agentLoop = viewModel.namespace("agent-loop")
                    val shellNs = viewModel.shellNamespace()
                    val shell = shellNs?.let { viewModel.namespace(it) }
                    var first = true
                    SettingsSection(title = stringResource(R.string.engine_settings_run)) {
                        if (subagent != null) {
                            SettingsRow(
                                title = SettingsCopy.namespaceTitle("subagent", language),
                                icon = R.drawable.ic_lucide_users,
                                value = engineValueText(subagent, listOf("maxDepth"), language),
                                onClick = { openNs = "subagent" },
                            )
                            first = false
                        }
                        if (agentLoop != null) {
                            if (!first) SettingsRowDivider()
                            SettingsRow(
                                title = SettingsCopy.namespaceTitle("agent-loop", language),
                                icon = R.drawable.ic_lucide_code,
                                value = engineValueText(agentLoop, listOf("maxParallelToolCalls"), language),
                                onClick = { openNs = "agent-loop" },
                            )
                            first = false
                        }
                        if (shellNs != null) {
                            if (!first) SettingsRowDivider()
                            SettingsRow(
                                title = SettingsCopy.namespaceTitle(shellNs, language),
                                icon = R.drawable.ic_lucide_sliders_horizontal,
                                value = engineValueText(shell, listOf("timeoutMs"), language),
                                onClick = { openNs = shellNs },
                            )
                        }
                    }
                }

                item {
                    val plugins = state.plugins?.entries.orEmpty()
                    val configs = viewModel.pluginConfigNamespaces()
                    SettingsSection(
                        title = stringResource(R.string.engine_settings_plugins),
                        footer = if (state.plugins?.managementAvailable == false) {
                            stringResource(R.string.settings_plugins_read_only)
                        } else {
                            null
                        },
                    ) {
                        SettingsRow(
                            title = stringResource(R.string.settings_plugin_inventory),
                            icon = R.drawable.ic_lucide_puzzle,
                            value = stringResource(R.string.settings_plugin_count, plugins.size),
                            onClick = onOpenPlugins,
                        )
                        configs.forEach { ns ->
                            SettingsRowDivider()
                            SettingsRow(
                                title = SettingsCopy.namespaceTitle(ns.ns, language),
                                icon = R.drawable.ic_lucide_puzzle,
                                subtitle = ns.schema.meta.description?.resolve(language),
                                onClick = { openNs = ns.ns },
                            )
                        }
                    }
                }

                item {
                    SettingsHint(
                        stringResource(
                            R.string.settings_engine_revision,
                            state.description?.namespaces?.size ?: 0,
                        ),
                    )
                }
            }
        }
    }
}

/** 单个 namespace 的 schema 表单页（引擎 Hub / 插件页共用）。 */
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
