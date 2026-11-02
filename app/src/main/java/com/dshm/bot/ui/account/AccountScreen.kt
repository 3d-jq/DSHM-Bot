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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.runtime.RuntimeEndpoint
import com.dshm.bot.ui.settings.SettingsPageScaffold

/** 账号页：接引擎 account 服务；未登录引导登录，已登录显示昵称并可退出。 */
@Composable
fun AccountScreen(endpoint: RuntimeEndpoint?, onBack: () -> Unit) {
    SettingsPageScaffold(title = stringResource(R.string.settings_account), onBack = onBack) {
        if (endpoint == null) {
            Text(
                text = stringResource(R.string.account_engine_required),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
            )
        } else {
            AccountContent(endpoint)
        }
    }
}

@Composable
private fun AccountContent(endpoint: RuntimeEndpoint) {
    val context = LocalContext.current
    val viewModel: AccountViewModel = viewModel(
        key = endpoint.baseUrl,
        factory = AccountViewModel.factory(endpoint),
    )
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    LaunchedEffect(ui.authorizeUrl) {
        val url = ui.authorizeUrl ?: return@LaunchedEffect
        openAuthorizePage(context, url)
        viewModel.consumeAuthorizeUrl()
    }

    Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.md)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dshm.radius.lg))
                .background(Dshm.colors.surface)
                .padding(Dshm.spacing.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(Dshm.spacing.avatar)
                        .clip(CircleShape)
                        .background(Dshm.colors.surfaceMuted),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = (ui.name ?: "?").trim().take(1).uppercase(),
                        style = Dshm.typography.rowTitle,
                        color = Dshm.colors.textSecondary,
                    )
                }
                Spacer(Modifier.padding(Dshm.spacing.sm))
                Column {
                    Text(
                        text = ui.name ?: stringResource(R.string.account_logged_out),
                        style = Dshm.typography.rowTitle,
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
        if (ui.name == null) {
            Button(
                onClick = viewModel::signIn,
                enabled = !ui.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (ui.busy) R.string.account_signing_in else R.string.account_login))
            }
        } else {
            Button(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.account_sign_out))
            }
        }
    }
}

/** 用 Custom Tabs / 浏览器打开授权页（OAuth 不放进 App 内 WebView）。 */
private fun openAuthorizePage(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

