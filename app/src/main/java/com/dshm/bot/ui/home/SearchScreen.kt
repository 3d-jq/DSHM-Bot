package com.dshm.bot.ui.home

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.ui.components.FloatingIconButton
import com.dshm.bot.ui.home.components.BotRow

/**
 * 独立搜索界面：顶部返回 + 搜索框（自动聚焦），下面是即时过滤的 Bot / 群聊结果。
 * 过滤在状态层完成（`HomeViewModel.setQuery`），这里只负责渲染。
 */
@Composable
fun SearchScreen(
    uiState: HomeUiState,
    onQueryChange: (String) -> Unit,
    onOpenBot: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Dshm.colors.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FloatingIconButton(
                icon = R.drawable.ic_lucide_arrow_left,
                contentDescription = stringResource(R.string.action_back),
                onClick = onBack,
            )
            Spacer(Modifier.width(Dshm.spacing.sm))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Dshm.radius.pill))
                    .background(Dshm.colors.surfaceMuted)
                    .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_search),
                    contentDescription = null,
                    tint = Dshm.colors.textSecondary,
                    modifier = Modifier.size(Dshm.spacing.topBarIcon),
                )
                Spacer(Modifier.width(Dshm.spacing.sm))
                Box(Modifier.weight(1f)) {
                    if (uiState.query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.home_search_placeholder),
                            style = Dshm.typography.rowPreview,
                            color = Dshm.colors.textTertiary,
                        )
                    }
                    BasicTextField(
                        value = uiState.query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = Dshm.typography.rowPreview.copy(color = Dshm.colors.textPrimary),
                        cursorBrush = SolidColor(Dshm.colors.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .fillMaxWidth(),
        ) {
            when {
                uiState.query.isBlank() -> SearchHint(stringResource(R.string.search_hint))
                uiState.rows.isEmpty() -> SearchHint(
                    stringResource(R.string.home_no_results, uiState.query.trim()),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = Dshm.spacing.lg,
                        vertical = Dshm.spacing.md,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xs),
                ) {
                    items(items = uiState.rows, key = { it.id }) { row ->
                        BotRow(row = row, onClick = { onOpenBot(row.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHint(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = Dshm.typography.emptyHint,
            color = Dshm.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Dshm.spacing.xxl),
        )
    }
}
