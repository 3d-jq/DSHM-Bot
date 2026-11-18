package com.dshm.bot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.pow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.core.design.HapticLevel
import com.dshm.bot.core.design.rememberHaptics
import com.dshm.bot.data.remote.ConnectorAuthKind
import com.dshm.bot.data.remote.ConnectorRow
import com.dshm.bot.data.remote.ConnectorsView
import com.dshm.bot.data.remote.MailAccountRow
import com.dshm.bot.data.remote.MailBox
import com.dshm.bot.data.runtime.RuntimeEndpoint
import com.dshm.bot.ui.components.FloatingIconButton
import org.json.JSONObject

/**
 * 连接器页（对齐 DS Bot 0.3.6 `ConnectorsPage`）：目录按分类成白卡 + 分类胶囊 + 连接，
 * 点开进详情（登录码 / token / 浏览器回执 / 只读 / 使用它的 Bot），邮箱单独一页。
 * 所有页面都是 `SettingsPage`（顶栏只有标题，全项目统一）。
 *
 * 2026-11-18 按用户要求重做视觉（原版「太丑」）：
 * - 「自定义 MCP 服务」从页面中间那颗按钮挪到**顶栏加号**；
 * - 去掉搜索框（目录只有几十项，分类胶囊够用）；
 * - 分类从 4 列等宽方格改成官方 `bt-app-chips` 那种自动宽度胶囊；
 * - 列表从裸行改成**白卡**（`SettingsSection`），分类名当卡标题；
 * - 一个都没连上时给一张白卡说明这里是干什么的；
 * - 行尾跟官方一样是状态胶囊（连接 / 已连接 / 重试 / 重新登录），重试就地执行。
 */
@Composable
fun ConnectorsScreen(endpoint: RuntimeEndpoint?, onBack: () -> Unit) {
    val viewModel: TeamSettingsViewModel = viewModel(
        key = "team-connectors:${endpoint?.baseUrl.orEmpty()}:${endpoint?.token.orEmpty()}",
        factory = TeamSettingsViewModel.factory(endpoint),
    )
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val view = state.connectorsView

    // 子页状态都要能活过 Activity 重建（原来一旋转/回前台就退回目录）。
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCustom by rememberSaveable { mutableStateOf(false) }
    var showMail by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 内嵌登录页：地址 + 是哪个连接器（登录完自动收起、回到详情）。
    var signInUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var signInConnectorId by rememberSaveable { mutableStateOf<String?>(null) }
    val done: (String?) -> Unit = { error = it }

    // 系统返回手势/返回键：详情 → 目录 → 设置，一层一层退。
    // 没有这一条，在详情页按返回会直接退出整个连接器页（用户 2026-11-18 报的
    //「深页返回没有回到上一级」）。
    BackHandler(enabled = signInUrl != null || showMail || detailId != null || showCustom) {
        when {
            signInUrl != null -> {
                signInUrl = null
                signInConnectorId = null
            }
            showCustom -> showCustom = false
            showMail -> showMail = false
            else -> detailId = null
        }
    }

    // 登录页开着的时候每两秒重读一次 state：服务端 302 直接打到宿主 loopback 时
    // 我们收不到任何事件，只能靠状态变化判断「已经连上了」。
    LaunchedEffect(signInUrl) {
        if (signInUrl == null) return@LaunchedEffect
        while (isActive) {
            delay(2_000)
            viewModel.load()
        }
    }
    // 连上了就自动收起登录页 —— 这就是「闭环」的收尾。
    LaunchedEffect(signInUrl, view.connectors) {
        val id = signInConnectorId ?: return@LaunchedEffect
        if (signInUrl == null) return@LaunchedEffect
        if (view.connectors.firstOrNull { it.id == id }?.connected == true) {
            signInUrl = null
            signInConnectorId = null
        }
    }

    val openLink: (String, String) -> Unit = { id, url ->
        signInConnectorId = id
        signInUrl = url
    }

    when {
        signInUrl != null -> ConnectorSignInScreen(
            title = view.connectors.firstOrNull { it.id == signInConnectorId }?.name
                ?: stringResource(R.string.team_connectors_title),
            url = signInUrl.orEmpty(),
            onCallback = { target ->
                val id = signInConnectorId
                if (id != null) {
                    viewModel.connectorSignInCode(id, target) { message ->
                        error = message
                        // 交回执成功：不用等轮询，直接收页回到详情（状态已被这次响应刷新）。
                        if (message == null) {
                            signInUrl = null
                            signInConnectorId = null
                        }
                    }
                }
            },
            onClose = {
                signInUrl = null
                signInConnectorId = null
            },
        )
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
                        onOpenLink = { url -> openLink(connector.id, url) },
                    )
                }
            }
        }
        else -> ConnectorDirectoryScreen(
            view = view, error = state.error ?: error, busy = state.busy,
            onOpen = { detailId = it },
            onRetry = { viewModel.connectorRetry(it, done) },
            onCustom = { showCustom = true }, onBack = onBack,
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

/** 目录里的一个「伪分类」：只看已经连上的（官方 `ConnectedSummary` 那个入口）。 */
private const val CATEGORY_CONNECTED = "__connected__"
private val KNOWN_CATEGORIES = setOf("dev", "docs", "work", "design", "data", "commerce", "search")

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ConnectorDirectoryScreen(
    view: ConnectorsView, error: String?, busy: Boolean,
    onOpen: (String) -> Unit, onRetry: (String) -> Unit,
    onCustom: () -> Unit, onBack: () -> Unit,
) {
    var category by remember { mutableStateOf("") }
    val apps = remember(view) {
        listOf(view.mailboxCard()) + view.connectors.filter { it.id != ConnectorsView.MAIL_ID }
    }
    // 卡里：已连接的排前面（一眼看到什么是通的），其余按名字。
    val ordered = remember(apps) {
        apps.sortedWith(
            compareByDescending<ConnectorRow> { it.on || it.pending != null }
                .thenBy { it.name.lowercase() },
        )
    }
    val connectedCount = ordered.count { it.connected || it.pending != null }
    val connectedRows = ordered.filter { it.connected || it.pending != null }
    // 已经断开光了还停在「已连接」上：退回「全部」，不要留一张空卡。
    val active = if (category == CATEGORY_CONNECTED && connectedRows.isEmpty()) "" else category

    // 分组：选中分类 → 只一张卡；「全部」→ 推荐 + 各分类（和官方一样，推荐里的
    // 服务在下面它自己的分类里还会再出现一次）。
    val groups: List<Pair<Int, List<ConnectorRow>>> = if (active == CATEGORY_CONNECTED) {
        listOf(R.string.team_connectors_connected to connectedRows)
    } else if (active.isNotEmpty()) {
        listOf(
            (CATEGORIES.first { it.first == active }.second) to ordered.filter { inCategory(it, active) },
        )
    } else {
        buildList {
            val featured = FEATURED.mapNotNull { id -> ordered.firstOrNull { it.id == id } }
            if (featured.isNotEmpty()) add(R.string.team_connectors_recommended to featured)
            for ((id, label) in CATEGORIES.drop(1)) {
                val rows = ordered.filter { inCategory(it, id) }
                if (rows.isNotEmpty()) add(label to rows)
            }
        }
    }

    SettingsPage(
        title = stringResource(R.string.team_connectors_title),
        onBack = onBack,
        actions = {
            // 自定义 MCP 服务：顶栏加号（原来是页面中间一颗孤零零的「自定义」按钮）。
            FloatingIconButton(
                icon = R.drawable.ic_lucide_plus,
                contentDescription = stringResource(R.string.team_connectors_add_custom),
                onClick = onCustom,
            )
        },
    ) {
        error?.let { message ->
            item { SettingsError(message) }
        }
        // 分类：自动宽度的胶囊，换行排（官方 `bt-app-chips`）。
        if (apps.size > 1) {
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                ) {
                    CATEGORIES.forEach { (id, label) ->
                        SettingsChoiceCapsule(
                            label = stringResource(label),
                            selected = active == id,
                            onClick = { category = id },
                        )
                    }
                    // 已连接的排最前（官方把它做成右上角的「N connected」入口）：
                    // 连了几个、都是哪些，点一下就能单独看。
                    if (connectedCount > 0) {
                        SettingsChoiceCapsule(
                            label = stringResource(R.string.team_connectors_connected_count, connectedCount),
                            selected = active == CATEGORY_CONNECTED,
                            onClick = { category = CATEGORY_CONNECTED },
                        )
                    }
                }
            }
        }

        if (apps.isEmpty()) {
            // 宿主还没给目录（或整个目录是空的）：白卡，不再是裸文本。
            item { SettingsEmpty(stringResource(R.string.team_connectors_empty_title)) }
        } else {
            groups.forEach { (titleRes, rows) ->
                item(key = "group:$titleRes") {
                    if (rows.isEmpty()) {
                        SettingsEmpty(stringResource(R.string.team_connectors_empty_search))
                    } else {
                        SettingsSection(title = stringResource(titleRes)) {
                            rows.forEachIndexed { index, connector ->
                                if (index > 0) SettingsRowDivider()
                                ConnectorTile(connector, busy, onOpen, onRetry)
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.team_connectors_note),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textTertiary,
                modifier = Modifier.padding(horizontal = Dshm.spacing.sm),
            )
        }
    }
}

private fun inCategory(connector: ConnectorRow, category: String): Boolean = when {
    category.isEmpty() -> true
    category == "other" -> connector.category !in KNOWN_CATEGORIES
    else -> connector.category == category
}

/** 目录里的一行：图标 + 名字/状态 + 行尾状态胶囊（和官方 `bt-app` 同构）。 */
@Composable
private fun ConnectorTile(
    connector: ConnectorRow,
    busy: Boolean,
    onOpen: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    val tag: (@Composable () -> Unit)? = if (connector.custom) {
        { CustomTag() }
    } else {
        null
    }
    val pill: @Composable () -> Unit = {
        ConnectorPill(
            connector = connector, busy = busy,
            onOpen = { onOpen(connector.id) },
            onRetry = { onRetry(connector.id) },
        )
    }
    SettingsRow(
        title = connector.name,
        leading = { ConnectorMark(connector) },
        badge = tag,
        subtitle = connectorStatus(connector),
        subtitleColor = if (connector.failed) Dshm.colors.danger else null,
        trailing = pill,
        onClick = { onOpen(connector.id) },
    )
}

/**
 * 行尾的状态胶囊（官方 `bt-app-btn` / `bt-app-btn-done`）：
 * 未连接是安静的一颗灰胶囊，已连接换成描边 + 对勾，连不动的时候不可点。
 *
 * 重试就地执行（这一条不需要表单）；其余需要表单/登录的交给详情页。
 */
@Composable
private fun ConnectorPill(
    connector: ConnectorRow,
    busy: Boolean,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
) {
    val done = connector.connected
    val labelRes = when {
        connector.connecting -> R.string.team_connectors_connecting
        done -> R.string.team_connectors_connected
        connector.failed -> R.string.team_connectors_retry
        connector.needsSignIn -> R.string.team_connectors_sign_in_again
        else -> R.string.team_connectors_connect
    }
    // 连不动的两态（连接中 / 已连接）不接点击；出错就地重试，其余进详情
    // （要表单或登录的都在那一页，和官方 `bt-app-btn` 的 press() 同一条分流）。
    val action: (() -> Unit)? = when {
        connector.connecting || done -> null
        connector.failed -> onRetry
        else -> onOpen
    }
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(Dshm.radius.pill)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (done) Color.Transparent else Dshm.colors.surfaceMuted)
            .then(
                if (done) Modifier.border(Dshm.spacing.hairline, Dshm.colors.divider, shape)
                else Modifier,
            )
            .then(
                if (action != null && !busy) {
                    Modifier.clickable {
                        haptics(HapticLevel.LIGHT)
                        action()
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (done) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_check),
                contentDescription = null,
                tint = Dshm.colors.textSecondary,
                modifier = Modifier.size(Dshm.spacing.md),
            )
            Spacer(Modifier.width(Dshm.spacing.xxs))
        }
        Text(
            text = stringResource(labelRes),
            style = Dshm.typography.rowPreview,
            color = if (done) Dshm.colors.textSecondary else Dshm.colors.textPrimary,
            maxLines = 1,
        )
    }
}

/** 自定义连接器的「已添加」小标签。 */
@Composable
private fun CustomTag() {
    Text(
        text = stringResource(R.string.team_connectors_added),
        style = Dshm.typography.rowTime,
        color = Dshm.colors.textTertiary,
        modifier = Modifier
            .clip(RoundedCornerShape(Dshm.radius.pill))
            .background(Dshm.colors.surfaceMuted)
            .padding(horizontal = Dshm.spacing.xs, vertical = Dshm.spacing.xxs),
    )
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

/**
 * 一个连接器的品牌外观：官方图形 + 品牌色。
 *
 * 色值取自官方品牌色（和 DeepSearch `nanomuse/connectors.json` 的 `color` 同源），
 * **不改成我们的主色** —— 用户 2026-11-18 明确：「保持人家原样子」。
 */
internal class ConnectorBrand(val mark: Int, val color: Long)

/**
 * 连接器 id → 品牌图形与品牌色；没有官方件的返回 null。
 *
 * 源文件在 `design/connector-icons/`，由 `tools/build_connector_icons.py` 生成到
 * `res/drawable/ic_connector_*`（改了那边要重跑脚本）。表里没有的（Zapier、DeepWiki、
 * Context7、高德）退回 [connectorFallbackIcon]。
 */
internal val CONNECTOR_BRANDS: Map<String, ConnectorBrand> = mapOf(
    "github" to ConnectorBrand(R.drawable.ic_connector_github, 0xFF181717),
    "notion" to ConnectorBrand(R.drawable.ic_connector_notion, 0xFF000000),
    "linear" to ConnectorBrand(R.drawable.ic_connector_linear, 0xFF5E6AD2),
    "atlassian" to ConnectorBrand(R.drawable.ic_connector_atlassian, 0xFF0052CC),
    "sentry" to ConnectorBrand(R.drawable.ic_connector_sentry, 0xFF362D59),
    "stripe" to ConnectorBrand(R.drawable.ic_connector_stripe, 0xFF635BFF),
    "paypal" to ConnectorBrand(R.drawable.ic_connector_paypal, 0xFF003087),
    "square" to ConnectorBrand(R.drawable.ic_connector_square, 0xFF3E4348),
    "cloudflare" to ConnectorBrand(R.drawable.ic_connector_cloudflare, 0xFFF38020),
    "cloudflare-docs" to ConnectorBrand(R.drawable.ic_connector_cloudflare, 0xFFF38020),
    "vercel" to ConnectorBrand(R.drawable.ic_connector_vercel, 0xFF000000),
    "supabase" to ConnectorBrand(R.drawable.ic_connector_supabase, 0xFF3FCF8E),
    "neon" to ConnectorBrand(R.drawable.ic_connector_neon, 0xFF00E599),
    "figma" to ConnectorBrand(R.drawable.ic_connector_figma, 0xFFF24E1E),
    "canva" to ConnectorBrand(R.drawable.ic_connector_canva, 0xFF00C4CC),
    "intercom" to ConnectorBrand(R.drawable.ic_connector_intercom, 0xFF6AFDEF),
    "monday" to ConnectorBrand(R.drawable.ic_connector_monday, 0xFFFF3D57),
    "webflow" to ConnectorBrand(R.drawable.ic_connector_webflow, 0xFF146EF5),
    "wix" to ConnectorBrand(R.drawable.ic_connector_wix, 0xFF0C6EFC),
    "netlify" to ConnectorBrand(R.drawable.ic_connector_netlify, 0xFF00C7B7),
    "airtable" to ConnectorBrand(R.drawable.ic_connector_airtable, 0xFF18BFFF),
    "dropbox" to ConnectorBrand(R.drawable.ic_connector_dropbox, 0xFF0061FF),
    "clickup" to ConnectorBrand(R.drawable.ic_connector_clickup, 0xFF7B68EE),
    "todoist" to ConnectorBrand(R.drawable.ic_connector_todoist, 0xFFE44332),
    "miro" to ConnectorBrand(R.drawable.ic_connector_miro, 0xFFFFD02F),
    "attio" to ConnectorBrand(R.drawable.ic_connector_attio, 0xFF1C1C1C),
    "postman" to ConnectorBrand(R.drawable.ic_connector_postman, 0xFFFF6C37),
    "asana" to ConnectorBrand(R.drawable.ic_connector_asana, 0xFFF06A6A),
    "box" to ConnectorBrand(R.drawable.ic_connector_box, 0xFF0061D5),
    "hubspot" to ConnectorBrand(R.drawable.ic_connector_hubspot, 0xFFFF7A59),
    "slack" to ConnectorBrand(R.drawable.ic_connector_slack, 0xFF4A154B),
    "huggingface" to ConnectorBrand(R.drawable.ic_connector_huggingface, 0xFFFFD21E),
    "exa" to ConnectorBrand(R.drawable.ic_connector_exa, 0xFF1F2937),
    "mslearn" to ConnectorBrand(R.drawable.ic_connector_microsoft, 0xFF0078D4),
)

/**
 * 连接器的图标块：
 * - 已连接：**品牌色底 + 能看清的墨色图形**（和官方 App 的图标观感一致）；
 * - 未连接：灰底灰图形（官方 `bt-app:not([data-state=ready]) .bt-app-icon` 的灰度）；
 * - 没有官方件的：中性底 + 通用图标（连接后映主色）。
 */
@Composable
private fun ConnectorMark(connector: ConnectorRow, size: Dp = Dshm.spacing.avatar) {
    val on = connector.connected
    val brand = CONNECTOR_BRANDS[connector.id]
    val shape = RoundedCornerShape(Dshm.radius.md)
    if (brand != null) {
        val tile = if (on) Color(brand.color) else Dshm.colors.surfaceMuted
        val ink = if (on) readableInkOn(Color(brand.color)) else Dshm.colors.textSecondary
        Box(
            modifier = Modifier.size(size).clip(shape).background(tile),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(brand.mark),
                contentDescription = null,
                tint = ink,
                // 品牌件大多满幅（monday 是 256×156 的横条），留一成半的边。
                modifier = Modifier.size(size * 0.58f),
            )
        }
        return
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(if (on) Dshm.colors.accent.copy(alpha = 0.12f) else Dshm.colors.surfaceMuted),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(connectorFallbackIcon(connector)),
            contentDescription = null,
            tint = if (on) Dshm.colors.accent else Dshm.colors.textSecondary,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** 品牌色底上画图形用哪种墨：白字还是近黑，取对比度更高的那个。 */
internal fun readableInkOn(brand: Color): Color =
    if (contrastRatio(brand, Color.White) >= contrastRatio(brand, BrandInkDark)) Color.White else BrandInkDark

/** 亮品牌色（Miro 的黄、Hugging Face 的黄）上的近黑墨。 */
private val BrandInkDark = Color(0xFF101418)

/** WCAG 相对亮度。 */
private fun relativeLuminance(color: Color): Float {
    fun channel(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)
}

/** WCAG 对比度（1..21）。 */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** 没有官方件时的兜底：邮箱用 @、自定义服务用拼图、其余用地球。 */
private fun connectorFallbackIcon(connector: ConnectorRow): Int = when {
    connector.id == ConnectorsView.MAIL_ID -> R.drawable.ic_lucide_at_sign
    connector.custom -> R.drawable.ic_lucide_puzzle
    else -> R.drawable.ic_lucide_globe
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
    /** 在**应用内**打开一个链接（登录页 / 验证页 / key 说明）；不再丢给系统浏览器。 */
    onOpenLink: (String) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var browserInput by remember { mutableStateOf("") }
    var showPaste by remember { mutableStateOf(false) }
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    val pending = connector.pending

    // 点「连接 / 重新登录」：不需要表单的就直接发起，需要输入 key/token 的翻出输入区。
    val startConnect: () -> Unit = {
        when (connector.auth) {
            ConnectorAuthKind.KEY -> showToken = true
            ConnectorAuthKind.OAUTH ->
                if (connector.authToken && connector.authClientId == null) showToken = true
                else onConnect(JSONObject())
            else -> onConnect(JSONObject())
        }
    }

    SettingsPage(title = connector.name, onBack = onBack) {
        error?.let { message ->
            item { SettingsError(message) }
        }

        // 身份卡：图标 + 名字 + 状态 + 工具数；说明文字挂在卡下方（`footer`）。
        item {
            SettingsSection(footer = connector.about.takeIf { it.isNotBlank() }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(Dshm.spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ConnectorMark(connector, size = Dshm.spacing.settingsAvatar)
                    Spacer(Modifier.width(Dshm.spacing.md))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = connector.name,
                                style = Dshm.typography.settingsRowTitle,
                                color = Dshm.colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (connector.custom) {
                                Spacer(Modifier.width(Dshm.spacing.xs))
                                CustomTag()
                            }
                        }
                        Text(
                            text = connectorStatus(connector),
                            style = Dshm.typography.settingsRowSubtitle,
                            color = if (connector.failed) Dshm.colors.danger else Dshm.colors.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (connector.tools > 0) {
                            Text(
                                text = stringResource(R.string.team_connectors_tools, connector.tools),
                                style = Dshm.typography.rowTime,
                                color = Dshm.colors.textTertiary,
                            )
                        }
                    }
                }
            }
        }

        if (pending?.isBrowser == true) {
            item {
                FormSection(title = stringResource(R.string.team_connectors_waiting_sign_in)) {
                    Text(
                        text = stringResource(R.string.team_connectors_browser_hint),
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.textSecondary,
                    )
                    pending.url?.let { url ->
                        SettingsPrimaryButton(
                            text = stringResource(R.string.team_connectors_open_in_app),
                            enabled = !busy,
                            onClick = { onOpenLink(url) },
                        )
                    }
                    if (showPaste) {
                        SettingsInput(
                            value = browserInput,
                            onValueChange = { browserInput = it },
                            placeholder = stringResource(R.string.team_connectors_paste_address),
                        )
                        SettingsPrimaryButton(
                            text = stringResource(R.string.team_connectors_finish_sign_in),
                            enabled = !busy && browserInput.isNotBlank(),
                            onClick = { onSignInCode(browserInput) },
                        )
                    } else {
                        TextButtonRow(stringResource(R.string.team_connectors_did_not_return)) { showPaste = true }
                    }
                    TextButtonRow(stringResource(R.string.team_connectors_cancel)) { onCancel() }
                }
            }
        }

        if (pending?.isDevice == true) {
            item {
                FormSection(title = stringResource(R.string.team_connectors_waiting_sign_in)) {
                    Text(
                        text = pending.userCode.orEmpty(),
                        style = Dshm.typography.pageTitle,
                        color = Dshm.colors.accent,
                    )
                    pending.verificationUri?.let { url ->
                        Text(
                            text = stringResource(R.string.team_connectors_device_hint, url),
                            style = Dshm.typography.rowPreview,
                            color = Dshm.colors.textSecondary,
                        )
                        SettingsPrimaryButton(
                            text = stringResource(R.string.team_connectors_open_in_app),
                            enabled = !busy,
                            onClick = { onOpenLink(url) },
                        )
                    }
                    TextButtonRow(stringResource(R.string.team_connectors_cancel)) { onCancel() }
                }
            }
        }

        if (connector.needsClient && pending == null) {
            item {
                FormSection(title = stringResource(R.string.team_connectors_needs_client)) {
                    SettingsInput(
                        value = clientId,
                        onValueChange = { clientId = it },
                        placeholder = stringResource(R.string.team_connectors_client_id),
                    )
                    SettingsInput(
                        value = clientSecret,
                        onValueChange = { clientSecret = it },
                        placeholder = stringResource(R.string.team_connectors_client_secret),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    SettingsPrimaryButton(
                        text = stringResource(R.string.team_connectors_connect),
                        enabled = !busy && clientId.isNotBlank(),
                        onClick = { onClient(clientId, clientSecret) },
                    )
                }
            }
        }

        if (showToken && pending == null) {
            item {
                FormSection(title = stringResource(R.string.team_connectors_connect_title, connector.name)) {
                    SettingsInput(
                        value = token, onValueChange = { token = it },
                        placeholder = stringResource(
                            if (connector.auth == ConnectorAuthKind.KEY) R.string.team_connectors_key
                            else R.string.team_connectors_token, connector.name,
                        ),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    connector.keyUrl?.let { url ->
                        TextButtonRow(stringResource(R.string.team_connectors_get_key)) { onOpenLink(url) }
                    }
                    SettingsPrimaryButton(
                        text = stringResource(R.string.team_connectors_connect),
                        enabled = !busy && token.isNotBlank(),
                        onClick = {
                            val body = JSONObject()
                            if (connector.auth == ConnectorAuthKind.KEY) body.put("key", token.trim())
                            else body.put("token", token.trim())
                            onConnect(body)
                        },
                    )
                    TextButtonRow(stringResource(R.string.team_connectors_cancel)) { showToken = false }
                }
            }
        }

        // 主操作：连 / 重连 / 断开（和「删除渠道」一样，动作按钮放在单独一张卡里）。
        if (pending == null && !connector.needsClient && !showToken) {
            item {
                SettingsSection {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(Dshm.spacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                    ) {
                        when {
                            connector.connected -> SettingsDangerButton(
                                text = stringResource(R.string.team_connectors_disconnect),
                                enabled = !busy,
                                onClick = onDisconnect,
                            )

                            connector.failed -> SettingsPrimaryButton(
                                text = stringResource(R.string.team_connectors_retry),
                                enabled = !busy,
                                onClick = onRetry,
                            )

                            connector.needsSignIn -> SettingsPrimaryButton(
                                text = stringResource(R.string.team_connectors_sign_in_again),
                                enabled = !busy,
                                onClick = startConnect,
                            )

                            else -> SettingsPrimaryButton(
                                text = stringResource(R.string.team_connectors_connect),
                                enabled = !busy,
                                onClick = startConnect,
                            )
                        }
                        // GitHub 的 OAuth 也允许直接贴 token（官方同样是「改用 token」）。
                        if (connector.id == "github" && !connector.connected && connector.auth == ConnectorAuthKind.OAUTH) {
                            TextButtonRow(
                                label = stringResource(R.string.team_connectors_use_token),
                                onClick = { showToken = true },
                                centered = true,
                            )
                        }
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

        // 自定义连接器：移除单独一张卡（和「删除模型渠道」同款）。
        if (connector.custom) {
            item {
                SettingsSection {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(Dshm.spacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        SettingsDangerButton(
                            text = stringResource(R.string.team_connectors_remove),
                            icon = R.drawable.ic_lucide_trash_2,
                            enabled = !busy,
                            onClick = onRemove,
                        )
                    }
                }
            }
        }
    }
}

/** 详情页里的表单卡：内容统一 16dp 内边距 + 8dp 行距（裸放输入框会贴到卡片边缘）。 */
@Composable
private fun FormSection(
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsSection(title = title, footer = footer) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Dshm.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
            content = content,
        )
    }
}

@Composable
private fun TextButtonRow(label: String, centered: Boolean = false, onClick: () -> Unit) {
    Text(
        text = label,
        style = Dshm.typography.rowPreview,
        color = Dshm.colors.accent,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Dshm.spacing.sm),
        textAlign = if (centered) androidx.compose.ui.text.style.TextAlign.Center else null,
    )
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
    SettingsPage(
        title = stringResource(R.string.team_connectors_mailbox),
        onBack = onBack,
        actions = {
            // 「添加邮箱」也挪到顶栏加号：和连接器目录的自定义同一个位置。
            FloatingIconButton(
                icon = R.drawable.ic_lucide_plus,
                contentDescription = stringResource(R.string.team_connectors_mail_add),
                onClick = { adding = true },
            )
        },
    ) {
        error?.let { message ->
            item { SettingsError(message) }
        }
        item {
            SettingsSection(footer = stringResource(R.string.team_connectors_mail_hint)) {
                if (mail.accounts.isEmpty()) {
                    Text(
                        text = stringResource(R.string.team_connectors_mail_no_accounts),
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.textSecondary,
                        modifier = Modifier.padding(Dshm.spacing.lg),
                    )
                } else {
                    mail.accounts.forEachIndexed { index, account ->
                        if (index > 0) SettingsRowDivider()
                        MailAccountRowItem(account, busy, onRemove)
                    }
                }
            }
        }
        item {
            // 邮箱也能收、也能发：说明一下权限边界（官方 `MailRules` 那段）。
            Text(
                text = stringResource(R.string.team_connectors_mail_note),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textTertiary,
                modifier = Modifier.padding(horizontal = Dshm.spacing.sm),
            )
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
            SettingsInput(
                value = address, onValueChange = { address = it },
                placeholder = stringResource(R.string.team_connectors_mail_address),
            )
            SettingsInput(
                value = password, onValueChange = { password = it },
                placeholder = stringResource(R.string.team_connectors_mail_password),
                visualTransformation = PasswordVisualTransformation(),
            )
            Text(
                text = stringResource(R.string.team_connectors_mail_hint),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm)) {
                SettingsSecondaryButton(
                    text = stringResource(R.string.team_connectors_cancel),
                    onClick = onDismiss, modifier = Modifier.weight(1f), fillWidth = false,
                    onBackground = Dshm.colors.surface,
                )
                SettingsPrimaryButton(
                    text = stringResource(R.string.team_connectors_mail_add),
                    enabled = !busy && address.isNotBlank() && password.isNotBlank(),
                    onClick = { onAdd(address.trim(), password.trim()) },
                    modifier = Modifier.weight(1f), fillWidth = false,
                )
            }
        }
    }
}

@Composable
private fun MailAccountRowItem(account: MailAccountRow, busy: Boolean, onRemove: (String) -> Unit) {
    SettingsRow(
        title = account.address,
        leading = {
            Box(
                modifier = Modifier
                    .size(Dshm.spacing.avatar)
                    .clip(RoundedCornerShape(Dshm.radius.md))
                    .background(
                        if (account.error == null) Dshm.colors.accent.copy(alpha = 0.12f)
                        else Dshm.colors.surfaceMuted,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_at_sign),
                    contentDescription = null,
                    tint = if (account.error == null) Dshm.colors.accent else Dshm.colors.textSecondary,
                    modifier = Modifier.size(Dshm.spacing.icon),
                )
            }
        },
        subtitle = if (account.error != null) {
            stringResource(R.string.team_connectors_error, account.error)
        } else {
            stringResource(R.string.team_connectors_connected)
        },
        subtitleColor = if (account.error != null) Dshm.colors.danger else null,
        trailing = {
            Text(
                text = stringResource(R.string.team_connectors_remove),
                style = Dshm.typography.rowPreview,
                color = if (busy) Dshm.colors.textTertiary else Dshm.colors.danger,
                modifier = Modifier
                    .clip(RoundedCornerShape(Dshm.radius.pill))
                    .clickable(enabled = !busy) { onRemove(account.id) }
                    .padding(horizontal = Dshm.spacing.sm, vertical = Dshm.spacing.xs),
            )
        },
    )
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
            SettingsInput(
                value = name, onValueChange = { name = it },
                placeholder = stringResource(R.string.team_connectors_custom_name),
            )
            SettingsInput(
                value = url, onValueChange = { url = it },
                placeholder = stringResource(R.string.team_connectors_custom_url),
            )
            SettingsInput(
                value = command, onValueChange = { command = it },
                placeholder = stringResource(R.string.team_connectors_custom_command),
            )
            Text(
                text = stringResource(R.string.team_connectors_custom_hint),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm)) {
                SettingsSecondaryButton(
                    text = stringResource(R.string.team_connectors_cancel),
                    onClick = onDismiss, modifier = Modifier.weight(1f), fillWidth = false,
                    onBackground = Dshm.colors.surface,
                )
                SettingsPrimaryButton(
                    text = stringResource(R.string.team_connectors_add_custom),
                    enabled = busy.not() && name.isNotBlank() && (url.isNotBlank() || command.isNotBlank()),
                    onClick = { onAdd(name.trim(), url.ifBlank { null }, command.ifBlank { null }) },
                    modifier = Modifier.weight(1f), fillWidth = false,
                )
            }
        }
    }
}
