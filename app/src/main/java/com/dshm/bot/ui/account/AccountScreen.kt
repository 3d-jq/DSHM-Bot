package com.dshm.bot.ui.account

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
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
import com.dshm.bot.ui.settings.SettingsRowDivider
import com.dshm.bot.ui.settings.SettingsSection

/** 账号页（对齐 dsh-Go account_page）：资料头 + 余额 + API Key + 充值/用量 + 退出登录。 */
@Composable
fun AccountScreen(viewModel: AccountViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var showKeyDialog by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf("") }
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    LaunchedEffect(ui.authorizeUrl) {
        val url = ui.authorizeUrl ?: return@LaunchedEffect
        openUrl(context, url)
        viewModel.consumeAuthorizeUrl()
    }

    SettingsPage(title = stringResource(R.string.settings_account), onBack = onBack) {
        item {
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
                            text = ui.name ?: stringResource(R.string.account_logged_out),
                            style = Dshm.typography.sheetTitle,
                            color = Dshm.colors.textPrimary,
                        )
                        Text(
                            text = when {
                                ui.balance != null -> stringResource(R.string.account_balance, ui.balance.orEmpty())
                                ui.name == null -> stringResource(R.string.account_login_hint)
                                else -> stringResource(R.string.account_signed_in)
                            },
                            style = Dshm.typography.rowPreview,
                            color = Dshm.colors.textSecondary,
                        )
                    }
                }
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.api_key_title)) {
                SettingsRow(
                    title = stringResource(R.string.api_key_title),
                    subtitle = stringResource(R.string.api_key_hint),
                    value = if (ui.hasApiKey) ui.maskedApiKey else stringResource(R.string.api_key_not_set),
                    trailing = {
                        TextButton(onClick = {
                            keyDraft = ""
                            showKeyDialog = true
                        }) {
                            Text(
                                stringResource(if (ui.hasApiKey) R.string.api_key_replace else R.string.api_key_set),
                                color = Dshm.colors.accent,
                            )
                        }
                    },
                )
                if (ui.hasApiKey) {
                    SettingsRowDivider()
                    SettingsRow(
                        title = stringResource(R.string.api_key_clear),
                        onClick = { viewModel.clearApiKey() },
                    )
                }
            }
        }

        item {
            if (ui.name == null) {
                SettingsPrimaryButton(
                    text = stringResource(if (ui.busy) R.string.account_signing_in else R.string.account_login),
                    enabled = !ui.busy && ui.ready,
                    onClick = viewModel::signIn,
                )
            } else {
                SettingsSection {
                    if (ui.topUpUrl != null) {
                        SettingsRow(
                            title = stringResource(R.string.account_top_up),
                            onClick = { openUrl(context, ui.topUpUrl.orEmpty()) },
                        )
                    }
                    if (ui.usageUrl != null) {
                        if (ui.topUpUrl != null) SettingsRowDivider()
                        SettingsRow(
                            title = stringResource(R.string.account_usage),
                            onClick = { openUrl(context, ui.usageUrl.orEmpty()) },
                        )
                    }
                    SettingsRowDivider()
                    SettingsRow(
                        title = stringResource(R.string.account_sign_out),
                        onClick = viewModel::signOut,
                    )
                }
            }
        }

        if (ui.error != null) {
            item { SettingsError(ui.error.orEmpty()) }
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
                text = stringResource(R.string.api_key_title),
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

private fun openUrl(context: Context, url: String) {
    if (url.isBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
