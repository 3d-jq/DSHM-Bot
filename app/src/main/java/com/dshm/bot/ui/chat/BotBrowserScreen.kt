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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm

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
        )
        BotBrowserPanel(session = session, modifier = Modifier.weight(1f))
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
        }
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dshm.spacing.topBarHeight)
            .padding(horizontal = Dshm.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrowserIcon(
            icon = R.drawable.ic_lucide_arrow_left,
            description = stringResource(R.string.action_back),
            onClick = onBack,
        )
        Text(
            text = title,
            style = Dshm.typography.screenTitle,
            color = Dshm.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Dshm.spacing.sm),
        )
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
