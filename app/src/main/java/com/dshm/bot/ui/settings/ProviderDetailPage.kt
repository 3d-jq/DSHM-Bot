package com.dshm.bot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.ModelDefaults
import com.dshm.bot.data.remote.ProviderHealth
import com.dshm.bot.data.remote.ProviderHealthEvaluator
import com.dshm.bot.data.remote.ProviderModelSpec
import com.dshm.bot.data.remote.ProviderProfile
import com.dshm.bot.data.remote.deriveProviderKeyRef
import com.dshm.bot.ui.components.FloatingIconButton

/**
 * 渠道详情页，对齐 dsh-Go `app/lib/ui/providers/provider_detail_page.dart`：
 * Hero（头像 + 状态 + 统计）→ 连接卡（Base URL / API Key / 测试连接）→ 模型卡 → 居中删除渠道。
 *
 * 只读为主：编辑与删除都在这里触发，实际写入仍走引擎的 settings/mutate。
 */
@Composable
fun ProviderDetailPage(
    viewModel: EngineSettingsViewModel,
    route: String,
    onBack: () -> Unit,
    onEdit: (ProviderProfile?) -> Unit,
    onOpenModel: (ProviderModelSpec) -> Unit,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    // 按 route 现取渠道，模型设置页写完引擎后这一页立刻能看到新值。
    val profile = viewModel.providers().profiles.firstOrNull { it.route == route }
    val keyRef = profile?.apiKeyEnv ?: deriveProviderKeyRef(route)
    val keyStored = state.credentials[keyRef]?.configured == true
    var testing by remember { mutableStateOf(false) }
    var testPassed by remember { mutableStateOf<Int?>(null) }
    var testFailed by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    if (profile == null) {
        // 渠道被删掉了（或者引擎还没读回来）：回列表，不留一个空壳页面。
        SettingsPage(
            title = stringResource(R.string.settings_model),
            onBack = onBack,
        ) {
            item { SettingsHint(stringResource(R.string.settings_model_empty)) }
        }
        return
    }

    val health = ProviderHealthEvaluator.evaluate(profile, keyStored)

    SettingsPage(
        title = profile.label,
        onBack = onBack,
        actions = {
            // dsh-Go：顶栏右侧是一个图标按钮（铅笔），不是文字链接。
            FloatingIconButton(
                icon = R.drawable.ic_lucide_pencil,
                contentDescription = stringResource(R.string.settings_model_detail_edit),
                onClick = { onEdit(profile) },
                enabled = !deleting,
            )
        },
    ) {
        item {
            // dsh-Go 的 PageHero 是卡片样式，这里也包一层卡。
            SettingsSection {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Monogram(profile.label, size = Dshm.spacing.settingsAvatar)
                    Spacer(Modifier.width(Dshm.spacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = profile.label,
                            style = Dshm.typography.sheetTitle,
                            color = Dshm.colors.textPrimary,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(health.toStatusTone())
                            Spacer(Modifier.width(Dshm.spacing.xs))
                            Text(
                                text = healthLabel(health),
                                style = Dshm.typography.settingsRowSubtitle,
                                color = Dshm.colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }

        if (health == ProviderHealth.MISSING_KEY) {
            item {
                SettingsPrimaryButton(
                    text = stringResource(R.string.settings_model_detail_enter_key),
                    onClick = { onEdit(profile) },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_model_detail_connection)) {
                SettingsRow(
                    title = stringResource(R.string.settings_model_detail_base_url),
                    icon = R.drawable.ic_lucide_server,
                    subtitle = profile.baseUrl?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.settings_value_default),
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_model_detail_api_key),
                    icon = R.drawable.ic_lucide_shield,
                    value = if (keyStored) {
                        stringResource(R.string.settings_model_detail_api_key_saved)
                    } else {
                        stringResource(R.string.settings_model_detail_api_key_missing)
                    },
                    onClick = { onEdit(profile) },
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_model_test_connection),
                    icon = R.drawable.ic_lucide_refresh_cw,
                    subtitle = when {
                        testing -> stringResource(R.string.action_loading)
                        testFailed != null -> testFailed
                        testPassed != null -> stringResource(R.string.settings_model_fetched, testPassed ?: 0)
                        else -> stringResource(R.string.settings_model_fetch_hint)
                    },
                    subtitleColor = when {
                        testFailed != null -> Dshm.colors.danger
                        testPassed != null -> Dshm.colors.success
                        else -> null
                    },
                    enabled = !testing,
                    trailing = if (testing) {
                        {
                            CircularProgressIndicator(
                                modifier = Modifier.size(Dshm.spacing.topBarIcon),
                                strokeWidth = Dshm.spacing.hairline * 2,
                                color = Dshm.colors.textTertiary,
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        testing = true
                        testPassed = null
                        testFailed = null
                        // 明文 Key 不存在 App 侧，交给引擎用它已保存的凭据去读模型列表。
                        viewModel.discoverModels(
                            baseUrl = profile.baseUrl.orEmpty(),
                            api = profile.api.orEmpty(),
                            apiKey = "",
                            route = profile.route,
                        ) { ids, failure, _ ->
                            testing = false
                            if (failure != null) {
                                testFailed = failure
                            } else {
                                testPassed = ids?.size ?: 0
                            }
                        }
                    },
                )
            }
        }

        item {
            SettingsSection(
                title = stringResource(R.string.settings_model_detail_models, profile.models.size),
            ) {
                if (profile.models.isEmpty()) {
                    SettingsRow(
                        title = stringResource(R.string.settings_model_detail_no_models),
                        icon = R.drawable.ic_lucide_bot,
                        enabled = false,
                    )
                } else {
                    profile.models.forEachIndexed { index, model ->
                        if (index > 0) SettingsRowDivider()
                        SettingsRow(
                            title = model.label,
                            icon = R.drawable.ic_lucide_bot,
                            subtitle = providerModelSummary(
                                model,
                                ModelDefaults.of(profile),
                            ).takeIf { it.isNotEmpty() },
                            onClick = { onOpenModel(model) },
                        )
                    }
                }
            }
        }

        item {
            // 删除渠道单独一张卡，按钮居中（和其它卡片同一套圆角与留白）。
            SettingsSection {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(Dshm.spacing.sm))
                    SettingsDangerButton(
                        text = stringResource(R.string.settings_model_delete),
                        icon = R.drawable.ic_lucide_trash_2,
                        enabled = !deleting,
                        onClick = { confirmDelete = true },
                    )
                    Spacer(Modifier.height(Dshm.spacing.sm))
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Dshm.colors.surface,
            title = {
                Text(
                    text = stringResource(R.string.settings_model_delete),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.settings_model_delete_confirm, profile.label),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleting = true
                    viewModel.deleteProvider(profile.route) { failure ->
                        deleting = false
                        if (failure == null) onBack()
                    }
                }) {
                    Text(stringResource(R.string.action_delete), color = Dshm.colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel), color = Dshm.colors.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun healthLabel(health: ProviderHealth): String = when (health) {
    ProviderHealth.READY -> stringResource(R.string.settings_model_health_ready)
    ProviderHealth.MISSING_KEY -> stringResource(R.string.settings_model_health_missing_key)
    ProviderHealth.MISCONFIGURED -> stringResource(R.string.settings_model_health_misconfigured)
    ProviderHealth.DISABLED -> stringResource(R.string.settings_model_health_disabled)
}
