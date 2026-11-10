package com.dshm.bot.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.service.PhoneAppRule
import com.dshm.bot.service.PhoneAppRules

private data class LaunchableApp(val label: String, val packageName: String)

@Composable
fun PhoneAppRulesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val apps = remember(context) { launchableApps(context) }
    var selected by remember { mutableStateOf<LaunchableApp?>(null) }
    var rules by remember {
        mutableStateOf(apps.associate { it.packageName to PhoneAppRules.ruleOf(context, it.packageName) })
    }

    SettingsPage(
        title = stringResource(R.string.settings_phone_app_rules),
        onBack = onBack,
    ) {
        item {
            SettingsHint(stringResource(R.string.settings_phone_app_rules_hint))
        }
        item {
            SettingsSection {
                if (apps.isEmpty()) {
                    SettingsRow(title = stringResource(R.string.settings_phone_no_apps))
                } else {
                    apps.forEachIndexed { index, app ->
                        if (index > 0) SettingsRowDivider()
                        val rule = rules[app.packageName] ?: PhoneAppRule.NORMAL
                        SettingsRow(
                            title = app.label,
                            subtitle = app.packageName,
                            value = appRuleLabel(rule),
                            onClick = { selected = app },
                        )
                    }
                }
            }
        }
    }

    selected?.let { app ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(app.label) },
            text = {
                androidx.compose.foundation.layout.Column {
                    PhoneAppRule.entries.forEach { rule ->
                        TextButton(
                            onClick = {
                                PhoneAppRules.setRule(context, app.packageName, rule)
                                rules = rules + (app.packageName to rule)
                                selected = null
                            },
                        ) {
                            Text(
                                appRuleLabel(rule) + " · " + appRuleHint(rule),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selected = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun appRuleLabel(rule: PhoneAppRule): String = when (rule) {
    PhoneAppRule.NORMAL -> stringResource(R.string.settings_phone_app_rule_normal)
    PhoneAppRule.CONFIRM -> stringResource(R.string.settings_phone_app_rule_confirm)
    PhoneAppRule.DENY -> stringResource(R.string.settings_phone_app_rule_deny)
}

@Composable
private fun appRuleHint(rule: PhoneAppRule): String = when (rule) {
    PhoneAppRule.NORMAL -> stringResource(R.string.settings_phone_app_rule_normal_hint)
    PhoneAppRule.CONFIRM -> stringResource(R.string.settings_phone_app_rule_confirm_hint)
    PhoneAppRule.DENY -> stringResource(R.string.settings_phone_app_rule_deny_hint)
}

private fun launchableApps(context: Context): List<LaunchableApp> {
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    return pm.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
        0,
    )
        .mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = info.loadLabel(pm)?.toString().orEmpty().ifBlank { packageName }
            LaunchableApp(label, packageName)
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}
