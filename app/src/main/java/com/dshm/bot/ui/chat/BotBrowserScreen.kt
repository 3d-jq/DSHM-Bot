package com.dshm.bot.ui.chat

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.ui.components.FloatingIconButton

/**
 * Bot 的浏览器：全屏页。面板里的「浏览器」分类直接内嵌 [BotBrowserPanel]，这一页是它的
 * 独立入口（返回键回聊天）。
 */
@Composable
fun BotBrowserScreen(
    botId: String?,
    botName: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember { BotBrowserStore.of(context) }
    val session = remember(botId) { botId?.let { store.session(it) } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Dshm.colors.pageBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        BrowserTopBar(
            title = session?.activeTab()?.title?.takeIf { it.isNotBlank() }
                ?: botName?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.browser_title),
            onBack = onBack,
            onReload = session?.let { current -> { current.reload() } },
            reloadEnabled = session?.activeTab() != null,
        )
        BotBrowserPanel(
            session = session,
            modifier = Modifier.weight(1f),
            botName = botName.orEmpty(),
        )
    }
}

/**
 * 浏览器本体：地址栏 + 页面 + 底栏（标签页在底栏的「n/6」里）。
 *
 * 既给全屏页用，也给 Bot 面板的「浏览器」分类内嵌用 —— 面板里直接就是浏览器，
 * 不是「打开浏览器」按钮（DeepSearch 的 `BrowserSheet` 也是这么做的）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BotBrowserPanel(
    session: BotBrowserSession?,
    modifier: Modifier = Modifier,
    onExpand: (() -> Unit)? = null,
    botName: String = "",
) {
    val context = LocalContext.current
    val tab = session?.activeTab()
    var draft by remember(session?.activeId) { mutableStateOf(tab?.url.orEmpty()) }
    var showTabs by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(session) {
        session?.let { current ->
            if (current.tabs.isEmpty()) current.newTab()
        }
    }
    LaunchedEffect(tab?.url) {
        val url = tab?.url.orEmpty()
        if (url.isNotEmpty()) draft = url
    }
    // 下载进度：跟着 DownloadManager 轮询，完成后再留两秒。
    LaunchedEffect(session?.download?.id) {
        val current = session ?: return@LaunchedEffect
        val id = current.download?.id ?: return@LaunchedEffect
        while (true) {
            val status = current.downloadStatus(id) ?: break
            current.updateDownload(status)
            if (status.done) {
                kotlinx.coroutines.delay(2_500)
                current.updateDownload(null)
                break
            }
            kotlinx.coroutines.delay(500)
        }
    }

    Column(modifier.fillMaxWidth()) {
        UrlBar(
            value = draft,
            onValueChange = { draft = it },
            onSubmit = { session?.load(draft) },
            enabled = session != null,
            onExpand = onExpand,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = Dshm.spacing.sm)
                .clip(RoundedCornerShape(Dshm.radius.md))
                .background(Dshm.colors.surface),
        ) {
            val webView = session?.activeWebView()
            if (session == null) {
                BrowserHint(stringResource(R.string.browser_no_bot))
            } else if (webView == null) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BrowserHint(stringResource(R.string.browser_empty_hint))
                    TextButton(onClick = { session.newTab() }) {
                        Text(stringResource(R.string.browser_new_tab))
                    }
                }
            } else {
                BrowserWebArea(webView = webView, modifier = Modifier.fillMaxSize())
            }
            if (session?.agentBusy == true) {
                AgentBrowsingOverlay(
                    botName = botName.ifBlank { "Bot" },
                    onTakeover = { session.cancelAgent() },
                )
            }
        }
        session?.handOverReason?.let { reason ->
            HandOverBanner(reason = reason, onDone = { session.finishHandOver() })
        }
        session?.download?.let { entry -> DownloadBanner(entry) }
        // 页面和底栏之间留一口气，贴着看太挤。
        Spacer(Modifier.height(Dshm.spacing.sm))
        BrowserBottomBar(
            canGoBack = session?.canGoBack == true,
            canGoForward = session?.canGoForward == true,
            enabled = session != null && tab != null,
            tabCount = session?.tabs?.size ?: 0,
            onBackPage = { session?.goBack() },
            onForward = { session?.goForward() },
            onReload = { session?.reload() },
            onTabs = { if (session != null && session.tabs.isNotEmpty()) showTabs = true },
            onNewTab = { session?.newTab() },
            onCloseTab = { session?.activeId?.let { session.closeTab(it) } },
            onOpenExternal = {
                val url = tab?.url
                if (!url.isNullOrEmpty()) {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            },
        )
    }

    if (showTabs && session != null) {
        ModalBottomSheet(
            onDismissRequest = { showTabs = false },
            sheetState = sheetState,
            containerColor = Dshm.colors.pageBackground,
            shape = RoundedCornerShape(topStart = Dshm.radius.xl, topEnd = Dshm.radius.xl),
            scrimColor = Dshm.colors.scrim,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dshm.spacing.lg)
                    .padding(bottom = Dshm.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xs),
            ) {
                Text(
                    text = stringResource(R.string.browser_tabs),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
                session.tabs.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Dshm.radius.sm))
                            .background(
                                if (entry.id == session.activeId) {
                                    Dshm.colors.accent.copy(alpha = 0.1f)
                                } else {
                                    Dshm.colors.surface
                                },
                            )
                            .clickable {
                                session.select(entry.id)
                                showTabs = false
                            }
                            .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = entry.title.takeIf { it.isNotBlank() }
                                    ?: hostOf(entry.url)
                                    ?: stringResource(R.string.browser_new_tab),
                                style = Dshm.typography.settingsRowTitle,
                                color = Dshm.colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (entry.url.isNotEmpty()) {
                                Text(
                                    text = entry.url,
                                    style = Dshm.typography.rowTime,
                                    color = Dshm.colors.textTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        BrowserIcon(
                            icon = R.drawable.ic_lucide_trash_2,
                            description = stringResource(R.string.browser_close_tab),
                            onClick = { session.closeTab(entry.id) },
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Dshm.radius.sm))
                        .background(Dshm.colors.surface)
                        .clickable { session.newTab(); showTabs = false }
                        .padding(Dshm.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_plus),
                        contentDescription = null,
                        tint = Dshm.colors.accent,
                        modifier = Modifier.size(Dshm.spacing.settingsRowIcon),
                    )
                    Spacer(Modifier.size(Dshm.spacing.sm))
                    Text(
                        text = stringResource(R.string.browser_new_tab),
                        style = Dshm.typography.settingsRowTitle,
                        color = Dshm.colors.accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowserTopBar(
    title: String,
    onBack: () -> Unit,
    onReload: (() -> Unit)? = null,
    reloadEnabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
    ) {
        FloatingIconButton(
            icon = R.drawable.ic_lucide_arrow_left,
            contentDescription = stringResource(R.string.action_back),
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Text(
            text = title,
            style = Dshm.typography.pageTitle,
            color = Dshm.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = Dshm.spacing.iconButton + Dshm.spacing.lg),
        )
        if (onReload != null) {
            FloatingIconButton(
                icon = R.drawable.ic_lucide_refresh_cw,
                contentDescription = stringResource(R.string.browser_reload),
                onClick = onReload,
                enabled = reloadEnabled,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@Composable
private fun UrlBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean,
    onExpand: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dshm.spacing.sm, vertical = Dshm.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 「全屏」跟右边的「前往」一样是文字键，左右各一个，和地址框等高对齐。
        if (onExpand != null) {
            TextButton(enabled = enabled, onClick = onExpand) {
                Text(stringResource(R.string.browser_expand))
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(Dshm.radius.pill))
                .background(Dshm.colors.surface)
                .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_globe),
                contentDescription = null,
                tint = Dshm.colors.textTertiary,
                modifier = Modifier.size(Dshm.spacing.settingsRowIcon),
            )
            Spacer(Modifier.size(Dshm.spacing.sm))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = Dshm.typography.settingsRowValue.copy(color = Dshm.colors.textPrimary),
                cursorBrush = SolidColor(Dshm.colors.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = stringResource(R.string.browser_address_hint),
                                style = Dshm.typography.settingsRowValue,
                                color = Dshm.colors.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        TextButton(enabled = enabled, onClick = onSubmit) {
            Text(stringResource(R.string.browser_go))
        }
    }
}

@Composable
private fun BrowserBottomBar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    enabled: Boolean,
    tabCount: Int,
    onBackPage: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onTabs: () -> Unit,
    onNewTab: () -> Unit,
    onCloseTab: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    // 白卡 + 等宽槽位：每个工具在自己那一格里居中，左右两边格数一样，视觉上是中心对称的。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dshm.spacing.sm),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Dshm.radius.md))
                .background(Dshm.colors.surface)
                .padding(vertical = Dshm.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarSlot(Modifier.weight(1f)) {
                BrowserIcon(
                    icon = R.drawable.ic_lucide_arrow_left,
                    description = stringResource(R.string.browser_back),
                    enabled = enabled && canGoBack,
                    onClick = onBackPage,
                )
            }
            BarSlot(Modifier.weight(1f)) {
                BrowserIcon(
                    icon = R.drawable.ic_lucide_chevron_right,
                    description = stringResource(R.string.browser_forward),
                    enabled = enabled && canGoForward,
                    onClick = onForward,
                )
            }
            BarSlot(Modifier.weight(1f)) {
                BrowserIcon(
                    icon = R.drawable.ic_lucide_refresh_cw,
                    description = stringResource(R.string.browser_reload),
                    enabled = enabled,
                    onClick = onReload,
                )
            }
            BarSlot(Modifier.weight(1f)) {
                Text(
                    text = "$tabCount/${BotBrowserSession.MAX_TABS}",
                    style = Dshm.typography.rowPreview,
                    color = if (enabled) Dshm.colors.textSecondary else Dshm.colors.textTertiary,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Dshm.radius.pill))
                        .clickable(enabled = tabCount > 0, onClick = onTabs)
                        .padding(horizontal = Dshm.spacing.xs, vertical = Dshm.spacing.xxs),
                )
            }
            BarSlot(Modifier.weight(1f)) {
                BrowserIcon(
                    icon = R.drawable.ic_lucide_external_link,
                    description = stringResource(R.string.browser_open_external),
                    enabled = enabled,
                    onClick = onOpenExternal,
                )
            }
            BarSlot(Modifier.weight(1f)) {
                BrowserIcon(
                    icon = R.drawable.ic_lucide_trash_2,
                    description = stringResource(R.string.browser_close_tab),
                    enabled = enabled,
                    onClick = onCloseTab,
                )
            }
            BarSlot(Modifier.weight(1f)) {
                BrowserIcon(
                    icon = R.drawable.ic_lucide_plus,
                    description = stringResource(R.string.browser_new_tab),
                    onClick = onNewTab,
                )
            }
        }
    }
}

/** 底栏的一格：等宽，内容居中。 */
@Composable
private fun BarSlot(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun BrowserIcon(
    icon: Int,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            // 底栏要放下「后退/前进/刷新/全屏/标签数/外部打开/关闭/新建」，
            // 用 36dp 的键位才不会在窄屏上把最后一个挤出去。
            .size(Dshm.spacing.iconButton - Dshm.spacing.sm)
            .clip(RoundedCornerShape(Dshm.radius.sm))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = if (enabled) Dshm.colors.textSecondary else Dshm.colors.textTertiary.copy(alpha = 0.4f),
            modifier = Modifier.size(Dshm.spacing.topBarIcon),
        )
    }
}

@Composable
private fun BrowserHint(text: String) {
    Text(
        text = text,
        style = Dshm.typography.rowPreview,
        color = Dshm.colors.textTertiary,
        modifier = Modifier.padding(Dshm.spacing.lg),
    )
}

/**
 * 「Bot 正在操作浏览器」。
 *
 * 对齐 DeepSearch 的 `AgentBrowsingOverlay`：页面压一层薄雾，中间一颗呼吸的点 +
 * 谁在操作 + 「接管」。接管会打断当前的等待，工具立刻回执，用户拿回页面。
 */
@Composable
private fun AgentBrowsingOverlay(botName: String, onTakeover: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "agent-busy")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1200), RepeatMode.Reverse),
        label = "agent-busy-alpha",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Dshm.colors.scrim.copy(alpha = 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(Dshm.radius.pill))
                .background(Dshm.colors.surface)
                .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(Dshm.spacing.statusDot)
                    .clip(CircleShape)
                    .background(Dshm.colors.accent.copy(alpha = alpha)),
            )
            Spacer(Modifier.size(Dshm.spacing.sm))
            Text(
                text = stringResource(R.string.browser_agent_busy, botName),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textPrimary,
            )
            Spacer(Modifier.size(Dshm.spacing.md))
            Box(
                Modifier
                    .width(Dshm.spacing.hairline)
                    .height(Dshm.spacing.sm + Dshm.spacing.xs)
                    .background(Dshm.colors.divider),
            )
            Spacer(Modifier.size(Dshm.spacing.md))
            Text(
                text = stringResource(R.string.browser_takeover),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(Dshm.radius.sm))
                    .clickable(onClick = onTakeover)
                    .padding(horizontal = Dshm.spacing.xxs, vertical = Dshm.spacing.xxs),
            )
        }
    }
}

/**
 * 「轮到你操作了」：Bot 把页面交给人（登录 / 验证码 / 付款），点「完成」继续。
 */
@Composable
private fun HandOverBanner(reason: String, onDone: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dshm.spacing.sm, vertical = Dshm.spacing.xs)
            .clip(RoundedCornerShape(Dshm.radius.md))
            .background(Dshm.colors.accent.copy(alpha = 0.12f))
            .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.browser_hand_over_title),
                style = Dshm.typography.rowTitle,
                color = Dshm.colors.accent,
            )
            Text(
                text = reason,
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onDone) {
            Text(stringResource(R.string.browser_hand_over_done))
        }
    }
}

/** 下载进度：文件名 + 一条细进度条（总量未知时只显示在下载）。 */
@Composable
private fun DownloadBanner(entry: BotDownload) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dshm.spacing.sm, vertical = Dshm.spacing.xs)
            .clip(RoundedCornerShape(Dshm.radius.md))
            .background(Dshm.colors.surface)
            .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lucide_download),
            contentDescription = null,
            tint = if (entry.failed) Dshm.colors.danger else Dshm.colors.accent,
            modifier = Modifier.size(Dshm.spacing.settingsRowIcon),
        )
        Spacer(Modifier.size(Dshm.spacing.sm))
        Text(
            text = entry.name,
            style = Dshm.typography.rowPreview,
            color = Dshm.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(Dshm.spacing.sm))
        if (entry.done) {
            Text(
                text = stringResource(
                    if (entry.failed) R.string.browser_download_failed else R.string.browser_download_done,
                ),
                style = Dshm.typography.rowTime,
                color = if (entry.failed) Dshm.colors.danger else Dshm.colors.success,
            )
        } else {
            val progress = entry.progress.takeIf { it >= 0f }
            if (progress != null) {
                Box(
                    modifier = Modifier
                        .width(Dshm.spacing.xxxl * 2)
                        .height(Dshm.spacing.statusDot)
                        .clip(RoundedCornerShape(Dshm.radius.pill))
                        .background(Dshm.colors.surfaceMuted),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(Dshm.spacing.statusDot)
                            .clip(RoundedCornerShape(Dshm.radius.pill))
                            .background(Dshm.colors.accent),
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.browser_download_running),
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.textTertiary,
                )
            }
        }
    }
}

/**
 * 把 WebView 放进一个容器，切标签时换孩子而不是换 Composable 节点。
 *
 * 直接 `factory = { webView }` 的话，AndroidView 只在第一次建；切标签后画面还是旧 WebView
 * （地址栏却已经变了）—— 这是 DeepSearch 里踩过的同一个坑。
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun BrowserWebArea(webView: WebView, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context -> FrameLayout(context) },
        update = { container ->
            if (container.getChildAt(0) !== webView) {
                container.removeAllViews()
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN,
                        MotionEvent.ACTION_MOVE,
                        MotionEvent.ACTION_POINTER_DOWN,
                        -> view.parent?.requestDisallowInterceptTouchEvent(true)

                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL,
                        -> view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
                container.addView(
                    webView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
        onRelease = { container -> container.removeAllViews() },
    )
}

private fun hostOf(url: String): String? =
    runCatching { Uri.parse(url).host }.getOrNull()?.takeIf { !it.isNullOrEmpty() }
