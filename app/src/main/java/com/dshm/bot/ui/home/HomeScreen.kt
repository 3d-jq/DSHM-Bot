package com.dshm.bot.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.ui.home.components.BotRow
import com.dshm.bot.ui.home.components.HomeTopBar

/**
 * 主界面：Bot 与群聊列表。
 * 「搜索」由上层导航到独立搜索页；「＋」在按钮下方弹出新建菜单。
 */
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onQueryChange: (String) -> Unit,
    onOpenBot: (String) -> Unit,
    onCreateBot: () -> Unit,
    onCreateGroup: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    /** 长按面板：置顶 / 取消置顶。 */
    onTogglePin: (ChatRowUi) -> Unit = {},
    /** 长按面板：删除（上层再调 `delete-bot` / `delete-room`）。 */
    onDelete: (ChatRowUi) -> Unit = {},
    /** 长按面板：设为主 Bot / 取消主 Bot（`set-main`）。 */
    onSetMain: (ChatRowUi, Boolean) -> Unit = { _, _ -> },
    engineLabel: String? = null,
    /** 引擎正在启动（还没连上）：首屏先出骨架，别显示「还没有内容」。 */
    engineBooting: Boolean = false,
    onEngineClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<ChatRowUi?>(null) }
    var rowMenu by remember { mutableStateOf<Pair<ChatRowUi, androidx.compose.ui.geometry.Rect>?>(null) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Dshm.colors.pageBackground,
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            HomeTopBar(
                onProfile = onOpenSettings,
                onSearch = onOpenSearch,
                onCreateBot = {
                    onCreateBot()
                },
                engineLabel = engineLabel,
                onEngineClick = onEngineClick,
                onCreateGroup = {
                    onCreateGroup()
                },
            )
            HomeContent(
                uiState = uiState,
                onOpenBot = onOpenBot,
                onTogglePin = onTogglePin,
                onDelete = { row -> pendingDelete = row },
                onLongPress = { row, anchor -> rowMenu = row to anchor },
                engineBooting = engineBooting,
                modifier = Modifier.weight(1f),
            )
        }
        rowMenu?.let { (row, _) ->
            RowActionSheet(
                row = row,
                onDismiss = { rowMenu = null },
                onTogglePin = {
                    rowMenu = null
                    onTogglePin(row)
                },
                onDelete = {
                    rowMenu = null
                    pendingDelete = row
                },
                canRemoveMain = uiState.rows.count { it.isMain } > 1,
                onSetMain = { main ->
                    rowMenu = null
                    onSetMain(row, main)
                },
            )
        }
        pendingDelete?.let { row ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text(row.name) },
                text = { Text(stringResource(R.string.bot_settings_delete_confirm, row.name)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingDelete = null
                            onDelete(row)
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.home_delete),
                            color = Dshm.colors.danger,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) {
                        Text(stringResource(R.string.home_search_cancel))
                    }
                },
                containerColor = Dshm.colors.surface,
            )
        }
        }
    }
}

@Composable
private fun HomeContent(
    uiState: HomeUiState,
    onOpenBot: (String) -> Unit,
    onTogglePin: (ChatRowUi) -> Unit = {},
    onDelete: (ChatRowUi) -> Unit = {},
    onLongPress: (ChatRowUi, androidx.compose.ui.geometry.Rect) -> Unit = { _, _ -> },
    engineBooting: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        when {
            // 首次进入还没有缓存时，先铺一屏骨架：比一个转圈更像「马上就有内容」。
            uiState.isLoading || (engineBooting && uiState.rows.isEmpty()) -> HomeSkeleton()

            uiState.hasError -> StateMessage(
                title = stringResource(R.string.home_error_title),
                hint = stringResource(R.string.home_error_hint),
            )

            uiState.showEmptyState -> StateMessage(
                title = if (uiState.showNoResults) {
                    stringResource(R.string.home_no_results, uiState.query.trim())
                } else {
                    stringResource(R.string.home_empty_title)
                },
                hint = if (uiState.showNoResults) "" else stringResource(R.string.home_empty_hint),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Dshm.spacing.listBottomPadding),
            ) {
                items(items = uiState.rows, key = { it.id }) { row ->
                    BotRow(
                        row = row,
                        onClick = { onOpenBot(row.id) },
                        onLongPress = { anchor -> onLongPress(row, anchor) },
                    )
                }
            }
        }
    }
}

/**
 * 长按行的操作面板（置顶 / 删除）。
 *
 * 用底部面板而不是浮层菜单：和 App 里其它面板同一套，点击一定落在按钮上
 * （之前用 `DropdownMenu` 的独立窗口，点「置顶」只会关菜单）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RowActionSheet(
    row: ChatRowUi,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    /** 团队里不止一个主 Bot 时才能取消。 */
    canRemoveMain: Boolean = false,
    onSetMain: (Boolean) -> Unit = {},
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(),
        containerColor = Dshm.colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Dshm.spacing.xl),
        ) {
            Text(
                text = row.name,
                style = Dshm.typography.emptyTitle,
                color = Dshm.colors.textPrimary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    horizontal = Dshm.spacing.lg,
                    vertical = Dshm.spacing.md,
                ),
            )
            MenuAction(
                icon = R.drawable.ic_lucide_pin,
                label = stringResource(if (row.pinned) R.string.home_unpin else R.string.home_pin),
                onClick = onTogglePin,
            )
            // 主 Bot 的「设为主 / 取消主」：团队至少要留一个主 Bot（对齐 DS Bot 的行菜单）。
            if (!row.isGroup) {
                when {
                    !row.isMain -> MenuAction(
                        icon = R.drawable.ic_lucide_star,
                        label = stringResource(R.string.bot_settings_make_main),
                        onClick = { onSetMain(true) },
                    )
                    canRemoveMain -> MenuAction(
                        icon = R.drawable.ic_lucide_star,
                        label = stringResource(R.string.bot_settings_remove_main),
                        onClick = { onSetMain(false) },
                    )
                }
            }
            // 主 Bot 不能直接删（服务端会拒绝），先取消主 Bot 角色。
            if (row.isGroup || !row.isMain) {
                MenuAction(
                    icon = R.drawable.ic_lucide_trash_2,
                    label = stringResource(R.string.home_delete),
                    danger = true,
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun MenuAction(
    icon: Int,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (danger) Dshm.colors.danger else Dshm.colors.textPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.md),
    ) {
        androidx.compose.material3.Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = tint,
        )
        Text(text = label, style = Dshm.typography.rowTitle, color = tint)
    }
}

@Composable
private fun StateMessage(title: String, hint: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Dshm.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = Dshm.typography.emptyTitle,
            color = Dshm.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (hint.isNotEmpty()) {
            Spacer(Modifier.height(Dshm.spacing.sm))
            Text(
                text = hint,
                style = Dshm.typography.emptyHint,
                color = Dshm.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 首屏骨架：几条和 [BotRow] 同尺寸的占位，带缓慢呼吸的透明度。
 *
 * 引擎冷启动要几秒，白屏 / 单颗转圈都显得「卡住了」；骨架先给出列表的形状。
 */
@Composable
private fun HomeSkeleton() {
    val transition = rememberInfiniteTransition(label = "home-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.32f,
        targetValue = 0.62f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "home-skeleton-alpha",
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = Dshm.spacing.sm),
    ) {
        repeat(6) { index ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = Dshm.spacing.lg,
                        vertical = Dshm.spacing.sm,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(Dshm.spacing.avatar)
                        .clip(CircleShape)
                        .background(Dshm.colors.textPrimary.copy(alpha = alpha * 0.20f)),
                )
                Spacer(Modifier.width(Dshm.spacing.md))
                Column(Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(if (index % 2 == 0) 0.36f else 0.28f)
                            .height(Dshm.spacing.sm)
                            .clip(RoundedCornerShape(Dshm.radius.xs))
                            .background(Dshm.colors.textPrimary.copy(alpha = alpha * 0.20f)),
                    )
                    Spacer(Modifier.height(Dshm.spacing.xs))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(if (index % 2 == 0) 0.62f else 0.5f)
                            .height(Dshm.spacing.xs)
                            .clip(RoundedCornerShape(Dshm.radius.xs))
                            .background(Dshm.colors.textPrimary.copy(alpha = alpha * 0.16f)),
                    )
                }
                Spacer(Modifier.width(Dshm.spacing.md))
                Box(
                    modifier = Modifier
                        .width(Dshm.spacing.xl)
                        .height(Dshm.spacing.xs)
                        .clip(RoundedCornerShape(Dshm.radius.xs))
                        .background(Dshm.colors.textPrimary.copy(alpha = alpha * 0.16f)),
                )
            }
        }
    }
}

