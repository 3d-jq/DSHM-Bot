package com.dshm.bot.ui.account

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.ui.settings.SettingsPageScaffold

/** 账号页：未登录时引导登录 DSH；登录态展示昵称/余额（接入引擎 account 后填充）。 */
@Composable
fun AccountScreen(
    loggedInName: String?,
    balanceText: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    SettingsPageScaffold(title = stringResource(R.string.settings_account), onBack = onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.md)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Dshm.radius.lg))
                    .background(Dshm.colors.surface)
                    .padding(Dshm.spacing.lg),
            ) {
                if (loggedInName == null) {
                    Text(
                        text = stringResource(R.string.account_logged_out),
                        style = Dshm.typography.rowTitle,
                        color = Dshm.colors.textPrimary,
                    )
                    Spacer(Modifier.padding(Dshm.spacing.xs))
                    Text(
                        text = stringResource(R.string.account_login_hint),
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.textSecondary,
                    )
                } else {
                    Text(
                        text = loggedInName,
                        style = Dshm.typography.sheetTitle,
                        color = Dshm.colors.textPrimary,
                    )
                    if (balanceText != null) {
                        Text(
                            text = balanceText,
                            style = Dshm.typography.rowPreview,
                            color = Dshm.colors.textSecondary,
                        )
                    }
                }
            }
            if (loggedInName == null) {
                Button(
                    onClick = { openLogin(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.account_login))
                }
            }
        }
    }
}

/** 打开 DSH / DeepSeek 登录页；Custom Tabs 回调换 grant 的步骤随后接入。 */
private fun openLogin(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://chat.deepseek.com/sign_in"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
