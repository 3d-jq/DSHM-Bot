package com.dshm.bot.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.domain.model.DshGoPreferences

/**
 * 对话时间线的管线开关（Phase 1b/1c）。
 *
 * - [DshGoPreferences.KEY_TIMELINE_PARITY]：kill-switch，默认开（entries 走
 *   「timeline 快照 + feed 增量 + 本地 echo」）；关掉整条回旧管线（load + feed
 *   message + session/follow，回滚路径代码保留）。放在通用组：它是对话行为的
 *   管线开关，与通知/语音这些对话周边是邻居，且复用 SettingsPage 全套现成组件。
 * - [DshGoPreferences.KEY_TIMELINE_SHADOW]：debug 对比工具。parity 开着时两路合一、
 *   shadow 无意义（自动关）；只在关掉 parity 回旧管线后才有对比价值，所以这里
 *   parity 开关关掉前它是禁用态。
 */
@Composable
internal fun TimelineSettingsScreen(
    dshGo: DshGoPreferences,
    onBack: () -> Unit,
    onSetPreference: (String, Boolean) -> Unit,
) {
    SettingsPage(
        title = stringResource(R.string.settings_timeline),
        onBack = onBack,
    ) {
        item {
            SettingsSection(
                title = stringResource(R.string.settings_timeline_pipeline),
                footer = stringResource(R.string.settings_timeline_hint),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_timeline_parity),
                    subtitle = stringResource(R.string.settings_timeline_parity_hint),
                    checked = dshGo.flag(DshGoPreferences.KEY_TIMELINE_PARITY),
                    onCheckedChange = { onSetPreference(DshGoPreferences.KEY_TIMELINE_PARITY, it) },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_timeline_shadow),
                    subtitle = stringResource(R.string.settings_timeline_shadow_hint),
                    checked = dshGo.flag(DshGoPreferences.KEY_TIMELINE_SHADOW),
                    onCheckedChange = { onSetPreference(DshGoPreferences.KEY_TIMELINE_SHADOW, it) },
                    enabled = !dshGo.flag(DshGoPreferences.KEY_TIMELINE_PARITY),
                )
            }
        }
    }
}
