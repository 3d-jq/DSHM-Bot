package com.dshm.bot.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
                    // 中间：先用固定空白占住头像高度，再放胶囊，让这一列撑出「头像+胶囊」的真实高度，
                    // 顶栏因此会给胶囊留足位置，下面的消息不会压到胶囊上。
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Column(
                            modifier = Modifier.align(Alignment.TopCenter),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Spacer(Modifier.height(Dshm.spacing.avatar - Dshm.spacing.capsuleOverlap))
                            BotTitleCapsule(
                                name = bot.name,
                                onClick = onDetails,
                                modifier = Modifier.wrapContentWidth(Alignment.CenterHorizontally),
                            )
                        }
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
        var draft by rememberSaveable { mutableStateOf("") }
        val sent = remember { mutableStateListOf<String>() }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (sent.isEmpty()) {
                    Column(
                        modifier = Modifier.padding(horizontal = Dshm.spacing.xxl),
                        horizontalAlignment = Alignment.CenterHorizontally,
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
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = Dshm.spacing.lg,
                            vertical = Dshm.spacing.md,
                        ),
                        verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                    ) {
                        items(sent) { message -> UserBubble(message) }
                    }
                }
            }
            ChatComposer(
                value = draft,
                onValueChange = { draft = it },
                onSend = {
                    if (ComposerRules.canSend(draft)) {
                        sent.add(draft.trim())
                        draft = ""
                    }
                },
                placeholder = stringResource(R.string.composer_placeholder, bot.name),
                modifier = Modifier
                    .padding(horizontal = Dshm.spacing.lg)
                    .padding(bottom = Dshm.spacing.composerBottomInset),
            )
        }
    }
}

/** 用户消息气泡（本地占位，接入 DSH 后由真实消息替换）。 */
@Composable
private fun UserBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            text = text,
            style = Dshm.typography.composerText,
            color = Dshm.colors.onAccent,
            modifier = Modifier
                .background(Dshm.colors.accent, RoundedCornerShape(Dshm.radius.lg))
                .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
        )
    }
}

/** 头像下方的名字胶囊：白底圆角，悬浮效果与主界面按钮一致。 */
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
    }
}