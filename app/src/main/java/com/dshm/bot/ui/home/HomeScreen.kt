package com.dshm.bot.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.ui.home.components.BotRow
import com.dshm.bot.ui.home.components.HomeTopBar
import kotlinx.coroutines.launch

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
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val comingSoon = stringResource(R.string.home_coming_soon)

    fun showComingSoon() {
        scope.launch { snackbarHostState.showSnackbar(comingSoon) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Dshm.colors.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    showComingSoon()
                },
                onCreateGroup = {
                    onCreateGroup()
                    showComingSoon()
                },
            )
            HomeContent(
                uiState = uiState,
                onOpenBot = onOpenBot,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HomeContent(
    uiState: HomeUiState,
    onOpenBot: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        when {
            uiState.isLoading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Dshm.colors.accent,
            )

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
