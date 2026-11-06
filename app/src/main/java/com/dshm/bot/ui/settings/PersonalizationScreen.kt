package com.dshm.bot.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.settings.PersonalizationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置首页用：读个人偏好第一行（IO 线程，不阻塞首帧）。 */
@Composable
fun rememberAgentsSummary(context: Context, refreshKey: Any? = Unit): String? {
    val store = remember(context) { PersonalizationStore(context) }
    var summary by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(store, refreshKey) {
        summary = withContext(Dispatchers.IO) { store.summary() }
    }
    return summary
}

// 个性化：个人偏好、可视化回复、Agent 预设，对齐 dsh-Go PersonalizationPage。
@Composable
fun PersonalizationScreen(
    onBack: () -> Unit,
    onOpenAgentPresets: () -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember(context) { PersonalizationStore(context) }
    val scope = rememberCoroutineScope()
    val rootfsAvailable = remember(store) { store.rootfsAvailable() }
    var text by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            val value = withContext(Dispatchers.IO) { store.read().orEmpty() }
            text = value
            original = value
            loaded = true
            saved = false
            error = null
        }
    }

    LaunchedEffect(store) { reload() }

    SettingsPage(
        title = stringResource(R.string.settings_personalization),
        onBack = onBack,
        actions = {
            SettingsRefreshButton(onClick = { reload() })
        },
    ) {
        if (!loaded) {
            item { SettingsLoading() }
        } else {
            item {
                SettingsSection(
                    title = stringResource(R.string.settings_personal_preference),
                    footer = stringResource(R.string.personalization_hint),
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.padding(Dshm.spacing.lg),
                    ) {
                        SettingsInput(
                            value = text,
                            onValueChange = {
                                text = it.take(PersonalizationStore.MAX_CHARS)
                                saved = false
                                error = null
                            },
                            modifier = Modifier.heightIn(min = 280.dp),
                            placeholder = stringResource(R.string.personalization_placeholder),
                            singleLine = false,
                        )
                        Text(
                            text = "${text.length} / ${PersonalizationStore.MAX_CHARS}",
                            style = Dshm.typography.rowTime,
                            color = Dshm.colors.textTertiary,
                            modifier = Modifier.padding(top = Dshm.spacing.xs),
                        )
                    }
                }
            }

            if (!rootfsAvailable) {
                item { SettingsHint(stringResource(R.string.info_engine_required)) }
            }
            if (error != null) {
                item { SettingsError(error.orEmpty()) }
            }
            if (saved) {
                item { SettingsHint(stringResource(R.string.personalization_saved_hint)) }
            }

            item {
                SettingsPrimaryButton(
                    text = if (saving) {
                        stringResource(R.string.action_saving)
                    } else {
                        stringResource(R.string.action_save)
                    },
                    enabled = rootfsAvailable && !saving && text != original,
                    onClick = {
                        saving = true
                        error = null
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { store.save(text) }
                            saving = false
                            if (ok) {
                                original = text
                                saved = true
                            } else {
                                error = context.getString(R.string.personalization_save_failed)
                            }
                        }
                    },
                )
            }
        }
        // 可视化回复（尚未接入）；Agent 预设是引擎侧的功能页。
        item {
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.settings_visual_replies),
                    subtitle = stringResource(R.string.settings_unavailable_hint),
                    value = stringResource(R.string.settings_unavailable),
                    enabled = false,
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_agent_presets),
                    subtitle = stringResource(R.string.presets_subtitle),
                    onClick = onOpenAgentPresets,
                )
            }
        }
    }
}
