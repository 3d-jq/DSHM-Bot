package com.dshm.bot.ui.account

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.ui.settings.SettingsError
import com.dshm.bot.ui.settings.SettingsHint
import com.dshm.bot.ui.settings.SettingsInput
import com.dshm.bot.ui.settings.SettingsPage
import com.dshm.bot.ui.settings.SettingsPrimaryButton
import com.dshm.bot.ui.settings.SettingsRow
import com.dshm.bot.ui.settings.SettingsSection

/**
 * 账号页：按 dsh-Go `app/lib/ui/account/account_page.dart` 的信息架构重做，
 * 配色仍走本项目黑白灰设计令牌。
 *
 * 未登录：账号主卡（图标 + 标题 + 副标题 + 三条带图标说明 + 卡内深色主按钮）；
 * 登录中：同一张卡内切成阶段提示 + 取消登录；
 * 已登录：账号概览 + 余额分组（充值/用量并排按钮）+ 退出登录（带任务检查确认）。
 * API Key 在两种状态下都是「分组标题 + 单行入口」，点开才编辑。
 */
@Composable
fun AccountScreen(viewModel: AccountViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var showKeyDialog by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf("") }

    LaunchedEffect(ui.authorizeUrl) {
        val url = ui.authorizeUrl ?: return@LaunchedEffect
        openUrl(context, url)
        viewModel.consumeAuthorizeUrl()
    }

    SettingsPage(title = stringResource(R.string.settings_account), onBack = onBack) {
        item {
            if (ui.signedIn) {
                SignedInCard(ui = ui, onTopUp = { openUrl(context, ui.topUpUrl) }, onUsage = { openUrl(context, ui.usageUrl) })
            } else {
                SignInCard(
                    ui = ui,
                    onSignIn = viewModel::signIn,
                    onCancel = viewModel::cancelSignIn,
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.account_api_key_group)) {
                SettingsRow(
                    title = stringResource(R.string.account_api_key_row),
                    icon = R.drawable.ic_lucide_shield,
                    value = if (ui.hasApiKey) ui.maskedApiKey else stringResource(R.string.api_key_not_set),
                    onClick = {
                        keyDraft = ""
                        showKeyDialog = true
                    },
                )
            }
        }

        if (ui.signedIn) {
            item {
                SettingsSection {
                    // dsh-Go 是居中的 TextButton.icon：图标 + 文字一起居中，且没有右侧箭头。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Dshm.spacing.iconButton)
                            .clickable(onClick = viewModel::requestSignOut)
                            .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_lucide_x),
                            contentDescription = null,
                            tint = Dshm.colors.danger,
                            modifier = Modifier.size(Dshm.spacing.topBarIcon),
                        )
                        Spacer(Modifier.width(Dshm.spacing.sm))
                        Text(
                            text = stringResource(R.string.account_sign_out),
                            style = Dshm.typography.settingsRowTitle,
                            color = Dshm.colors.danger,
                        )
                    }
                }
            }
        }

        if (ui.error != null || ui.errorCode != null) {
            item {
                SettingsError(
                    ui.error ?: stringResource(accountErrorTextRes(ui.errorCode.orEmpty())),
                )
            }
        }
        if (!ui.ready) {
            item { SettingsHint(stringResource(R.string.account_engine_required)) }
        }
    }

    if (showKeyDialog) {
        KeyDialog(
            hasKey = ui.hasApiKey,
            value = keyDraft,
            onValueChange = { keyDraft = it },
            onDismiss = { showKeyDialog = false },
            onSave = {
                if (keyDraft.isNotBlank()) viewModel.saveApiKey(keyDraft)
                showKeyDialog = false
            },
            onClear = {
                viewModel.clearApiKey()
                showKeyDialog = false
            },
        )
    }

    if (ui.pendingSignOut) {
        AlertDialog(
            onDismissRequest = viewModel::cancelSignOut,
            containerColor = Dshm.colors.surface,
            title = {
                Text(
                    text = stringResource(R.string.account_sign_out_confirm_title),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
            },
            text = {
                Text(
                    text = stringResource(
                        if (ui.signOutBusy) {
                            R.string.account_sign_out_confirm_busy
                        } else {
                            R.string.account_sign_out_confirm_body
                        },
                    ),
                    style = Dshm.typography.settingsRowSubtitle,
                    color = Dshm.colors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmSignOut) {
                    Text(stringResource(R.string.account_sign_out), color = Dshm.colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelSignOut) {
                    Text(stringResource(android.R.string.cancel), color = Dshm.colors.textSecondary)
                }
            },
        )
    }
}

/** 未登录 / 登录中的主卡：dsh-Go 的三段式（头部、说明或阶段、卡内主按钮）。 */
@Composable
private fun SignInCard(
    ui: AccountViewModel.UiState,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
) {
    SettingsSection {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Dshm.spacing.lg, end = Dshm.spacing.lg, top = Dshm.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(Dshm.spacing.settingsAvatar)
                    .clip(CircleShape)
                    .background(Dshm.colors.surfaceMuted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_shield),
                    contentDescription = null,
                    tint = Dshm.colors.iconTint,
                    modifier = Modifier.size(Dshm.spacing.icon),
                )
            }
            Spacer(Modifier.width(Dshm.spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.account_deepseek_title),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
                Text(
                    text = stringResource(R.string.account_login_subtitle),
                    style = Dshm.typography.settingsRowSubtitle,
                    color = Dshm.colors.textSecondary,
                )
            }
        }

        // dsh-Go：头部到说明 12dp、说明到按钮 16dp，卡内不画分割线。
        Spacer(Modifier.height(Dshm.spacing.md))

        if (ui.busy) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dshm.spacing.lg),
            ) {
                Text(
                    text = stringResource(stageTitle(ui.stage)),
                    style = Dshm.typography.settingsRowTitle,
                    color = Dshm.colors.textPrimary,
                )
                Text(
                    text = stringResource(stageHint(ui.stage)),
                    style = Dshm.typography.settingsRowSubtitle,
                    color = Dshm.colors.textSecondary,
                )
            }
        } else {
            AccountBenefitRow(R.drawable.ic_lucide_gauge, stringResource(R.string.account_benefit_balance))
            AccountBenefitRow(R.drawable.ic_lucide_layout_dashboard, stringResource(R.string.account_benefit_browser))
            AccountBenefitRow(R.drawable.ic_lucide_shield, stringResource(R.string.account_benefit_local))
        }

        Spacer(Modifier.height(Dshm.spacing.lg))

        // 主按钮是卡片里独立浮着的元素，下面留 16dp（dsh-Go 的按钮下方有 16）。
        Box(
            Modifier.padding(
                start = Dshm.spacing.lg,
                end = Dshm.spacing.lg,
                bottom = Dshm.spacing.lg,
            ),
        ) {
            SettingsPrimaryButton(
                text = stringResource(
                    if (ui.busy) R.string.account_sign_in_cancel else R.string.account_sign_in,
                ),
                enabled = ui.ready,
                onClick = if (ui.busy) onCancel else onSignIn,
            )
        }

        if (!ui.ready) {
            // dsh-Go：引擎没连上时说明「打开 App 会自动启动引擎」。
            Text(
                text = stringResource(R.string.account_auto_start_hint),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textTertiary,
                modifier = Modifier.padding(
                    start = Dshm.spacing.lg,
                    end = Dshm.spacing.lg,
                    bottom = Dshm.spacing.lg,
                ),
            )
        }
    }
}

@Composable
private fun AccountBenefitRow(icon: Int, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Dshm.colors.textSecondary,
            modifier = Modifier.size(Dshm.spacing.topBarIcon),
        )
        Spacer(Modifier.width(Dshm.spacing.sm))
        Text(
            text = text,
            style = Dshm.typography.settingsRowSubtitle,
            color = Dshm.colors.textSecondary,
        )
    }
}

@Composable
private fun SignedInCard(
    ui: AccountViewModel.UiState,
    onTopUp: () -> Unit,
    onUsage: () -> Unit,
) {
    Column {
        SettingsSection {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccountAvatar(name = ui.name, avatarUrl = ui.avatarUrl)
                Spacer(Modifier.width(Dshm.spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = ui.name ?: stringResource(R.string.account_deepseek_title),
                        style = Dshm.typography.sheetTitle,
                        color = Dshm.colors.textPrimary,
                    )
                    Text(
                        text = ui.balance?.let { stringResource(R.string.account_balance, it) }
                            ?: stringResource(R.string.account_signed_in),
                        style = Dshm.typography.settingsRowSubtitle,
                        color = Dshm.colors.textSecondary,
                    )
                }
            }
        }

        // 余额分组与上方账号卡拉开距离，避免标题贴着卡片。
        Spacer(Modifier.height(Dshm.spacing.xxl))

        SettingsSection(title = stringResource(R.string.account_balance_group)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
            ) {
                BalanceStat(
                    label = stringResource(R.string.account_balance_recharge),
                    amount = ui.balance,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Dshm.spacing.lg))
                BalanceStat(
                    label = stringResource(R.string.account_balance_bonus),
                    amount = ui.bonusBalance,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.padding(
                    horizontal = Dshm.spacing.lg,
                    vertical = Dshm.spacing.md,
                ),
            ) {
                SettingsPrimaryButton(
                    text = stringResource(R.string.account_top_up),
                    enabled = ui.topUpUrl != null,
                    onClick = onTopUp,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Dshm.spacing.md))
                SettingsPrimaryButton(
                    text = stringResource(R.string.account_usage),
                    enabled = ui.usageUrl != null,
                    onClick = onUsage,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 一列余额：标签 + 金额，对齐 dsh-Go 的 _WalletStat 并排布局。 */
@Composable
private fun BalanceStat(
    label: String,
    amount: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = Dshm.typography.rowPreview,
            color = Dshm.colors.textTertiary,
        )
        Spacer(Modifier.height(Dshm.spacing.xs))
        Text(
            text = amount ?: stringResource(R.string.settings_value_not_set),
            style = Dshm.typography.pageTitle,
            color = Dshm.colors.textPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun KeyDialog(
    hasKey: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Dshm.colors.surface,
        title = {
            Text(
                text = stringResource(R.string.account_api_key_row),
                style = Dshm.typography.sheetTitle,
                color = Dshm.colors.textPrimary,
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.api_key_hint),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
                Spacer(Modifier.padding(Dshm.spacing.xs))
                SettingsInput(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = "sk-…",
                )
                if (hasKey) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.api_key_clear), color = Dshm.colors.danger)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = value.isNotBlank()) {
                Text(stringResource(R.string.api_key_save), color = Dshm.colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel), color = Dshm.colors.textSecondary)
            }
        },
    )
}

@Composable
private fun AccountAvatar(name: String?, avatarUrl: String?) {
    if (avatarUrl != null) {
        AsyncImage(
            model = avatarUrl,
            contentDescription = null,
            modifier = Modifier
                .size(Dshm.spacing.avatar)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(Dshm.spacing.avatar)
                .clip(CircleShape)
                .background(Dshm.colors.surfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = (name ?: "?").trim().take(1).uppercase(),
                style = Dshm.typography.rowTitle,
                color = Dshm.colors.textSecondary,
            )
        }
    }
}

private fun stageTitle(stage: AccountStage?): Int = when (stage) {
    AccountStage.WAITING_BROWSER -> R.string.account_stage_browser
    AccountStage.FINISHING -> R.string.account_stage_finishing
    else -> R.string.account_stage_preparing
}

private fun stageHint(stage: AccountStage?): Int = when (stage) {
    AccountStage.WAITING_BROWSER -> R.string.account_benefit_browser
    AccountStage.FINISHING -> R.string.account_benefit_local
    else -> R.string.account_engine_required
}

/** wire 的 SignInErrorCode → 文案资源 id；协议值来自 dsh-Go account_api.dart。 */
private fun accountErrorTextRes(code: String): Int = when (code) {
    "no-response" -> R.string.account_error_offline
    "network" -> R.string.account_error_service
    "protocol" -> R.string.account_error_protocol
    "expired" -> R.string.account_error_expired
    "storage" -> R.string.account_error_storage
    AccountViewModel.ERROR_TIMEOUT -> R.string.account_error_timeout
    AccountViewModel.ERROR_ENGINE -> R.string.account_error_engine
    else -> R.string.account_sign_in_failed
}

private fun openUrl(context: Context, url: String?) {
    if (url.isNullOrBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
