package com.dshm.bot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.runtime.RuntimeEndpoint
import com.dshm.bot.ui.components.FloatingIconButton
import org.json.JSONArray

/**
 * 「密钥」页（对齐 DS Bot `SecretsPage`）：列出名字/用途/范围，能改用途与范围、能换值、能删。
 *
 * 值只在写入时经过一次，界面不回显、也不保存。
 */
@Composable
fun SecretsScreen(endpoint: RuntimeEndpoint?, onBack: () -> Unit) {
    val viewModel: TeamSettingsViewModel = viewModel(
        key = "team-secrets:${endpoint?.baseUrl.orEmpty()}:${endpoint?.token.orEmpty()}",
        factory = TeamSettingsViewModel.factory(endpoint),
    )
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TeamSecret?>(null) }
    var deleting by remember { mutableStateOf<TeamSecret?>(null) }

    SettingsPage(title = stringResource(R.string.team_secrets_title), onBack = onBack) {
        state.error?.let { message ->
            item {
                Text(
                    text = message,
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.danger,
                    modifier = Modifier.padding(horizontal = Dshm.spacing.lg),
                )
            }
        }
        item {
            SettingsSection(title = stringResource(R.string.team_secrets_saved)) {
                if (state.secrets.isEmpty()) {
                    SettingsRow(
                        title = stringResource(R.string.team_secrets_empty),
                        enabled = false,
                    )
                } else {
                    state.secrets.forEachIndexed { index, secret ->
                        if (index > 0) SettingsRowDivider()
                        SettingsRow(
                            title = secret.name,
                            subtitle = secretSubtitle(secret, state.members),
                            value = secret.lastUsedAt?.let { stringResource(R.string.team_secrets_used) },
                            onClick = { editing = secret },
                        )
                    }
                }
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.team_secrets_add),
                    onClick = { adding = true },
                )
            }
        }
        item {
            Text(
                text = stringResource(R.string.team_secrets_note),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textTertiary,
                modifier = Modifier.padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
            )
        }
    }

    if (adding) {
        SecretDialog(
            existing = null,
            members = state.members,
            busy = state.busy,
            onDismiss = { adding = false },
            onSave = { name, value, purpose, scope ->
                viewModel.addSecret(name, value, purpose, scope) { error ->
                    if (error == null) adding = false
                }
            },
        )
    }
    editing?.let { secret ->
        SecretDialog(
            existing = secret,
            members = state.members,
            busy = state.busy,
            onDismiss = { editing = null },
            onSave = { _, value, purpose, scope ->
                viewModel.updateSecret(secret, purpose, scope, value) { error ->
                    if (error == null) editing = null
                }
            },
            onDelete = { deleting = secret; editing = null },
        )
    }
    deleting?.let { secret ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.team_secrets_delete)) },
            text = { Text(stringResource(R.string.team_secrets_delete_confirm, secret.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.deleteSecret(secret.name)
                }) {
                    Text(stringResource(R.string.team_secrets_delete), color = Dshm.colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

/**
 * 「连接器」页（对齐 DS Bot `ConnectorsPage`）：GitHub 的连接状态 / 账号 / 重试 / 断开 / 连接。
 */
@Composable
fun ConnectorsScreen(endpoint: RuntimeEndpoint?, onBack: () -> Unit) {
    val viewModel: TeamSettingsViewModel = viewModel(
        key = "team-connectors:${endpoint?.baseUrl.orEmpty()}:${endpoint?.token.orEmpty()}",
        factory = TeamSettingsViewModel.factory(endpoint),
    )
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var connecting by remember { mutableStateOf<TeamConnector?>(null) }

    SettingsPage(title = stringResource(R.string.team_connectors_title), onBack = onBack) {
        state.error?.let { message ->
            item {
                Text(
                    text = message,
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.danger,
                    modifier = Modifier.padding(horizontal = Dshm.spacing.lg),
                )
            }
        }
        item {
            SettingsSection(title = stringResource(R.string.team_connectors_available)) {
                if (state.connectors.isEmpty()) {
                    SettingsRow(
                        title = stringResource(R.string.team_connectors_empty),
                        enabled = false,
                    )
                } else {
                    state.connectors.forEachIndexed { index, connector ->
                        if (index > 0) SettingsRowDivider()
                        SettingsRow(
                            title = connector.name,
                            subtitle = when {
                                connector.error != null -> connector.error
                                connector.connected -> connector.account?.let {
                                    stringResource(R.string.team_connectors_connected_as, it)
                                } ?: stringResource(R.string.team_connectors_connected)

                                else -> stringResource(R.string.team_connectors_not_connected)
                            },
                            value = when {
                                connector.connected -> stringResource(R.string.team_connectors_disconnect)
                                connector.error != null -> stringResource(R.string.team_connectors_retry)
                                else -> stringResource(R.string.team_connectors_connect)
                            },
                            valueColor = if (connector.error != null) Dshm.colors.danger else null,
                            enabled = !state.busy,
                            onClick = {
                                when {
                                    connector.connected -> viewModel.disconnect(connector)
                                    connector.error != null -> viewModel.retry(connector)
                                    else -> connecting = connector
                                }
                            },
                        )
                    }
                }
            }
        }
        item {
            Text(
                text = stringResource(R.string.team_connectors_note),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textTertiary,
                modifier = Modifier.padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
            )
        }
    }

    connecting?.let { connector ->
        ConnectorTokenDialog(
            connector = connector,
            busy = state.busy,
            onDismiss = { connecting = null },
            onConnect = { token ->
                viewModel.connect(connector, token) { error ->
                    if (error == null) connecting = null
                }
            },
        )
    }
}

/**
 * 「能力」页（对齐 DS Bot `CapabilitiesPage`）：能力库 + 每个 Bot 的挂载开关 + 批准未批准的 MCP。
 */
@Composable
fun CapabilitiesScreen(endpoint: RuntimeEndpoint?, onBack: () -> Unit) {
    val viewModel: TeamSettingsViewModel = viewModel(
        key = "team-caps:${endpoint?.baseUrl.orEmpty()}:${endpoint?.token.orEmpty()}",
        factory = TeamSettingsViewModel.factory(endpoint),
    )
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<TeamCapability?>(null) }

    SettingsPage(title = stringResource(R.string.team_caps_title), onBack = onBack) {
        state.error?.let { message ->
            item {
                Text(
                    text = message,
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.danger,
                    modifier = Modifier.padding(horizontal = Dshm.spacing.lg),
                )
            }
        }
        item {
            SettingsSection(title = stringResource(R.string.team_caps_library)) {
                if (state.capabilities.isEmpty()) {
                    SettingsRow(
                        title = stringResource(R.string.team_caps_empty),
                        enabled = false,
                    )
                } else {
                    state.capabilities.forEachIndexed { index, capability ->
                        if (index > 0) SettingsRowDivider()
                        val users = state.attached.count { (_, names) -> capability.name in names }
                        SettingsRow(
                            title = capability.name,
                            subtitle = listOfNotNull(
                                capabilityKindLabel(capability.kind),
                                capability.description,
                            ).joinToString(" · "),
                            value = when {
                                !capability.approved ->
                                    stringResource(R.string.team_caps_needs_approval)

                                users > 0 -> stringResource(R.string.team_caps_attached, users)
                                else -> stringResource(R.string.team_caps_none_attached)
                            },
                            valueColor = if (!capability.approved) Dshm.colors.warning else null,
                            onClick = { open = capability },
                        )
                    }
                }
            }
        }
        item {
            Text(
                text = stringResource(R.string.team_caps_note),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textTertiary,
                modifier = Modifier.padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
            )
        }
    }

    open?.let { capability ->
        CapabilityDialog(
            capability = capability,
            state = state,
            busy = state.busy,
            onDismiss = { open = null },
            onAttach = { botId, attached -> viewModel.attachCapability(botId, capability.name, attached) },
            onApprove = { viewModel.approveCapability(capability.name) },
            onDelete = {
                viewModel.deleteCapability(capability.name) { error ->
                    if (error == null) open = null
                }
            },
        )
    }
}

@Composable
private fun CapabilityDialog(
    capability: TeamCapability,
    state: TeamSettingsUi,
    busy: Boolean,
    onDismiss: () -> Unit,
    onAttach: (botId: String, attached: Boolean) -> Unit,
    onApprove: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(capability.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm)) {
                Text(
                    text = listOfNotNull(
                        capabilityKindLabel(capability.kind),
                        capability.transport,
                    ).joinToString(" · "),
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.textTertiary,
                )
                capability.description?.let { description ->
                    Text(
                        text = description,
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.textSecondary,
                    )
                }
                if (!capability.approved) {
                    Text(
                        text = stringResource(R.string.team_caps_needs_approval),
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.warning,
                    )
                    TextButton(enabled = !busy, onClick = onApprove) {
                        Text(stringResource(R.string.team_caps_approve))
                    }
                }
                Text(
                    text = stringResource(R.string.team_caps_attach_title),
                    style = Dshm.typography.settingsSectionTitle,
                    color = Dshm.colors.textSecondary,
                )
                state.members.forEach { member ->
                    val attached = capability.name in state.attached[member.id].orEmpty()
                    val status = state.statuses[member.id]?.get(capability.name)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Dshm.radius.sm))
                            .clickable(enabled = !busy) { onAttach(member.id, !attached) }
                            .padding(horizontal = Dshm.spacing.xs, vertical = Dshm.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = member.name,
                            style = Dshm.typography.rowPreview,
                            color = if (attached) Dshm.colors.accent else Dshm.colors.textPrimary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (status != null) {
                            Text(
                                text = capabilityStatusLabel(status),
                                style = Dshm.typography.rowTime,
                                color = if (status == "error") Dshm.colors.danger else Dshm.colors.textTertiary,
                            )
                            Spacer(Modifier.size(Dshm.spacing.xs))
                        }
                        Text(
                            text = if (attached) "✓" else "",
                            style = Dshm.typography.rowPreview,
                            color = Dshm.colors.accent,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (confirmDelete) onDelete() else confirmDelete = true
                },
            ) {
                Text(
                    text = stringResource(
                        if (confirmDelete) R.string.team_caps_delete_confirm else R.string.team_caps_delete,
                    ),
                    color = Dshm.colors.danger,
                )
            }
        },
    )
}

/**
 * 「用量」页（对齐 DS Bot `UsagePage` 的主要信息）：范围、总量、每天的趋势、
 * 按 Bot / 群聊、按模型。
 *
 * `usage` 端点给的是「每小时 × 归属 × 模型」的行，范围筛选与合计在 `TeamUsage.kt` 里。
 */
@Composable
fun UsageScreen(endpoint: RuntimeEndpoint?, onBack: () -> Unit) {
    val viewModel: TeamSettingsViewModel = viewModel(
        key = "team-usage:${endpoint?.baseUrl.orEmpty()}:${endpoint?.token.orEmpty()}",
        factory = TeamSettingsViewModel.factory(endpoint),
    )
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var rangeDays by rememberSaveable { mutableStateOf(7) }
    androidx.compose.runtime.LaunchedEffect(endpoint) { viewModel.loadUsage() }

    val cutoff = usageCutoff(state.usageNow, rangeDays)
    val totals = usageTotals(state.usageRows, cutoff)
    val days = usageByDay(state.usageRows, cutoff)
    val owners = usageByOwner(state.usageRows, cutoff)
    val models = usageByModel(state.usageRows, cutoff)

    val ranges = listOf(
        1 to stringResource(R.string.team_usage_range_today),
        7 to stringResource(R.string.team_usage_range_7d),
        30 to stringResource(R.string.team_usage_range_30d),
    )
    SettingsPage(
        title = stringResource(R.string.team_usage_title),
        onBack = onBack,
        subtitle = if (state.usageScanning) {
            stringResource(R.string.team_usage_scanning)
        } else null,
        actions = {
            FloatingIconButton(
                icon = R.drawable.ic_lucide_refresh_cw,
                contentDescription = stringResource(R.string.team_usage_refresh),
                onClick = { viewModel.loadUsage() },
                enabled = !state.usageScanning,
            )
        },
    ) {
        item {
            SettingsSegmentedPill(
                options = ranges.map { it.second },
                selected = ranges.firstOrNull { it.first == rangeDays }?.second ?: ranges[1].second,
                onSelect = { picked ->
                    ranges.firstOrNull { it.second == picked }?.let { rangeDays = it.first }
                },
                modifier = Modifier.padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.xs),
            )
        }
        item {
            SettingsSection(title = stringResource(R.string.team_usage_total)) {
                SettingsRow(
                    title = stringResource(R.string.team_usage_everyone),
                    subtitle = buildString {
                        append(stringResource(R.string.team_usage_calls, totals.calls))
                        totals.cacheHit?.let { hit ->
                            append(" · ")
                            append(stringResource(R.string.team_usage_cache, formatPercent(hit)))
                        }
                    },
                    value = formatTokens(totals.tokens),
                    valueColor = Dshm.colors.textPrimary,
                )
            }
        }
        if (days.size > 1) {
            item {
                SettingsSection(title = stringResource(R.string.team_usage_by_day)) {
                    UsageDayBars(days)
                }
            }
        }
        item {
            SettingsSection(title = stringResource(R.string.team_usage_owners)) {
                if (owners.isEmpty()) {
                    SettingsRow(title = stringResource(R.string.team_usage_empty), enabled = false)
                } else {
                    owners.forEachIndexed { index, (ownerIndex, sum) ->
                        val owner = state.usageOwners.getOrNull(ownerIndex) ?: return@forEachIndexed
                        if (index > 0) SettingsRowDivider()
                        SettingsRow(
                            title = owner.name,
                            subtitle = buildString {
                                append(ownerKindLabel(owner.kind))
                                append(" · ")
                                append(stringResource(R.string.team_usage_calls, sum.calls))
                                sum.cacheHit?.let { hit ->
                                    append(" · ")
                                    append(stringResource(R.string.team_usage_cache, formatPercent(hit)))
                                }
                            },
                            value = formatTokens(sum.tokens),
                        )
                    }
                }
            }
        }
        item {
            SettingsSection(title = stringResource(R.string.team_usage_by_model)) {
                if (models.isEmpty()) {
                    SettingsRow(title = stringResource(R.string.team_usage_empty), enabled = false)
                } else {
                    models.forEachIndexed { index, (modelIndex, sum) ->
                        val model = state.usageModels.getOrNull(modelIndex) ?: return@forEachIndexed
                        if (index > 0) SettingsRowDivider()
                        SettingsRow(
                            title = model.name,
                            subtitle = buildString {
                                model.provider?.let { provider ->
                                    append(provider)
                                    append(" · ")
                                }
                                append(stringResource(R.string.team_usage_calls, sum.calls))
                            },
                            value = formatTokens(sum.tokens),
                        )
                    }
                }
            }
        }
    }
}

/** 每天的 token 柱状图（最多 14 根）。 */
@Composable
private fun UsageDayBars(days: List<Pair<String, Long>>) {
    val shown = if (days.size > 14) days.takeLast(14) else days
    val max = shown.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.xxs),
    ) {
        shown.forEach { (day, tokens) ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .fillMaxHeight((tokens.toFloat() / max).coerceIn(0.02f, 1f))
                            .clip(RoundedCornerShape(Dshm.radius.xs))
                            .background(Dshm.colors.accent.copy(alpha = 0.75f)),
                    )
                }
                Spacer(Modifier.size(Dshm.spacing.xxs))
                Text(
                    text = day.takeLast(5),
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun capabilityKindLabel(kind: String): String = stringResource(
    when (kind) {
        "skill" -> R.string.team_caps_kind_skill
        "pack" -> R.string.team_caps_kind_pack
        else -> R.string.team_caps_kind_mcp
    },
)

@Composable
private fun capabilityStatusLabel(status: String): String = when (status) {
    "ready" -> stringResource(R.string.team_caps_status_ready)
    "connecting" -> stringResource(R.string.team_caps_status_connecting)
    "needs-approval" -> stringResource(R.string.team_caps_status_approval)
    "error" -> stringResource(R.string.team_caps_status_error)
    else -> status
}

@Composable
private fun ownerKindLabel(kind: String): String = stringResource(
    when (kind) {
        "room" -> R.string.team_usage_kind_room
        "agent" -> R.string.team_usage_kind_agent
        "bot" -> R.string.team_usage_kind_bot
        else -> R.string.team_usage_kind_other
    },
)

/** `1.2M` 之类的短写法。 */
private fun formatTokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fM", tokens / 1_000_000.0)
    tokens >= 1_000 -> String.format(java.util.Locale.ROOT, "%.1fK", tokens / 1_000.0)
    else -> tokens.toString()
}

private fun formatPercent(value: Float): String {
    val percent = value * 100.0
    return if (percent >= 100.0) "100" else String.format(java.util.Locale.ROOT, "%.1f", percent)
}

/** 密钥编辑/新增：名字、值、用途、谁能用。 */
@Composable
private fun SecretDialog(
    existing: TeamSecret?,
    members: List<TeamMember>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, value: String, purpose: String, scope: List<String>?) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var value by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf(existing?.purpose.orEmpty()) }
    val initialScope = remember { scopeIds(existing) }
    var allBots by remember { mutableStateOf(initialScope == null) }
    var picked by remember { mutableStateOf(initialScope.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (existing == null) R.string.team_secrets_add else R.string.team_secrets_edit,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.uppercase() },
                    singleLine = true,
                    enabled = existing == null,
                    label = { Text(stringResource(R.string.team_secrets_name)) },
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = {
                        Text(
                            stringResource(
                                if (existing == null) {
                                    R.string.team_secrets_value
                                } else {
                                    R.string.team_secrets_value_keep
                                },
                            ),
                        )
                    },
                )
                OutlinedTextField(
                    value = purpose,
                    onValueChange = { purpose = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.team_secrets_purpose)) },
                )
                Text(
                    text = stringResource(R.string.team_secrets_who),
                    style = Dshm.typography.settingsSectionTitle,
                    color = Dshm.colors.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.xs)) {
                    ScopeChip(stringResource(R.string.team_secrets_scope_all), allBots) {
                        allBots = true
                    }
                    ScopeChip(stringResource(R.string.team_secrets_scope_some), !allBots) {
                        allBots = false
                    }
                }
                if (!allBots) {
                    members.forEach { member ->
                        val on = member.id in picked
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Dshm.radius.sm))
                                .clickable {
                                    picked = if (on) picked - member.id else picked + member.id
                                }
                                .padding(vertical = Dshm.spacing.xs, horizontal = Dshm.spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = member.name,
                                style = Dshm.typography.rowPreview,
                                color = if (on) Dshm.colors.accent else Dshm.colors.textPrimary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (on) "✓" else "",
                                style = Dshm.typography.rowPreview,
                                color = Dshm.colors.accent,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank() && (existing != null || value.isNotBlank()),
                onClick = {
                    onSave(name.trim(), value, purpose, if (allBots) null else picked)
                },
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.team_secrets_delete), color = Dshm.colors.danger)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
    )
}

@Composable
private fun ConnectorTokenDialog(
    connector: TeamConnector,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.team_connectors_connect_title, connector.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm)) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.team_connectors_token)) },
                )
                Text(
                    text = stringResource(R.string.team_connectors_token_hint),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && token.isNotBlank(), onClick = { onConnect(token) }) {
                Text(stringResource(R.string.team_connectors_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun ScopeChip(label: String, picked: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = Dshm.typography.rowPreview,
        color = if (picked) Dshm.colors.accent else Dshm.colors.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(Dshm.radius.pill))
            .background(if (picked) Dshm.colors.accent.copy(alpha = 0.12f) else Dshm.colors.surfaceMuted)
            .clickable(onClick = onClick)
            .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.xs),
    )
}

/** 副标题：用途 · 谁能用。 */
@Composable
private fun secretSubtitle(secret: TeamSecret, members: List<TeamMember>): String? {
    val scope = when {
        secret.scope.startsWith("[") -> {
            val ids = runCatching { JSONArray(secret.scope) }.getOrNull()
            val names = (0 until (ids?.length() ?: 0)).mapNotNull { index ->
                val id = ids?.optString(index).orEmpty()
                if (id.isEmpty()) null else members.firstOrNull { it.id == id }?.name ?: id
            }
            if (names.isEmpty()) {
                stringResource(R.string.team_secrets_scope_none)
            } else {
                stringResource(R.string.team_secrets_scope_some_of, names.joinToString(", "))
            }
        }

        else -> stringResource(R.string.team_secrets_scope_all)
    }
    return listOfNotNull(secret.purpose, scope).joinToString(" · ")
}

/** 现有密钥的范围 → Bot id 列表；`null` 表示所有 Bot。 */
private fun scopeIds(secret: TeamSecret?): List<String>? {
    if (secret == null) return null
    if (!secret.scope.startsWith("[")) return null
    val array = runCatching { JSONArray(secret.scope) }.getOrNull() ?: return null
    return (0 until array.length()).mapNotNull { index ->
        array.optString(index).takeIf { it.isNotEmpty() }
    }
}
