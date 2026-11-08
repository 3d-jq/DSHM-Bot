package com.dshm.bot.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.ui.settings.SettingsChoiceChip
import com.dshm.bot.ui.settings.SettingsEmpty
import com.dshm.bot.ui.settings.SettingsInput
import com.dshm.bot.ui.settings.SettingsPage
import com.dshm.bot.ui.settings.SettingsRefreshButton
import com.dshm.bot.ui.settings.SettingsRow
import com.dshm.bot.ui.settings.SettingsRowDivider
import com.dshm.bot.ui.settings.SettingsSection
import com.dshm.bot.ui.settings.SettingsSegmentedPill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 浏览历史页：按 Bot 记录最近访问的页面，点一条就在当前标签里打开。
 *
 * 记录由 [BotBrowserSession] 在页面装载完成时写入，存在这个 Bot 自己的 SharedPreferences 里，
 * 重开 App 也还在。
 */
@Composable
fun BrowserHistoryPage(
    session: BotBrowserSession,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    val entries = session.history

    SettingsPage(
        title = stringResource(R.string.browser_history),
        onBack = onBack,
    ) {
        if (entries.isEmpty()) {
            item { SettingsEmpty(stringResource(R.string.browser_history_empty)) }
        } else {
            item {
                SettingsSection {
                    entries.forEachIndexed { index, entry ->
                        if (index > 0) SettingsRowDivider()
                        SettingsRow(
                            title = entry.title.ifBlank { browserHostOf(entry.url) ?: entry.url },
                            subtitle = entry.url,
                            value = timeLabel(entry.visitedAt),
                            onClick = { onOpen(entry.url) },
                        )
                    }
                }
            }
            item {
                SettingsSection {
                    SettingsRow(
                        title = stringResource(R.string.browser_history_clear),
                        onClick = { confirming = true },
                        icon = R.drawable.ic_lucide_trash_2,
                    )
                }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = Dshm.colors.surface,
            title = {
                Text(
                    text = stringResource(R.string.browser_history_clear_title),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.browser_history_clear_hint),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    session.clearHistory()
                    confirming = false
                }) {
                    Text(stringResource(R.string.action_delete), color = Dshm.colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.action_cancel), color = Dshm.colors.textSecondary)
                }
            },
        )
    }
}

/** 浏览器设置页：UA / 视口 / Cookie / 站点数据。 */
@Composable
fun BrowserSettingsPage(
    session: BotBrowserSession,
    onBack: () -> Unit,
) {
    var refresh by remember { mutableIntStateOf(0) }
    var customUa by rememberSaveable(session.botId) {
        mutableStateOf(session.userAgentOverride.takeIf { it !in UA_PRESETS } .orEmpty())
    }
    var viewportText by rememberSaveable(session.botId) {
        mutableStateOf(session.viewportWidth?.toString().orEmpty())
    }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var confirmClearData by remember { mutableStateOf(false) }

    val page = session.activeTab()?.url?.takeIf { it.isNotEmpty() }
    val domains = remember(refresh, session.history.size, session.activeId) { session.cookieDomains() }
    val cleared = stringResource(R.string.browser_settings_cache_cleared)
    val applyLabel = stringResource(R.string.browser_settings_apply)

    SettingsPage(
        title = stringResource(R.string.browser_settings),
        onBack = onBack,
        actions = {
            SettingsRefreshButton(onClick = { refresh++ })
        },
    ) {
        page?.let { url ->
            item {
                SettingsSection(title = stringResource(R.string.browser_settings_current)) {
                    SettingsRow(
                        title = session.activeTab()?.title?.ifBlank { null } ?: browserHostOf(url) ?: url,
                        subtitle = url,
                    )
                }
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.browser_settings_ua)) {
                Column(
                    modifier = Modifier.padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                ) {
                    val labels = listOf(
                        stringResource(R.string.browser_settings_ua_default),
                        stringResource(R.string.browser_settings_ua_mobile),
                        stringResource(R.string.browser_settings_ua_desktop),
                        stringResource(R.string.browser_settings_ua_custom),
                    )
                    val current = when (session.userAgentOverride) {
                        null -> labels[0]
                        UA_MOBILE -> labels[1]
                        UA_DESKTOP -> labels[2]
                        else -> labels[3]
                    }
                    SettingsSegmentedPill(
                        options = labels,
                        selected = current,
                        onSelect = { picked ->
                            when (picked) {
                                labels[0] -> session.setUserAgent(null)
                                labels[1] -> session.setUserAgent(UA_MOBILE)
                                labels[2] -> session.setUserAgent(UA_DESKTOP)
                                else -> if (customUa.isNotBlank()) session.setUserAgent(customUa)
                            }
                        },
                    )
                    if (current == labels[3]) {
                        SettingsInput(
                            value = customUa,
                            onValueChange = { customUa = it },
                            placeholder = stringResource(R.string.browser_settings_ua_hint),
                            singleLine = false,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = {
                                    if (customUa.isNotBlank()) session.setUserAgent(customUa)
                                },
                            ) {
                                Text(applyLabel, color = Dshm.colors.accent)
                            }
                        }
                    }
                }
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.browser_settings_viewport)) {
                Column(
                    modifier = Modifier.padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                ) {
                    val presets = listOf<Int?>(null, 360, 414, 768)
                    val followLabel = stringResource(R.string.browser_settings_viewport_follow)
                    val labels = presets.map { it?.toString() ?: followLabel }
                    SettingsSegmentedPill(
                        options = labels,
                        selected = presets.firstOrNull { it == session.viewportWidth }?.toString()
                            ?: followLabel,
                        onSelect = { picked ->
                            when (val width = presets[labels.indexOf(picked).coerceAtLeast(0)]) {
                                null -> session.resetViewport()
                                else -> session.setViewportWidth(width)
                            }
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            SettingsInput(
                                value = viewportText,
                                onValueChange = { viewportText = it.filter(Char::isDigit).take(4) },
                                placeholder = stringResource(R.string.browser_settings_viewport_hint),
                            )
                        }
                        TextButton(
                            onClick = {
                                viewportText.toIntOrNull()?.let { session.setViewportWidth(it) }
                            },
                        ) {
                            Text(applyLabel, color = Dshm.colors.accent)
                        }
                    }
                }
            }
        }

        item {
            SettingsSection(
                title = stringResource(R.string.browser_settings_cookies),
                footer = notice,
            ) {
                if (domains.isEmpty()) {
                    SettingsRow(
                        title = stringResource(R.string.browser_settings_cookies_empty),
                    )
                } else {
                    domains.forEachIndexed { index, domain ->
                        if (index > 0) SettingsRowDivider()
                        val cookies = session.cookiesFor(domain).orEmpty()
                        SettingsRow(
                            title = domain,
                            subtitle = cookies.takeIf { it.isNotBlank() }?.take(64)
                                ?: stringResource(R.string.browser_settings_cookies_empty),
                            trailing = {
                                BoxedIcon(
                                    icon = R.drawable.ic_lucide_trash_2,
                                    description = stringResource(R.string.browser_settings_cookie_domain),
                                    enabled = cookies.isNotBlank(),
                                    onClick = {
                                        session.clearCookiesFor(domain)
                                        refresh++
                                    },
                                )
                            },
                        )
                    }
                }
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.browser_settings_clear_cookies),
                    onClick = { confirmClearAll = true },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.browser_settings_data)) {
                SettingsRow(
                    title = stringResource(R.string.browser_settings_clear_cache),
                    onClick = {
                        session.clearCache()
                        notice = cleared
                    },
                )
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.browser_settings_clear_data),
                    subtitle = stringResource(R.string.browser_settings_clear_data_hint),
                    onClick = { confirmClearData = true },
                )
            }
        }
    }

    if (confirmClearAll) {
        ConfirmDialog(
            title = stringResource(R.string.browser_settings_clear_cookies),
            hint = stringResource(R.string.browser_settings_clear_data_hint),
            confirm = stringResource(R.string.action_delete),
            onCancel = { confirmClearAll = false },
            onConfirm = {
                session.clearAllCookies()
                confirmClearAll = false
                refresh++
            },
        )
    }
    if (confirmClearData) {
        ConfirmDialog(
            title = stringResource(R.string.browser_settings_clear_data_title),
            hint = stringResource(R.string.browser_settings_clear_data_hint),
            confirm = stringResource(R.string.action_delete),
            onCancel = { confirmClearData = false },
            onConfirm = {
                session.clearSiteData()
                session.clearHistory()
                confirmClearData = false
                refresh++
            },
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    hint: String,
    confirm: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Dshm.colors.surface,
        title = {
            Text(text = title, style = Dshm.typography.sheetTitle, color = Dshm.colors.textPrimary)
        },
        text = {
            Text(text = hint, style = Dshm.typography.rowPreview, color = Dshm.colors.textSecondary)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = Dshm.colors.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel), color = Dshm.colors.textSecondary)
            }
        },
    )
}

/** 行尾的小图标键（不引入按钮底色）。 */
@Composable
private fun BoxedIcon(
    icon: Int,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(Dshm.spacing.iconButton)
            .padding(Dshm.spacing.xxs)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = if (enabled) Dshm.colors.textSecondary else Dshm.colors.textTertiary,
            modifier = Modifier.size(Dshm.spacing.topBarIcon),
        )
    }
}

private fun timeLabel(timeMs: Long): String {
    if (timeMs <= 0L) return ""
    return runCatching {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timeMs))
    }.getOrDefault("")
}

internal fun browserHostOf(url: String): String? =
    runCatching { android.net.Uri.parse(url).host }.getOrNull()?.takeIf { it.isNotBlank() }

private const val UA_MOBILE =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Mobile Safari/537.36"
private const val UA_DESKTOP =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Safari/537.36"
private val UA_PRESETS = setOf(UA_MOBILE, UA_DESKTOP)
