package com.dshm.bot.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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
    engineLabel: String? = null,
    /** 引擎正在启动（还没连上）：首屏先出骨架，别显示「还没有内容」。 */
    engineBooting: Boolean = false,
    onEngineClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Dshm.colors.pageBackground,
    ) { innerPadding ->
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
                engineBooting = engineBooting,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HomeContent(
    uiState: HomeUiState,
    onOpenBot: (String) -> Unit,
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
                    BotRow(row = row, onClick = { onOpenBot(row.id) })
                }
            }
        }
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

