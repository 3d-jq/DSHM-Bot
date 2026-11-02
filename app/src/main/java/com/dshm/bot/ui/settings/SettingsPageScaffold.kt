package com.dshm.bot.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.ui.components.FloatingIconButton

/**
 * 设置子页统一骨架（防止重复踩坑）：
 * - header 固定在 `Scaffold(topBar)`，只在这里加一次 `statusBarsPadding()`；
 * - 内容区只消费 `innerPadding`，不允许再加状态栏内边距；
 * - header 不随内容滚动。
 *
 * 以后所有设置子页都用它，不要再手写 Scaffold + 顶栏。
 */
@Composable
fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Dshm.colors.background,
        topBar = {
            Row(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatingIconButton(
                    icon = R.drawable.ic_lucide_arrow_left,
                    contentDescription = stringResource(R.string.action_back),
                    onClick = onBack,
                )
                Spacer(Modifier.width(Dshm.spacing.sm))
                Text(text = title, style = Dshm.typography.pageTitle, color = Dshm.colors.textPrimary)
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = Dshm.spacing.lg)
                    .padding(top = Dshm.spacing.sm, bottom = Dshm.spacing.xxl),
                content = content,
            )
        }
    }
}
