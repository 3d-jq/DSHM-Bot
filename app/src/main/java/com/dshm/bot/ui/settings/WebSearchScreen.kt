package com.dshm.bot.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm

/**
 * 联网搜索，对齐 dsh-Go `WebSearchSettingsPage`：
 * 一张无标题的开关卡（写 `web.searchProvider/fetchProvider`），
 * 一张「DeepSeek 搜索」卡只渲染当前模式需要的 apiKey / baseURL / 次数三个字段。
 */
@Composable
fun WebSearchScreen(viewModel: EngineSettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val language = settingsLanguage()
    val web = state.description?.namespace("web")
    val messages = state.description?.namespace("web-search-deepseek")
    val api = state.description?.namespace("web-deepseek-api")
    val switchable = web != null && messages != null && api != null
    val useApi = switchable && web?.valueOf(listOf("searchProvider")) == "deepseek-search-api"
    val active = if (useApi) api else messages
    val switchError = listOf("searchProvider", "fetchProvider")
        .firstNotNullOfOrNull { viewModel.fieldError("web", listOf(it)) }

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
                // dsh-Go 的第一组没有标题：只有一行开关（和字段错误 / 锁定提示）。
                if (switchable) {
                    item {
                        SettingsSection {
                            SettingsSwitchRow(
                                title = stringResource(R.string.settings_web_search_new_api),
                                subtitle = switchError ?: stringResource(
                                    R.string.settings_web_search_switch_hint,
                                ),
                                subtitleColor = if (switchError != null) {
                                    Dshm.colors.danger
                                } else {
                                    null
                                },
                                checked = useApi,
                                enabled = web?.writable == true &&
                                    !viewModel.isSaving("web", listOf("searchProvider")) &&
                                    !viewModel.isSaving("web", listOf("fetchProvider")),
                                onCheckedChange = { viewModel.setWebSearchApi(it) },
                            )
                        }
                    }
                }

                // dsh-Go 的第二组：标题固定「DeepSeek 搜索」，只列当前模式三个字段。
                item {
                    EngineNamespaceSection(
                        viewModel = viewModel,
                        namespace = active,
                        language = language,
                        title = stringResource(R.string.settings_web_search_deepseek),
                        singleCard = true,
                        fieldPaths = listOf(
                            listOf("apiKey"),
                            listOf("baseURL"),
                            listOf(if (useApi) "count" else "maxUses"),
                        ),
                        ellipsizeValuePaths = setOf(listOf("baseURL")),
                    )
                }
            }
        }
    }
}
