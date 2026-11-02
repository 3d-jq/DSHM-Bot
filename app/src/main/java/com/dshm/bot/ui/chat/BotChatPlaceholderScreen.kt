package com.dshm.bot.ui.chat

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.ui.components.BotAvatar
import com.dshm.bot.ui.components.FloatingIconButton
import com.dshm.bot.ui.home.ChatRowUi

/**
 * Bot 对话页（占位）。
 * 顶栏：左「返回」、右「详情」两个悬浮圆钮；中间垂直排列「头像在上、名字胶囊在下」，
 * 整体水平居中，作为进入详情的入口。
 */
@Composable
fun BotChatPlaceholderScreen(
    bot: ChatRowUi,
    onBack: () -> Unit,
    onDetails: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Dshm.colors.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = Dshm.spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 返回 / 详情与中间「头像+胶囊」同排；中间块用 Box 让胶囊顶部嵌入头像下缘。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dshm.spacing.lg),
                    verticalAlignment = Alignment.Top,
                ) {
                    FloatingIconButton(
                        icon = R.drawable.ic_lucide_arrow_left,
                        contentDescription = stringResource(R.string.action_back),
                        onClick = onBack,
                    )
                    // 中间：头像上层、名字胶囊下层，胶囊按内容宽度居中，头像下缘含住胶囊顶部。
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        BotTitleCapsule(
                            name = bot.name,
                            onClick = onDetails,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .wrapContentWidth(Alignment.CenterHorizontally)
                                .offset(y = Dshm.spacing.avatar - Dshm.spacing.capsuleOverlap),
                        )
                        BotAvatar(
                            inkId = bot.inkId,
                            glyph = bot.glyph,
                            size = Dshm.spacing.avatar,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .zIndex(1f),
                        )
                    }
                    FloatingIconButton(
                        icon = R.drawable.ic_lucide_square_user,
                        contentDescription = stringResource(R.string.chat_details),
                        onClick = onDetails,
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = Dshm.spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.chat_placeholder_title, bot.name),
                style = Dshm.typography.emptyTitle,
                color = Dshm.colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Dshm.spacing.sm))
            Text(
                text = stringResource(R.string.chat_placeholder_hint),
                style = Dshm.typography.emptyHint,
                color = Dshm.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 头像下方的名字胶囊：白底圆角 + 名字 + ›，悬浮效果与主界面按钮一致。 */
@Composable
private fun BotTitleCapsule(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .shadow(
                elevation = Dshm.spacing.floatingElevation,
                shape = RoundedCornerShape(Dshm.radius.pill),
                ambientColor = Dshm.colors.shadow,
                spotColor = Dshm.colors.shadow,
            )
            .clip(RoundedCornerShape(Dshm.radius.pill))
            .background(Dshm.colors.background)
            .clickable(onClick = onClick)
            .padding(horizontal = Dshm.spacing.xl, vertical = Dshm.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = name,
            style = Dshm.typography.rowTitle,
            color = Dshm.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(Dshm.spacing.xs))
        Icon(
            painter = painterResource(R.drawable.ic_lucide_chevron_right),
            contentDescription = null,
            tint = Dshm.colors.textTertiary,
            modifier = Modifier.size(Dshm.spacing.topBarIcon),
        )
    }
}