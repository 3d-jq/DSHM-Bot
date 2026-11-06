package com.dshm.bot.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.runtime.RuntimeEndpoint
import com.dshm.bot.domain.model.VoiceMode
import com.dshm.bot.ui.components.BotAvatar
import com.dshm.bot.ui.components.FloatingIconButton
import com.dshm.bot.ui.home.ChatRowUi
import com.dshm.bot.ui.settings.SettingsRow
import com.dshm.bot.ui.settings.SettingsRowDivider
import com.dshm.bot.ui.settings.SettingsSection
import kotlinx.coroutines.launch

/**
 * Bot 对话页：原生 Compose 界面，数据来自本机 DSH 的 DS Bot 插件。
 * 顶栏：左「返回」、右「详情」两个悬浮圆钮；中间「头像 + 名字胶囊」作为详情入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotChatScreen(
    bot: ChatRowUi,
    endpoint: RuntimeEndpoint?,
    onBack: () -> Unit,
    onDetails: () -> Unit = {},
    onOpenBrowser: () -> Unit = {},
    fontScale: Float = 1f,
    voiceMode: VoiceMode = VoiceMode.AUTO,
    modifier: Modifier = Modifier,
    viewModel: BotChatViewModel = viewModel(
        key = "bot-chat:${bot.id}:${endpoint?.baseUrl.orEmpty()}:${endpoint?.token.orEmpty()}",
        factory = BotChatViewModel.factory(bot.id, bot.name, bot.isGroup, endpoint),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var draft by rememberSaveable(bot.id) { mutableStateOf("") }
    var quickSettings by remember { mutableStateOf(false) }
    val quickSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val working = state.sending || state.waitingForReply || state.streaming.isNotEmpty()
    val workingTransition = rememberInfiniteTransition(label = "bot-working")
    val workingPulse by workingTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(650), repeatMode = RepeatMode.Reverse),
        label = "bot-working-pulse",
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Dshm.colors.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Dshm.colors.background,
                            0.58f to Dshm.colors.background.copy(alpha = 0.92f),
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
                                notice = state.memoryNotice,
                                onClick = { quickSettings = true },
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
                                .zIndex(1f)
                                .graphicsLayer {
                                    val scale = if (working) workingPulse else 1f
                                    scaleX = scale
                                    scaleY = scale
                                },
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
                        bottomPadding = 0.dp,
                        working = working,
                        hasEarlier = state.hasEarlier,
                        loadingEarlier = state.loadingEarlier,
                        autoLoadEnabled = state.error == null,
                        onLoadEarlier = viewModel::loadEarlier,
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
                voiceMode = voiceMode,
                speech = viewModel.speechRepository,
                sendEnabled = ComposerRules.canSend(draft) && !state.sending,
                modifier = Modifier
                    .padding(horizontal = Dshm.spacing.lg)
                    .padding(
                        bottom = innerPadding.calculateBottomPadding() + Dshm.spacing.composerBottomInset,
                    ),
            )
        }
    }
    if (quickSettings) {
        BotSettingsSheet(
            bot = bot,
            endpoint = endpoint,
            onDismiss = { quickSettings = false },
            onDetails = {
                quickSettings = false
                onDetails()
            },
            onBrowser = {
                quickSettings = false
                onOpenBrowser()
            },
        )
    }
}

/**
 * 距顶多少像素内就自动往前翻页。
 *
 * 照 dsh-Go（`bot_chat_kit.dart` 的 `above < 400`）：不是要滑到最顶，
 * 而是接近顶部就开始加载，用户不用刻意拉到头。
 */
private const val EARLIER_TRIGGER_PX = 400

/** 滑到第几项之内就算「接近顶部」。 */
private const val EARLIER_TRIGGER_INDEX = 2

/**
 * 往前翻页时的顶部转圈。
 *
 * 尺寸照 dsh-Go（`bot_chat_kit.dart`）：18dp、strokeWidth 2、上下 16dp 留白。
 */
@Composable
private fun LoadingEarlierRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = Dshm.colors.accent,
            strokeWidth = 2.dp,
            modifier = Modifier.size(18.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatMessages(
    entries: List<ChatEntry>,
    streaming: String,
    bot: ChatRowUi,
    fontScale: Float,
    topPadding: Dp,
    bottomPadding: Dp,
    working: Boolean,
    hasEarlier: Boolean = false,
    loadingEarlier: Boolean = false,
    /** 上次加载出错时不再自动触发（否则会一直重试）。 */
    autoLoadEnabled: Boolean = true,
    onLoadEarlier: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val typing = working && streaming.isEmpty()
    val count = entries.size + (if (streaming.isNotEmpty()) 1 else 0) + (if (typing) 1 else 0)

    // 用户自己往上滑看别的消息时，别再自动抢着滚到底。
    var userAway by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }
            .collect { away -> userAway = away }
    }

    // 滚到底的时机：第一次有内容（进对话要看最新消息）、来了新消息、
    // 模型正在输出（流式气泡在长）。往前翻历史时最后一条没变 —— 这时不能滚。
    var lastTailId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(count, streaming.length) {
        val tailId = entries.lastOrNull()?.id
        val isNewTail = tailId != null && tailId != lastTailId
        val firstLoad = lastTailId == null && tailId != null
        lastTailId = tailId
        if (count > 0 && (isNewTail || firstLoad || streaming.isNotEmpty()) && !userAway) {
            // 最后一条可能比屏幕高：先定位到它，再滚到列表末尾，保证看到最新内容的底部。
            listState.scrollToItem(count - 1)
            listState.scrollBy(100_000f)
        }
    }

    val scope = rememberCoroutineScope()
    // 滑到接近顶部就自动往前翻一页。
    //
    // dsh-Go 用的是倒序列表，判断「离再往上的端点还有多远」（above < 400）；
    // 我们是正序列表，对应的量是「当前第一条消息离列表开头有多远」——
    // 用滚动偏移量算：越接近 0 越靠近顶部。
    LaunchedEffect(hasEarlier, loadingEarlier, entries.size, autoLoadEnabled) {
        if (!hasEarlier || loadingEarlier || !autoLoadEnabled) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                // 第一条可见消息就是列表第一项（index 0）时，说明已经到顶附近。
                val nearTop = index <= EARLIER_TRIGGER_INDEX && offset <= EARLIER_TRIGGER_PX
                if (nearTop) onLoadEarlier()
            }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Dshm.spacing.lg,
                end = Dshm.spacing.lg,
                top = topPadding + Dshm.spacing.md,
                bottom = bottomPadding + Dshm.spacing.md,
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
                        text = streaming,
                        author = null,
                        showAuthor = false,
                        fontScale = fontScale,
                    )
                }
            }
            if (typing) {
                item(key = "typing") { ThinkingRow(bot) }
            }
            // 更早的历史：加载圈挂在列表顶部。
            if (loadingEarlier) {
                item(key = "load-earlier") {
                    LoadingEarlierRow()
                }
            }
        }
        if (listState.canScrollForward) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // 紧贴输入框上沿。
                    .padding(bottom = bottomPadding + Dshm.spacing.xs)
                    .size(Dshm.spacing.iconButton)
                    .shadow(
                        elevation = Dshm.spacing.floatingElevation,
                        shape = CircleShape,
                        ambientColor = Dshm.colors.shadowSoft,
                        spotColor = Dshm.colors.shadowSoft,
                    )
                    .clip(CircleShape)
                    .background(Dshm.colors.surface)
                    .clickable {
                        scope.launch {
                            listState.animateScrollToItem((count - 1).coerceAtLeast(0))
                            listState.animateScrollBy(100_000f)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_arrow_up),
                    contentDescription = stringResource(R.string.chat_jump_bottom),
                    tint = Dshm.colors.iconTint,
                    modifier = Modifier
                        .size(Dshm.spacing.topBarIcon)
                        .rotate(180f),
                )
            }
        }
    }
}

/** 模型思考/排队：一个会呼吸的小头像 + 「理一下思路…」+ 已等待秒数（对齐 DS Bot）。 */
@Composable
private fun ThinkingRow(bot: ChatRowUi) {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            seconds += 1
        }
    }
    val transition = rememberInfiniteTransition(label = "thinking")
    val scale by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(620), repeatMode = RepeatMode.Reverse),
        label = "thinking-scale",
    )
    Row(
        modifier = Modifier.padding(horizontal = Dshm.spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotAvatar(
            inkId = bot.inkId,
            glyph = bot.glyph,
            avatarImage = bot.avatarImage,
            avatarShape = bot.avatarShape,
            size = Dshm.spacing.lg + Dshm.spacing.sm,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        )
        Text(
            text = stringResource(R.string.chat_thinking),
            style = Dshm.typography.rowPreview,
            color = Dshm.colors.textSecondary,
        )
        Text(
            text = "· ${seconds}s",
            style = Dshm.typography.rowTime,
            color = Dshm.colors.textTertiary,
        )
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
                .background(
                    Brush.linearGradient(
                        listOf(Dshm.colors.bubbleUserStart, Dshm.colors.bubbleUserEnd),
                    ),
                    RoundedCornerShape(Dshm.radius.lg),
                )
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
                .clip(RoundedCornerShape(Dshm.radius.lg))
                .background(Dshm.colors.bubbleAssistant)
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
    notice: Boolean = false,
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
        if (notice) {
            Spacer(Modifier.width(Dshm.spacing.xs))
            Text(
                text = stringResource(R.string.chat_memory_updated),
                style = Dshm.typography.rowTime.copy(fontSize = 11.sp),
                color = Dshm.colors.accent,
                maxLines = 1,
            )
        }
    }
}
