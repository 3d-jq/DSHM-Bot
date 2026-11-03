package com.dshm.bot.ui.settings

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm

/**
 * 联网搜索（对齐 dsh-Go `WebSearchSettingsPage`）：
 * 开关在 DeepSeek Messages 搜索和 Search / Fetch API 之间切换，
 * 下面的表单只渲染当前选中那套配置。
 */
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
                if (switchable) {
                    item {
                        SettingsSection(
                            title = stringResource(R.string.settings_web_search_service),
                            footer = stringResource(R.string.settings_web_search_switch_hint),
                        ) {
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
                    }
                }

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
                    )
                }

                if (web != null) {
                    item {
                        val search = web.valueOf(listOf("searchProvider"))?.toString().orEmpty()
                        val fetch = web.valueOf(listOf("fetchProvider"))?.toString().orEmpty()
                        SettingsHint(
                            stringResource(
                                R.string.settings_web_search_current,
                                search.ifBlank { "—" },
                                fetch.ifBlank { "—" },
                            ),
                        )
                    }
                }
            }
        }
    }
}
