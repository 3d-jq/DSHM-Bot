package com.dshm.bot.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.runtime.RuntimeEndpoint
import com.dshm.bot.ui.components.BotAvatar
import com.dshm.bot.ui.components.FloatingIconButton
import com.dshm.bot.ui.home.ChatRowUi

/**
 * Bot 对话页：原生 Compose 界面，数据来自本机 DSH 的 DS Bot 插件。
 * 顶栏：左「返回」、右「详情」两个悬浮圆钮；中间「头像 + 名字胶囊」作为详情入口。
 */
@Composable
fun BotChatScreen(
    bot: ChatRowUi,
    endpoint: RuntimeEndpoint?,
    onBack: () -> Unit,
    onDetails: () -> Unit = {},
    fontScale: Float = 1f,
    modifier: Modifier = Modifier,
    viewModel: BotChatViewModel = viewModel(
        key = "bot-chat:${bot.id}:${endpoint?.baseUrl.orEmpty()}:${endpoint?.token.orEmpty()}",
        factory = BotChatViewModel.factory(bot.id, bot.name, bot.isGroup, endpoint),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var draft by rememberSaveable(bot.id) { mutableStateOf("") }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Dshm.colors.pageBackground,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Dshm.colors.pageBackground,
                            0.58f to Dshm.colors.pageBackground.copy(alpha = 0.92f),
                            1f to Color.Transparent,
                        ),
                    )
                    .statusBarsPadding()
                    .padding(top = Dshm.spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
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
                            )
                        }
                        BotAvatar(
                            inkId = bot.inkId,
                            glyph = bot.glyph,
                            avatarImage = bot.avatarImage,
                            avatarShape = bot.avatarShape,
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
                .imePadding(),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.engineOff -> CenteredHint(
                        title = stringResource(R.string.chat_engine_off_title),
                        hint = stringResource(R.string.chat_engine_off_hint),
                    )

                    state.loading && state.entries.isEmpty() -> CircularProgressIndicator(
                        color = Dshm.colors.accent,
                        modifier = Modifier.size(Dshm.spacing.xxl),
                    )

                    state.error != null && state.entries.isEmpty() -> ChatError(
                        message = state.error.orEmpty(),
                        onRetry = viewModel::retry,
                    )

                    state.entries.isEmpty() && state.streaming.isEmpty() -> CenteredHint(
                        title = stringResource(R.string.chat_placeholder_title, bot.name),
                        hint = stringResource(R.string.chat_placeholder_hint),
                    )

                    else -> ChatMessages(
                        entries = state.entries,
                        streaming = state.streaming,
                        bot = bot,
                        fontScale = fontScale,
                        topPadding = innerPadding.calculateTopPadding(),
                    )
                }
            }
            if (state.error != null && state.entries.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.chat_send_failed, state.error.orEmpty()),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.danger,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.xs),
                )
            }
            ChatComposer(
                value = draft,
                onValueChange = { draft = it },
                onSend = {
                    if (ComposerRules.canSend(draft) && !state.sending) {
                        viewModel.send(draft)
                        draft = ""
                    }
                },
                placeholder = stringResource(R.string.composer_placeholder, bot.name),
                textScale = fontScale,
                sendEnabled = ComposerRules.canSend(draft) && !state.sending,
                modifier = Modifier
                    .padding(horizontal = Dshm.spacing.lg)
                    .padding(
                        bottom = innerPadding.calculateBottomPadding() + Dshm.spacing.composerBottomInset,
                    ),
            )
        }
    }
}

@Composable
private fun ChatMessages(
    entries: List<ChatEntry>,
    streaming: String,
    bot: ChatRowUi,
    fontScale: Float,
    topPadding: Dp,
) {
    val listState = rememberLazyListState()
    val count = entries.size + if (streaming.isNotEmpty()) 1 else 0
    LaunchedEffect(entries.size, streaming.length) {
        if (count > 0) {
            // 最后一条可能比屏幕高：先定位到它，再滚到列表末尾，保证看到最新内容的底部。
            listState.scrollToItem(count - 1)
            listState.scrollBy(100_000f)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Dshm.spacing.lg,
            end = Dshm.spacing.lg,
            top = topPadding + Dshm.spacing.md,
            bottom = Dshm.spacing.md,
        ),
        verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
    ) {
        items(entries, key = { it.id }) { entry ->
            when (entry.kind) {
                ChatEntryKind.USER -> UserBubble(entry.text, fontScale)
                ChatEntryKind.BOT -> BotBubble(
                    text = entry.text,
                    author = entry.author,
                    showAuthor = bot.isGroup,
                    fontScale = fontScale,
                )
                ChatEntryKind.EVENT -> EventLine(entry.text)
            }
        }
        if (streaming.isNotEmpty()) {
            item(key = "streaming") {
                BotBubble(
                    text = "$streaming▍",
                    author = null,
                    showAuthor = false,
                    fontScale = fontScale,
                )
            }
        }
    }
}

@Composable
private fun CenteredHint(title: String, hint: String) {
    Column(
        modifier = Modifier.padding(horizontal = Dshm.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = Dshm.typography.emptyTitle,
            color = Dshm.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Dshm.spacing.sm))
        Text(
            text = hint,
            style = Dshm.typography.emptyHint,
            color = Dshm.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ChatError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = Dshm.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.chat_load_failed),
            style = Dshm.typography.emptyTitle,
            color = Dshm.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Dshm.spacing.xs))
        Text(
            text = message,
            style = Dshm.typography.rowPreview,
            color = Dshm.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Dshm.spacing.md))
        Text(
            text = stringResource(R.string.chat_retry),
            style = Dshm.typography.rowTitle,
            color = Dshm.colors.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(Dshm.radius.pill))
                .clickable(onClick = onRetry)
                .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
        )
    }
}

/** 用户消息气泡，右对齐。 */
@Composable
private fun UserBubble(text: String, fontScale: Float) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            text = text,
            style = Dshm.typography.composerText.copy(
                fontSize = Dshm.typography.composerText.fontSize * fontScale,
                lineHeight = Dshm.typography.composerText.lineHeight * fontScale,
            ),
            color = Dshm.colors.onAccent,
            modifier = Modifier
                .background(Dshm.colors.accent, RoundedCornerShape(Dshm.radius.lg))
                .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
        )
    }
}

/** Bot 消息气泡，左对齐；群聊里显示发言 Bot 的名字。 */
@Composable
private fun BotBubble(
    text: String,
    author: String?,
    showAuthor: Boolean,
    fontScale: Float,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        if (showAuthor && !author.isNullOrBlank()) {
            Text(
                text = author,
                style = Dshm.typography.rowTime.copy(fontSize = 12.sp),
                color = Dshm.colors.textTertiary,
                modifier = Modifier.padding(start = Dshm.spacing.xs, bottom = Dshm.spacing.xxs),
            )
        }
        Column(
            modifier = Modifier
                .shadow(
                    elevation = Dshm.spacing.cardElevation,
                    shape = RoundedCornerShape(Dshm.radius.lg),
                    ambientColor = Dshm.colors.shadowCard,
                    spotColor = Dshm.colors.shadowCard,
                )
                .clip(RoundedCornerShape(Dshm.radius.lg))
                .background(Dshm.colors.surface)
                .border(
                    Dshm.spacing.hairline,
                    Dshm.colors.divider,
                    RoundedCornerShape(Dshm.radius.lg),
                )
                .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
        ) {
            MarkdownText(markdown = text, fontScale = fontScale)
        }
    }
}

/** 系统事件（完成、停止等）显示为一行居中小字。 */
@Composable
private fun EventLine(text: String) {
    Text(
        text = text,
        style = Dshm.typography.rowTime,
        color = Dshm.colors.textTertiary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dshm.spacing.xs),
    )
}

/** 头像下方的名字胶囊：白底圆角，悬浮效果与主界面按钮一致。 */
@Composable
private fun BotTitleCapsule(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Dshm.radius.pill)
    Row(
        modifier = modifier
            .width(IntrinsicSize.Max)
            .shadow(
                elevation = Dshm.spacing.floatingElevation,
                shape = shape,
                ambientColor = Dshm.colors.shadow,
                spotColor = Dshm.colors.shadow,
            )
            .clip(shape)
            .background(Dshm.colors.surface)
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
