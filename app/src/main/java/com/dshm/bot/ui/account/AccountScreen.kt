package com.dshm.bot.ui.account

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
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
import com.dshm.bot.ui.settings.SettingsPageScaffold

/** 账号页（对齐 dsh-Go account_page）：资料头 + 余额 + 充值/用量 + 退出登录。 */
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

    SettingsPageScaffold(title = stringResource(R.string.settings_account), onBack = onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.md)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Dshm.radius.lg))
                    .background(Dshm.colors.surface)
                    .padding(Dshm.spacing.lg),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AccountAvatar(name = ui.name, avatarUrl = ui.avatarUrl)
                    Spacer(Modifier.width(Dshm.spacing.md))
                    Column {
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
                ui.error?.let { error ->
                    Spacer(Modifier.padding(Dshm.spacing.xs))
                    Text(error, style = Dshm.typography.rowPreview, color = Dshm.colors.danger)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Dshm.radius.lg))
                    .background(Dshm.colors.surface)
                    .padding(Dshm.spacing.lg),
            ) {
                Text(
                    text = stringResource(R.string.api_key_title),
                    style = Dshm.typography.rowTitle,
                    color = Dshm.colors.textPrimary,
                )
                Spacer(Modifier.padding(Dshm.spacing.xxs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (ui.hasApiKey) ui.maskedApiKey else stringResource(R.string.api_key_not_set),
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        keyDraft = ""
                        showKeyDialog = true
                    }) {
                        Text(stringResource(if (ui.hasApiKey) R.string.api_key_replace else R.string.api_key_set))
                    }
                }
            }

            if (showKeyDialog) {
                AlertDialog(
                    onDismissRequest = { showKeyDialog = false },
                    containerColor = Dshm.colors.surface,
                    title = { Text(stringResource(R.string.api_key_title), color = Dshm.colors.textPrimary) },
                    text = {
                        Column {
                            Text(
                                text = stringResource(R.string.api_key_hint),
                                style = Dshm.typography.rowPreview,
                                color = Dshm.colors.textSecondary,
                            )
                            Spacer(Modifier.padding(Dshm.spacing.xs))
                            BasicTextField(
                                value = keyDraft,
                                onValueChange = { keyDraft = it },
                                singleLine = true,
                                textStyle = Dshm.typography.rowPreview.copy(color = Dshm.colors.textPrimary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(Dshm.radius.sm))
                                    .background(Dshm.colors.surfaceMuted)
                                    .padding(Dshm.spacing.sm),
                            )
                            if (ui.hasApiKey) {
                                TextButton(onClick = {
                                    viewModel.clearApiKey()
                                    showKeyDialog = false
                                }) {
                                    Text(stringResource(R.string.api_key_clear), color = Dshm.colors.danger)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (keyDraft.isNotBlank()) viewModel.saveApiKey(keyDraft)
                                showKeyDialog = false
                            },
                        ) { Text(stringResource(R.string.api_key_save)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showKeyDialog = false }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    },
                )
            }

            if (ui.name == null) {
                Button(
                    onClick = viewModel::signIn,
                    enabled = !ui.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(if (ui.busy) R.string.account_signing_in else R.string.account_login))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    if (ui.topUpUrl != null) {
                        Button(
                            onClick = { openUrl(context, ui.topUpUrl.orEmpty()) },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.account_top_up)) }
                    }
                    if (ui.usageUrl != null) {
                        OutlinedButton(
                            onClick = { openUrl(context, ui.usageUrl.orEmpty()) },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.account_usage)) }
                    }
                }
                OutlinedButton(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.account_sign_out))
                }
            }

            if (!ui.ready) {
                Text(
                    text = stringResource(R.string.account_engine_required),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textTertiary,
                )
            }
        }
    }
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

