package com.dshm.bot.ui.home.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.ui.components.FloatingIconButton

/**
 * 顶栏：左侧个人资料，右侧搜索与新建。
 * 搜索进入独立搜索页；「＋」在按钮下方弹出新建菜单（带出现动画）。
 */
@Composable
fun HomeTopBar(
    onProfile: () -> Unit,
    onSearch: () -> Unit,
    onCreateBot: () -> Unit,
    onCreateGroup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

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
        Box {
            FloatingIconButton(
                icon = R.drawable.ic_lucide_plus,
                contentDescription = stringResource(R.string.home_new),
                onClick = { menuOpen = true },
            )
            CreateMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                onCreateBot = onCreateBot,
                onCreateGroup = onCreateGroup,
            )
        }
    }
}

/** 「＋」下方的下拉菜单：新建 Bot / 新建群聊。DropdownMenu 自带缩放+淡入的出现动画。 */
@Composable
private fun CreateMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCreateBot: () -> Unit,
    onCreateGroup: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(x = 0.dp, y = Dshm.spacing.xs),
        shape = RoundedCornerShape(Dshm.radius.md),
        containerColor = Dshm.colors.surface,
        tonalElevation = 0.dp,
        shadowElevation = Dshm.spacing.floatingElevation,
    ) {
        MenuRow(
            icon = R.drawable.ic_lucide_bot,
            label = stringResource(R.string.home_new_bot),
            onClick = {
                onDismiss()
                onCreateBot()
            },
        )
        MenuRow(
            icon = R.drawable.ic_lucide_users,
            label = stringResource(R.string.home_new_group),
            onClick = {
                onDismiss()
                onCreateGroup()
            },
        )
    }
}

@Composable
private fun MenuRow(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Dshm.colors.iconTint,
            modifier = Modifier.size(Dshm.spacing.topBarIcon),
        )
        Spacer(Modifier.width(Dshm.spacing.sm))
        Text(
            text = label,
            style = Dshm.typography.sheetAction,
            color = Dshm.colors.textPrimary,
        )
    }
}
