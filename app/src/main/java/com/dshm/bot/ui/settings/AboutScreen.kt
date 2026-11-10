package com.dshm.bot.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.dshm.bot.BuildConfig
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm

private const val PROJECT_URL = "https://github.com/3d-jq/DSHM-Bot"
private const val RELEASES_URL = "https://github.com/3d-jq/DSHM-Bot/releases/latest"
private const val HARNESS_URL = "https://github.com/deepseek-ai/deepseek-harness"
private const val DSH_GO_URL = "https://github.com/ccch1mneyyy/dsh-Go"
private const val DS_BOT_URL = "https://github.com/Fei2411/DS-Bot"
private const val PROOT_URL = "https://proot-me.github.io/"

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
}

/** 「版本」：DSHM Bot 的版本、检查更新与项目地址。 */
@Composable
fun AppVersionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = BuildConfig.VERSION_NAME
    SettingsPage(
        title = stringResource(R.string.settings_version),
        onBack = onBack,
    ) {
        item {
            AppVersionHero(
                title = stringResource(R.string.app_name),
                version = version,
            )
        }
        item {
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.settings_version),
                    value = version,
                    trailing = {},
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_about_check_update),
                    icon = R.drawable.ic_lucide_download,
                    value = "GitHub",
                    onClick = { openUrl(context, RELEASES_URL) },
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_project_url),
                    icon = R.drawable.ic_lucide_external_link,
                    value = "github.com/3d-jq/DSHM-Bot",
                    singleLineTitle = true,
                    onClick = { openUrl(context, PROJECT_URL) },
                )
            }
        }
    }
}

/** 「DSH 版本」：DSH 版本与 DeepSeek Harness 地址。 */
@Composable
fun DshVersionScreen(
    dshVersion: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val dsh = dshVersion ?: stringResource(R.string.settings_value_not_linked)
    SettingsPage(
        title = stringResource(R.string.settings_dsh_version),
        onBack = onBack,
    ) {
        item {
            DshVersionHero(
                title = "DSH",
                version = dsh,
            )
        }
        item {
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.settings_dsh_version),
                    value = dsh,
                    trailing = {},
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_harness_url),
                    icon = R.drawable.ic_lucide_external_link,
                    value = "github.com/deepseek-ai/deepseek-harness",
                    singleLineTitle = true,
                    onClick = { openUrl(context, HARNESS_URL) },
                )
            }
        }
    }
}

/** 版本页顶部的产品标识卡，用 App 启动图标。 */
@Composable
private fun AppVersionHero(
    title: String,
    version: String,
) {
    SettingsSection {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dshm.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(Dshm.spacing.settingsAvatar * 2),
            )
            Spacer(Modifier.size(Dshm.spacing.md))
            Text(
                text = title,
                style = Dshm.typography.pageTitle,
                color = Dshm.colors.textPrimary,
            )
            Text(
                text = version,
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
            )
        }
    }
}

/** DSH 版本页顶部的标识卡。 */
@Composable
private fun DshVersionHero(
    title: String,
    version: String,
) {
    SettingsSection {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dshm.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_deepseek_harness),
                contentDescription = null,
                modifier = Modifier
                    .size(Dshm.spacing.settingsAvatar)
                    .clip(RoundedCornerShape(Dshm.radius.sm)),
            )
            Spacer(Modifier.width(Dshm.spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = Dshm.typography.pageTitle,
                    color = Dshm.colors.textPrimary,
                )
                Text(
                    text = version,
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
            }
        }
    }
}

/** 开源许可：感谢 dsh-Go，并给出参考项目的开源地址。 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val thanks = listOf(
        Triple("dsh-Go", "AGPL-3.0", DSH_GO_URL),
        Triple("DS-Bot", "Apache-2.0", DS_BOT_URL),
        Triple("DeepSeek Harness", "官方仓库", HARNESS_URL),
        Triple("proot", "GPL-2.0", PROOT_URL),
    )
    SettingsPage(
        title = stringResource(R.string.settings_licenses),
        onBack = onBack,
    ) {
        item {
            SettingsSection {
                thanks.forEachIndexed { index, (name, license, url) ->
                    if (index > 0) SettingsRowDivider()
                    SettingsRow(
                        title = name,
                        value = license,
                        singleLineTitle = true,
                        onClick = { openUrl(context, url) },
                    )
                }
            }
        }
        item {
            SettingsSection {
                Text(
                    text = stringResource(R.string.info_open_source_thanks),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                    modifier = Modifier.padding(Dshm.spacing.lg),
                )
            }
        }
    }
}
