package com.dshm.bot.ui.settings

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm

// 联网搜索（对齐 dsh-Go `WebSearchSettingsPage`）：
// 搜索服务在 DeepSeek Messages 搜索和 Search / Fetch API 之间切换，
// 表单渲染当前选中那套配置，预算等未接入能力明确标注不可用。
@Composable
fun WebSearchScreen(viewModel: EngineSettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val language = settingsLanguage()
    val web = state.description?.namespace("web")
    val messages = state.description?.namespace("web-search-deepseek")
    val api = state.description?.namespace("web-deepseek-api")
    val switchable = web != null && messages != null && api != null
    val useApi = web?.valueOf(listOf("searchProvider")) == "deepseek-search-api"
    val active = when {
        switchable -> if (useApi) api else messages
        api != null -> api
        messages != null -> messages
        else -> null
    }
    val switchError = viewModel.fieldError("web", listOf("searchProvider"))

    SettingsPage(
        title = stringResource(R.string.settings_web_search),
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
            active == null -> item { SettingsEmpty(stringResource(R.string.info_engine_required)) }
            else -> {

                // 搜索服务：新版搜索 API 开关、字段错误与当前 provider 状态
                if (switchable || web != null) {
                    item {
                        SettingsSection(
                            title = stringResource(R.string.settings_web_search_service),
                            footer = if (switchable) {
                                stringResource(R.string.settings_web_search_switch_hint)
                            } else {
                                null
                            },
                        ) {
                            if (switchable) {
                                SettingsSwitchRow(
                                    title = stringResource(R.string.settings_web_search_api),
                                    subtitle = if (useApi) {
                                        stringResource(R.string.settings_web_search_api_on)
                                    } else {
                                        stringResource(R.string.settings_web_search_api_off)
                                    },
                                    checked = useApi,
                                    enabled = web?.writable == true,
                                    onCheckedChange = { viewModel.setWebSearchApi(it) },
                                )
                                if (switchError != null) {
                                    SettingsRowDivider()
                                    SettingsError(switchError)
                                }
                            }
                            if (switchable) SettingsRowDivider()
                            val search = web?.valueOf(listOf("searchProvider"))?.toString().orEmpty()
                            val fetch = web?.valueOf(listOf("fetchProvider"))?.toString().orEmpty()
                            SettingsRow(
                                title = stringResource(R.string.settings_web_search_current_label),
                                subtitle = stringResource(
                                    R.string.settings_web_search_current,
                                    search.ifBlank { "—" },
                                    fetch.ifBlank { "—" },
                                ),
                            )
                        }
                    }
                }

                // API Key / 服务地址 / 每次最多搜索数：引擎 schema 的真实可编辑字段
                item {
                    EngineNamespaceSection(
                        viewModel = viewModel,
                        namespace = active,
                        language = language,
                        title = if (useApi || messages == null) {
                            stringResource(R.string.settings_web_search_api_config)
                        } else {
                            stringResource(R.string.settings_web_search_messages_config)
                        },
                        footer = stringResource(R.string.settings_engine_required),
                    )
                }

                // 预算等进阶能力：dsh-Go 有、当前版本未接入，明确标注不可用
                item {
                    SettingsSection(title = stringResource(R.string.settings_web_search_budget_section)) {
                        SettingsRow(
                            title = stringResource(R.string.settings_web_search_budget),
                            enabled = false,
                            subtitle = stringResource(R.string.settings_unavailable_hint),
                            value = stringResource(R.string.settings_unavailable),
                        )
                    }
                }
            }
        }
    }
}
