package com.dshm.bot.ui.settings

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.ConnectorsView
import com.dshm.bot.data.remote.ConnectorAuthKind
import com.dshm.bot.data.remote.ConnectorRow
import com.dshm.bot.data.remote.MailAccountRow
import com.dshm.bot.data.remote.MailBox
import com.dshm.bot.data.runtime.RuntimeEndpoint
import org.json.JSONObject

/**
 * 连接器页（对齐 DS Bot 0.3.6 `ConnectorsPage`）：目录卡 + 搜索 + 分类 + 连接，
 * 点开进详情（登录码 / token / 浏览器回执 / 只读 / 使用它的 Bot），邮箱单独一页。
 * 所有页面都是 `SettingsPage`（顶栏只有标题，全项目统一）。
 */
@Composable
fun ConnectorsScreen(endpoint: RuntimeEndpoint?, onBack: () -> Unit) {
    val viewModel: TeamSettingsViewModel = viewModel(
        key = "team-connectors:${endpoint?.baseUrl.orEmpty()}:${endpoint?.token.orEmpty()}",
        factory = TeamSettingsViewModel.factory(endpoint),
    )
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val view = state.connectorsView

    var detailId by remember { mutableStateOf<String?>(null) }
    var showCustom by remember { mutableStateOf(false) }
    var showMail by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val done: (String?) -> Unit = { error = it }

    when {
        showMail -> MailBoxScreen(
            mail = view.mail, busy = state.busy, error = error,
            onBack = { showMail = false },
            onAdd = { address, password -> viewModel.mailAccountAdd(address, password, null, done) },
            onRemove = { viewModel.mailAccountRemove(it, done) },
        )
        detailId != null -> {
            val id = detailId
            if (id == ConnectorsView.MAIL_ID) {
                showMail = true
                detailId = null
            } else {
                val connector = view.connectors.firstOrNull { it.id == id }
                if (connector == null) {
                    detailId = null
                } else {
                    ConnectorDetailScreen(
                        connector = connector, members = state.members, busy = state.busy, error = error,
                        onBack = { detailId = null },
                        onConnect = { body -> viewModel.connectorConnect(connector.id, body, done) },
                        onDisconnect = { viewModel.connectorDisconnect(connector.id, done) },
                        onRetry = { viewModel.connectorRetry(connector.id, done) },
                        onCancel = { viewModel.connectorCancel(connector.id, done) },
                        onSignInCode = { viewModel.connectorSignInCode(connector.id, it, done) },
                        onClient = { cid, secret -> viewModel.connectorClient(connector.id, cid, secret, done) },
                        onSet = { ro, off -> viewModel.connectorSet(connector.id, ro, off, done) },
                        onRemove = { viewModel.connectorRemove(connector.id, done) },
                    )
                }
            }
        }
        else -> ConnectorDirectoryScreen(
            view = view, error = state.error ?: error,
            onOpen = { detailId = it }, onCustom = { showCustom = true }, onBack = onBack,
        )
    }

    if (showCustom) {
        AddConnectorSheet(
            busy = state.busy, onDismiss = { showCustom = false },
            onAdd = { name, url, command ->
                viewModel.connectorAdd(name, url, command) { message ->
                    error = message
                    if (message == null) showCustom = false
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 目录
// ---------------------------------------------------------------------------

private val CATEGORIES = listOf(
    "" to R.string.team_connectors_all,
    "dev" to R.string.team_connectors_cat_dev,
    "docs" to R.string.team_connectors_cat_docs,
    "work" to R.string.team_connectors_cat_work,
    "design" to R.string.team_connectors_cat_design,
    "data" to R.string.team_connectors_cat_data,
    "commerce" to R.string.team_connectors_cat_commerce,
    "search" to R.string.team_connectors_cat_search,
    "other" to R.string.team_connectors_cat_other,
)
private val FEATURED = listOf("github", "mail", "notion", "linear", "figma", "slack")
private val KNOWN_CATEGORIES = setOf("dev", "docs", "work", "design", "data", "commerce", "search")

@Composable
private fun ConnectorDirectoryScreen(
    view: ConnectorsView, error: String?, onOpen: (String) -> Unit,
    onCustom: () -> Unit, onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    val apps = remember(view) { listOf(view.mailboxCard()) + view.connectors.filter { it.id != ConnectorsView.MAIL_ID } }
    val shown = apps.filter { app ->
        val inCategory = when {
            category.isEmpty() -> true
            category == "other" -> app.category !in KNOWN_CATEGORIES
            else -> app.category == category
        }
        inCategory && (query.isBlank() || "${app.name} ${app.about}".contains(query.trim(), ignoreCase = true))
    }
    val featured = FEATURED.mapNotNull { id -> apps.firstOrNull { it.id == id } }
    val filtering = query.isNotBlank() || category.isNotEmpty()

    SettingsPage(title = stringResource(R.string.team_connectors_title), onBack = onBack) {
        error?.let { message ->
            item {
                Text(message, color = Dshm.colors.danger, style = Dshm.typography.rowPreview,
                    modifier = Modifier.padding(horizontal = Dshm.spacing.lg))
            }
        }
        item {
            SettingsInput(value = query, onValueChange = { query = it },
                placeholder = stringResource(R.string.team_connectors_search),
                modifier = Modifier.padding(horizontal = Dshm.spacing.lg))
        }
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Dshm.spacing.lg),
                horizontalArrangement = Arrangement.End) {
                SettingsSecondaryButton(text = stringResource(R.string.team_connectors_custom),
                    onClick = onCustom, fillWidth = false, onBackground = Dshm.colors.pageBackground)
            }
        }
        item {
            Column(Modifier.padding(horizontal = Dshm.spacing.lg)) {
                CATEGORIES.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.xs)) {
                        row.forEach { (id, label) ->
                            CategoryChip(stringResource(label), category == id, { category = id }, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        if (!filtering && featured.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.team_connectors_recommended)) }
            items(featured, key = { "f:${it.id}" }) { ConnectorTile(it, onOpen) }
        }
        if (shown.isEmpty()) {
            item {
                Text(stringResource(R.string.team_connectors_empty_search), style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textTertiary, modifier = Modifier.padding(Dshm.spacing.lg))
            }
        } else {
            if (!filtering && featured.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.team_connectors_available)) }
            }
            items(shown, key = { "a:${it.id}" }) { ConnectorTile(it, onOpen) }
        }
        item {
            Text(stringResource(R.string.team_connectors_note), style = Dshm.typography.rowPreview,
                color = Dshm.colors.textTertiary, modifier = Modifier.padding(Dshm.spacing.lg))
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.heightIn(min = Dshm.spacing.xl * 1.6f)
            .clip(RoundedCornerShape(Dshm.radius.md))
            .background(if (selected) Dshm.colors.accent else Dshm.colors.surfaceMuted)
            .clickable(onClick = onClick)
            .padding(horizontal = Dshm.spacing.xs, vertical = Dshm.spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Dshm.typography.rowPreview,
            color = if (selected) Dshm.colors.onAccent else Dshm.colors.textSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = Dshm.typography.settingsRowTitle, color = Dshm.colors.textPrimary,
        modifier = Modifier.padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm))
}

@Composable
private fun ConnectorTile(connector: ConnectorRow, onOpen: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(connector.id) }
            .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConnectorMark(connector)
        Spacer(Modifier.width(Dshm.spacing.md))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(connector.name, style = Dshm.typography.settingsRowTitle,
                    color = Dshm.colors.textPrimary, maxLines = 1)
                if (connector.custom) {
                    Spacer(Modifier.width(Dshm.spacing.xs))
                    Text(stringResource(R.string.team_connectors_added), style = Dshm.typography.rowTime,
                        color = Dshm.colors.textTertiary)
                }
            }
            Text(connectorStatus(connector), style = Dshm.typography.rowPreview,
                color = if (connector.failed) Dshm.colors.danger else Dshm.colors.textSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            when {
                connector.connected -> stringResource(R.string.team_connectors_connected)
                connector.failed -> stringResource(R.string.team_connectors_retry)
                connector.state.isNotEmpty() && connector.state != "off" -> stringResource(R.string.team_connectors_open)
                else -> stringResource(R.string.team_connectors_connect)
            },
            style = Dshm.typography.rowPreview, color = Dshm.colors.accent,
        )
    }
}

@Composable
private fun connectorStatus(connector: ConnectorRow): String = when {
    connector.failed -> stringResource(R.string.team_connectors_error, connector.error.orEmpty())
    connector.connecting -> if (connector.pending != null) stringResource(R.string.team_connectors_waiting_sign_in)
        else stringResource(R.string.team_connectors_connecting)
    connector.needsSignIn -> stringResource(R.string.team_connectors_sign_in_again)
    connector.needsClient -> stringResource(R.string.team_connectors_needs_client)
    connector.connected -> connector.account?.let { stringResource(R.string.team_connectors_connected_as, it) }
        ?: stringResource(R.string.team_connectors_connected)
    else -> connector.about
}

@Composable
private fun ConnectorMark(connector: ConnectorRow) {
    val github = connector.id.equals("github", ignoreCase = true)
    Box(
        modifier = Modifier.size(Dshm.spacing.avatar).clip(RoundedCornerShape(Dshm.radius.md))
            .background(if (github || connector.connected) Dshm.colors.accent.copy(alpha = 0.12f) else Dshm.colors.surfaceMuted),
        contentAlignment = Alignment.Center,
    ) {
        if (github) {
            Icon(painter = painterResource(R.drawable.ic_connector_github), contentDescription = null,
                tint = Dshm.colors.githubBrand, modifier = Modifier.size(Dshm.spacing.icon))
        } else {
            val icon = when (connector.id) {
                ConnectorsView.MAIL_ID -> R.drawable.ic_lucide_at_sign
                "notion" -> R.drawable.ic_lucide_file_text
                "figma" -> R.drawable.ic_lucide_palette
                else -> R.drawable.ic_lucide_globe
            }
            Icon(painter = painterResource(icon), contentDescription = null,
                tint = if (connector.connected) Dshm.colors.accent else Dshm.colors.textSecondary,
                modifier = Modifier.size(Dshm.spacing.icon))
        }
    }
}

// ---------------------------------------------------------------------------
// 详情
// ---------------------------------------------------------------------------

@Composable
private fun ConnectorDetailScreen(
    connector: ConnectorRow, members: List<TeamMember>, busy: Boolean, error: String?,
    onBack: () -> Unit, onConnect: (JSONObject) -> Unit, onDisconnect: () -> Unit,
    onRetry: () -> Unit, onCancel: () -> Unit, onSignInCode: (String) -> Unit,
    onClient: (String, String) -> Unit, onSet: (Boolean?, List<String>?) -> Unit, onRemove: () -> Unit,
) {
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var browserInput by remember { mutableStateOf("") }
    var showPaste by remember { mutableStateOf(false) }
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    val pending = connector.pending

    SettingsPage(title = connector.name, onBack = onBack) {
        item { ConnectorMark(connector) }
        item {
            Text(connectorStatus(connector), style = Dshm.typography.rowPreview,
                color = if (connector.failed) Dshm.colors.danger else Dshm.colors.textSecondary,
                modifier = Modifier.padding(horizontal = Dshm.spacing.lg))
        }
        if (connector.about.isNotBlank()) {
            item {
                Text(connector.about, style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary, modifier = Modifier.padding(horizontal = Dshm.spacing.lg))
            }
        }
        error?.let { message ->
            item {
                Text(message, color = Dshm.colors.danger, style = Dshm.typography.rowPreview,
                    modifier = Modifier.padding(horizontal = Dshm.spacing.lg))
            }
        }

        if (pending?.isBrowser == true) {
            item {
                SettingsSection(title = stringResource(R.string.team_connectors_waiting_sign_in)) {
                    Text(stringResource(R.string.team_connectors_browser_hint), style = Dshm.typography.rowPreview,
                        color = Dshm.colors.textSecondary)
                    pending.url?.let { OpenLinkRow(it) }
                    if (showPaste) {
                        SettingsInput(value = browserInput, onValueChange = { browserInput = it },
                            placeholder = stringResource(R.string.team_connectors_paste_address))
                        SettingsPrimaryButton(text = stringResource(R.string.team_connectors_finish_sign_in),
                            enabled = !busy && browserInput.isNotBlank(), onClick = { onSignInCode(browserInput) })
                    } else {
                        TextButtonRow(stringResource(R.string.team_connectors_did_not_return)) { showPaste = true }
                    }
                    TextButtonRow(stringResource(R.string.team_connectors_cancel), onCancel)
                }
            }
        }

        if (pending?.isDevice == true) {
            item {
                SettingsSection(title = stringResource(R.string.team_connectors_waiting_sign_in)) {
                    Text(pending.userCode.orEmpty(), style = Dshm.typography.settingsRowTitle, color = Dshm.colors.accent)
                    pending.verificationUri?.let { url ->
                        Text(stringResource(R.string.team_connectors_device_hint, url),
                            style = Dshm.typography.rowPreview, color = Dshm.colors.textSecondary)
                        OpenLinkRow(url)
                    }
                    TextButtonRow(stringResource(R.string.team_connectors_cancel), onCancel)
                }
            }
        }

        if (connector.needsClient && pending == null) {
            item {
                SettingsSection(title = stringResource(R.string.team_connectors_needs_client)) {
                    SettingsInput(value = clientId, onValueChange = { clientId = it },
                        placeholder = stringResource(R.string.team_connectors_client_id))
                    SettingsInput(value = clientSecret, onValueChange = { clientSecret = it },
                        placeholder = stringResource(R.string.team_connectors_client_secret),
                        visualTransformation = PasswordVisualTransformation())
                    SettingsPrimaryButton(text = stringResource(R.string.team_connectors_connect),
                        enabled = !busy && clientId.isNotBlank(), onClick = { onClient(clientId, clientSecret) })
                }
            }
        }

        if (showToken && pending == null) {
            item {
                SettingsSection(title = stringResource(R.string.team_connectors_connect_title, connector.name)) {
                    SettingsInput(
                        value = token, onValueChange = { token = it },
                        placeholder = stringResource(
                            if (connector.auth == ConnectorAuthKind.KEY) R.string.team_connectors_key
                            else R.string.team_connectors_token, connector.name,
                        ),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    connector.keyUrl?.let { OpenLinkRow(it, stringResource(R.string.team_connectors_get_key)) }
                    SettingsPrimaryButton(text = stringResource(R.string.team_connectors_connect),
                        enabled = !busy && token.isNotBlank(), onClick = {
                            val body = JSONObject()
                            if (connector.auth == ConnectorAuthKind.KEY) body.put("key", token.trim())
                            else body.put("token", token.trim())
                            onConnect(body)
                        })
                }
            }
        }

        if (pending == null && !connector.needsClient && !showToken) {
            item {
                SettingsSection {
                    when {
                        connector.connected || connector.state == "needs-sign-in" ->
                            SettingsRow(title = stringResource(R.string.team_connectors_disconnect),
                                enabled = !busy, onClick = onDisconnect)
                        connector.failed ->
                            SettingsRow(title = stringResource(R.string.team_connectors_retry),
                                enabled = !busy, onClick = onRetry)
                        else ->
                            SettingsRow(title = stringResource(R.string.team_connectors_connect),
                                enabled = !busy, onClick = {
                                    when (connector.auth) {
                                        ConnectorAuthKind.NONE -> onConnect(JSONObject())
                                        ConnectorAuthKind.KEY -> showToken = true
                                        ConnectorAuthKind.OAUTH ->
                                            if (connector.authToken && connector.authClientId == null) showToken = true
                                            else onConnect(JSONObject())
                                        else -> Unit
                                    }
                                })
                    }
                    if (connector.id == "github" && !connector.connected && connector.auth == ConnectorAuthKind.OAUTH) {
                        TextButtonRow(stringResource(R.string.team_connectors_use_token)) { showToken = true }
                    }
                    if (connector.custom) {
                        SettingsRow(title = stringResource(R.string.team_connectors_remove),
                            enabled = !busy, onClick = onRemove)
                    }
                }
            }
        }

        if (connector.connected && pending == null) {
            item {
                SettingsSection(
                    title = stringResource(R.string.team_connectors_for_bots),
                    footer = stringResource(R.string.team_connectors_for_bots_hint),
                ) {
                    if (connector.readOnly != null) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.team_connectors_read_only),
                            subtitle = stringResource(R.string.team_connectors_read_only_hint),
                            checked = connector.readOnly == true,
                            onCheckedChange = { onSet(it, connector.offFor) },
                        )
                        SettingsRowDivider()
                    }
                    members.forEach { member ->
                        val off = member.id in connector.offFor
                        // 单一切换逻辑：M3 Checkbox 会消费自己的点击，不会与行 onClick
                        // 双触发；两处共用同一函数避免规则漂移。
                        fun toggle() {
                            val next = connector.offFor.toMutableList()
                            if (off) next.remove(member.id) else if (member.id !in next) next.add(member.id)
                            onSet(connector.readOnly, next)
                        }
                        SettingsRow(
                            title = member.name,
                            trailing = { Checkbox(checked = !off, onCheckedChange = { toggle() }) },
                            onClick = { toggle() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextButtonRow(label: String, onClick: () -> Unit) {
    Text(label, style = Dshm.typography.rowPreview, color = Dshm.colors.accent,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Dshm.spacing.sm))
}

@Composable
private fun OpenLinkRow(url: String, label: String? = null) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        }.padding(vertical = Dshm.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painter = painterResource(R.drawable.ic_lucide_external_link), contentDescription = null,
            tint = Dshm.colors.accent, modifier = Modifier.size(Dshm.spacing.icon))
        Spacer(Modifier.width(Dshm.spacing.sm))
        Text(label ?: url, style = Dshm.typography.rowPreview, color = Dshm.colors.accent,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------
// 邮箱
// ---------------------------------------------------------------------------

@Composable
private fun MailBoxScreen(
    mail: MailBox, busy: Boolean, error: String?, onBack: () -> Unit,
    onAdd: (String, String) -> Unit, onRemove: (String) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    SettingsPage(title = stringResource(R.string.team_connectors_mailbox), onBack = onBack) {
        error?.let { message ->
            item {
                Text(message, color = Dshm.colors.danger, style = Dshm.typography.rowPreview,
                    modifier = Modifier.padding(horizontal = Dshm.spacing.lg))
            }
        }
        item {
            SettingsSection(title = stringResource(R.string.team_connectors_mailbox)) {
                if (mail.accounts.isEmpty()) {
                    Text(stringResource(R.string.team_connectors_mail_no_accounts),
                        style = Dshm.typography.rowPreview, color = Dshm.colors.textTertiary)
                } else {
                    mail.accounts.forEachIndexed { index, account ->
                        if (index > 0) SettingsRowDivider()
                        MailAccountRowItem(account, busy, onRemove)
                    }
                }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Dshm.spacing.lg),
                horizontalArrangement = Arrangement.End) {
                SettingsSecondaryButton(text = stringResource(R.string.team_connectors_mail_add),
                    onClick = { adding = true }, fillWidth = false, onBackground = Dshm.colors.pageBackground)
            }
        }
        item {
            Text(stringResource(R.string.team_connectors_mail_hint), style = Dshm.typography.rowPreview,
                color = Dshm.colors.textTertiary, modifier = Modifier.padding(Dshm.spacing.lg))
        }
    }
    if (adding) {
        MailAddSheet(busy, { adding = false }) { address, password ->
            onAdd(address, password)
            adding = false
        }
    }
}

@Composable
private fun MailAddSheet(busy: Boolean, onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var address by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    SettingsSheet(title = stringResource(R.string.team_connectors_mail_add), onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm)) {
            SettingsInput(value = address, onValueChange = { address = it },
                placeholder = stringResource(R.string.team_connectors_mail_address))
            SettingsInput(value = password, onValueChange = { password = it },
                placeholder = stringResource(R.string.team_connectors_mail_password),
                visualTransformation = PasswordVisualTransformation())
            Text(stringResource(R.string.team_connectors_mail_hint), style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm)) {
                SettingsSecondaryButton(text = stringResource(R.string.team_connectors_cancel),
                    onClick = onDismiss, modifier = Modifier.weight(1f), fillWidth = false,
                    onBackground = Dshm.colors.surface)
                SettingsPrimaryButton(text = stringResource(R.string.team_connectors_mail_add),
                    enabled = !busy && address.isNotBlank() && password.isNotBlank(),
                    onClick = { onAdd(address.trim(), password.trim()) },
                    modifier = Modifier.weight(1f), fillWidth = false)
            }
        }
    }
}

@Composable
private fun MailAccountRowItem(account: MailAccountRow, busy: Boolean, onRemove: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painter = painterResource(R.drawable.ic_lucide_at_sign), contentDescription = null,
            tint = Dshm.colors.accent, modifier = Modifier.size(Dshm.spacing.icon))
        Spacer(Modifier.width(Dshm.spacing.md))
        Column(Modifier.weight(1f)) {
            Text(account.address, style = Dshm.typography.settingsRowTitle,
                color = Dshm.colors.textPrimary, maxLines = 1)
            Text(
                if (account.error != null) stringResource(R.string.team_connectors_error, account.error)
                else stringResource(R.string.team_connectors_connected),
                style = Dshm.typography.rowPreview,
                color = if (account.error != null) Dshm.colors.danger else Dshm.colors.textSecondary,
            )
        }
        Text(stringResource(R.string.team_connectors_remove), style = Dshm.typography.rowPreview,
            color = Dshm.colors.danger,
            modifier = Modifier.clickable(enabled = !busy) { onRemove(account.id) })
    }
}

// ---------------------------------------------------------------------------
// 自定义 MCP server
// ---------------------------------------------------------------------------

@Composable
private fun AddConnectorSheet(busy: Boolean, onDismiss: () -> Unit, onAdd: (String, String?, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    SettingsSheet(title = stringResource(R.string.team_connectors_add_custom), onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm)) {
            SettingsInput(value = name, onValueChange = { name = it },
                placeholder = stringResource(R.string.team_connectors_custom_name))
            SettingsInput(value = url, onValueChange = { url = it },
                placeholder = stringResource(R.string.team_connectors_custom_url))
            SettingsInput(value = command, onValueChange = { command = it },
                placeholder = stringResource(R.string.team_connectors_custom_command))
            Text(stringResource(R.string.team_connectors_custom_hint), style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm)) {
                SettingsSecondaryButton(text = stringResource(R.string.team_connectors_cancel),
                    onClick = onDismiss, modifier = Modifier.weight(1f), fillWidth = false,
                    onBackground = Dshm.colors.surface)
                SettingsPrimaryButton(text = stringResource(R.string.team_connectors_add_custom),
                    enabled = !busy && name.isNotBlank() && (url.isNotBlank() || command.isNotBlank()),
                    onClick = { onAdd(name.trim(), url.ifBlank { null }, command.ifBlank { null }) },
                    modifier = Modifier.weight(1f), fillWidth = false)
            }
        }
    }
}
