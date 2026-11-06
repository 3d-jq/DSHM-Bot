package com.dshm.bot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.modelCandidates
import com.dshm.bot.data.remote.routesJson
import com.dshm.bot.data.remote.routesOf

/**
 * 「子代理」页，对齐 dsh-Go `app/lib/ui/engine_settings/subagent_settings_page.dart`：
 *
 * - **运行限制**：最大递归深度、同时运行上限（走通用 schema 表单，数字有编辑弹层）；
 * - **模型选择**：一个开关 + 引擎模型目录里的可选模型（按渠道分组，单选圆点标记）。
 *
 * 两个约束跟 dsh-Go 一样：
 * 1. 打开开关必须同时有至少一个模型 —— 没选模型时开关先「挂着」，
 *    选到第一个模型时把「打开」和「模型列表」一起写出去，引擎才不拒绝；
 * 2. 已经打开时不能删光最后一个模型。
 */
@Composable
fun SubagentSettingsScreen(
    viewModel: EngineSettingsViewModel,
    onBack: () -> Unit,
    onOpenProviders: () -> Unit = {},
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val language = settingsLanguage()
    val limits = state.description?.namespace(LIMITS_NS)
    val models = state.description?.namespace(MODELS_NS)

    LaunchedEffect(Unit) { viewModel.refreshModelCatalog() }

    if (limits == null && models == null) {
        SettingsPage(
            title = stringResource(R.string.settings_subagent_title),
            onBack = onBack,
        ) {
            item { SettingsEmpty(stringResource(R.string.settings_engine_required)) }
        }
        return
    }

    val enabledValue = models?.valueOf(listOf("enabled")) == true
    val allowed = models?.valueOf(listOf("allowedModels")).let { routesOf(it) }
    // 开关拨到「开」但还没选模型时，先记在本地，等第一个模型一起写。
    var pendingOn by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val on = enabledValue || pendingOn
    val writable = models?.writable != false

    // 文案要在 Composable 作用域取好：点击回调里不能再调 stringResource。
    val keepOneText = stringResource(R.string.settings_subagent_keep_one)
    val unavailableText = stringResource(R.string.settings_subagent_unavailable)
    // remember 必须在 Composable 作用域：SettingsPage 的内容 lambda 是 LazyListScope。
    val catalog = state.modelCatalog
    val groups = remember(catalog, allowed) { modelCandidates(catalog, allowed) }

    SettingsPage(
        title = stringResource(R.string.settings_subagent_title),
        onBack = onBack,
    ) {
        if (limits != null) {
            item {
                EngineNamespaceSection(
                    viewModel = viewModel,
                    namespace = limits,
                    language = language,
                    title = stringResource(R.string.settings_subagent_limits),
                    showTitle = true,
                    singleCard = true,
                )
            }
        }

        if (models != null) {
            item {
                SettingsSection(
                    title = stringResource(R.string.settings_subagent_model_selection),
                    footer = note ?: if (pendingOn && allowed.isEmpty()) {
                        stringResource(R.string.settings_subagent_pick_first)
                    } else {
                        null
                    },
                ) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_subagent_allow_choice),
                        checked = on,
                        enabled = writable,
                        onCheckedChange = { picked ->
                            note = null
                            if (picked && allowed.isEmpty()) {
                                pendingOn = true
                                return@SettingsSwitchRow
                            }
                            if (!picked && pendingOn) {
                                pendingOn = false
                                if (!enabledValue) return@SettingsSwitchRow
                            }
                            viewModel.setValue(MODELS_NS, listOf("enabled"), picked)
                        },
                    )
                }
            }

            if (on) {
                if (state.modelCatalogError != null && catalog == null) {
                    item {
                        SettingsSection {
                            SettingsRow(
                                title = stringResource(R.string.settings_subagent_read_failed),
                                value = stringResource(R.string.action_retry),
                                trailing = {},
                                onClick = { viewModel.refreshModelCatalog() },
                            )
                        }
                    }
                } else if (groups.isEmpty()) {
                    item {
                        SettingsSection {
                            SettingsRow(
                                title = stringResource(R.string.settings_subagent_no_models),
                                value = stringResource(R.string.settings_subagent_connect_model),
                                trailing = {},
                                onClick = onOpenProviders,
                            )
                        }
                    }
                } else {
                    groups.forEach { (groupName, modelsInGroup) ->
                        item {
                            SettingsSection(title = groupName) {
                                modelsInGroup.forEachIndexed { index, candidate ->
                                    if (index > 0) SettingsRowDivider()
                                    val selected = candidate.route in allowed
                                    SettingsRow(
                                        title = candidate.name,
                                        subtitle = when {
                                            !candidate.available -> {
                                                "${candidate.providerName} · $unavailableText"
                                            }
                                            candidate.name != candidate.route.model -> candidate.route.model
                                            else -> null
                                        },
                                        trailing = {
                                            Box(
                                                modifier = Modifier
                                                    .size(Dshm.spacing.selectionTick)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (selected) {
                                                            Dshm.colors.accent
                                                        } else {
                                                            Dshm.colors.textTertiary
                                                        },
                                                    ),
                                            )
                                        },
                                        enabled = writable,
                                        onClick = {
                                            val current = routesOf(models.valueOf(listOf("allowedModels")))
                                            val next = if (selected) {
                                                if (current.size == 1 && enabledValue) {
                                                    note = keepOneText
                                                    return@SettingsRow
                                                }
                                                current - candidate.route
                                            } else {
                                                current + candidate.route
                                            }
                                            note = null
                                            if (pendingOn && !enabledValue) {
                                                // 开关和模型一起写，引擎才认「开了但没模型」这个状态。
                                                pendingOn = false
                                                viewModel.setValues(
                                                    MODELS_NS,
                                                    listOf(
                                                        listOf("enabled") to true,
                                                        listOf("allowedModels") to routesJson(next),
                                                    ),
                                                )
                                            } else {
                                                viewModel.setValue(
                                                    MODELS_NS,
                                                    listOf("allowedModels"),
                                                    routesJson(next),
                                                )
                                            }
                                        },
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

private const val LIMITS_NS = "subagent"
private const val MODELS_NS = "subagent-model-selection-settings"
