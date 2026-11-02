package com.dshm.bot.ui.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.ui.components.FloatingIconButton

/**
 * 顶栏：左侧个人资料，右侧搜索与新建。
 * 三个入口都是「底色 + 四周阴影」的悬浮圆钮：填充和页面底色一致，靠阴影浮起来。
 */
@Composable
fun HomeTopBar(
    onProfile: () -> Unit,
    onSearch: () -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dshm.spacing.topBarHeight)
            .padding(horizontal = Dshm.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FloatingIconButton(
            icon = R.drawable.ic_lucide_user,
            contentDescription = stringResource(R.string.home_profile),
            onClick = onProfile,
        )
        Spacer(Modifier.weight(1f))
        FloatingIconButton(
            icon = R.drawable.ic_lucide_search,
            contentDescription = stringResource(R.string.home_search),
            onClick = onSearch,
        )
        Spacer(Modifier.width(Dshm.spacing.sm))
        FloatingIconButton(
            icon = R.drawable.ic_lucide_plus,
            contentDescription = stringResource(R.string.home_new),
            onClick = onNew,
        )
    }
}

/** 搜索态顶栏：圆角输入框 + 取消。进入时自动聚焦。 */
@Composable
fun HomeSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dshm.spacing.topBarHeight)
            .padding(horizontal = Dshm.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = Dshm.typography.rowPreview.copy(color = Dshm.colors.textPrimary),
            cursorBrush = SolidColor(Dshm.colors.accent),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .padding(vertical = Dshm.spacing.md),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(
                            elevation = Dshm.spacing.floatingElevation,
                            shape = RoundedCornerShape(Dshm.radius.pill),
                            ambientColor = Dshm.colors.shadow,
                            spotColor = Dshm.colors.shadow,
                        )
                        .clip(RoundedCornerShape(Dshm.radius.pill))
                        .background(Dshm.colors.background)
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
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.home_search_placeholder),
                                style = Dshm.typography.rowPreview,
                                color = Dshm.colors.textTertiary,
                            )
                        }
                        innerTextField()
                    }
                }
            },
        )
        Spacer(Modifier.width(Dshm.spacing.sm))
        TextButton(onClick = onCancel) {
            Text(
                text = stringResource(R.string.home_search_cancel),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.accent,
            )
        }
    }
}
