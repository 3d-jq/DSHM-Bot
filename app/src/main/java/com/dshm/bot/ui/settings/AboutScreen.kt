package com.dshm.bot.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.dshm.bot.BuildConfig
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm

/** 关于：版本、DSH 版本、开源许可入口。 */
@Composable
fun AboutScreen(
    dshVersion: String?,
    engineStatus: String,
    onOpenLicenses: () -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(
        title = stringResource(R.string.settings_group_about),
        onBack = onBack,
    ) {
        item {
            SettingsSection(title = stringResource(R.string.settings_about_app)) {
                SettingsRow(
                    title = stringResource(R.string.settings_version),
                    value = BuildConfig.VERSION_NAME,
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_dsh_version),
                    value = dshVersion ?: stringResource(R.string.settings_value_not_linked),
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_local_engine),
                    value = engineStatus,
                )
            }
        }
        item {
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.settings_licenses),
                    icon = R.drawable.ic_lucide_file_text,
                    onClick = onOpenLicenses,
                )
            }
        }
        item {
            SettingsHint(stringResource(R.string.info_licenses))
        }
    }
}

/** 开源许可清单。 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val entries = listOf(
        "dsh-Go" to "AGPL-3.0",
        "DS-Bot" to "Apache-2.0",
        "Lucide" to "ISC",
        "AndroidX / Jetpack Compose" to "Apache-2.0",
        "Kotlin & Coroutines" to "Apache-2.0",
        "Coil" to "Apache-2.0",
    )
    SettingsPage(
        title = stringResource(R.string.settings_licenses),
        onBack = onBack,
    ) {
        item {
            SettingsSection(title = stringResource(R.string.settings_licenses_components)) {
                entries.forEachIndexed { index, (name, license) ->
                    if (index > 0) SettingsRowDivider()
                    SettingsRow(title = name, value = license)
                }
            }
        }
        item {
            SettingsSection(title = stringResource(R.string.settings_licenses_project)) {
                Text(
                    text = stringResource(R.string.info_licenses),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                    modifier = Modifier.padding(Dshm.spacing.lg),
                )
            }
        }
    }
}
