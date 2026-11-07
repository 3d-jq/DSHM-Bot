package com.dshm.bot.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.conflate
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.runtime.RuntimeEndpoint
import com.dshm.bot.domain.model.AvatarGlyph
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BotChatScreen(
    bot: ChatRowUi,
    endpoint: RuntimeEndpoint?,
    onBack: () -> Unit,
    onDetails: () -> Unit = {},
    onOpenBrowser: () -> Unit = {},
    onOpenMember: (String) -> Unit = {},
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
    // 审批应答中（按钮禁用，避免重复点）。
    var answeringApproval by remember { mutableStateOf(false) }
    // 发送了几次 —— 每发一次就重新钉到底（照 DeepSearch 的 SEND-PATH）。
    var sendCount by remember { mutableIntStateOf(0) }
    var lastSendAt by remember { mutableStateOf(0L) }
    // 输入框的实际高度 —— 列表要留出这么多，消息才不会被输入框压住
    // （照 DeepSearch 的 bottomChromeHeight + bottomReserve）。
    var composerHeight by remember { mutableStateOf(56.dp) }
    val density = LocalDensity.current
    // 输入法高度与系统导航栏高度分开取 —— 两个间隙要能各自调。
    val imeInset = with(density) { WindowInsets.Companion.ime.getBottom(this).toDp() }
    val systemBarInset = with(density) { WindowInsets.Companion.navigationBars.getBottom(this).toDp() }
    // 沉浸式小白条：内容区铺到物理底边（照 DeepSearch NanoMuseHome.kt 510 +
    // ChatScreen.kt 2725 的 imePadding），输入框自己让开导航条。
    // 只给内容区加 bottom padding 会把它裁在系统栏上沿，下面露出一条白底。
    val composerBottomPadding = composerBottomPadding(
        imeInset = imeInset,
        navigationBars = systemBarInset,
        composerImeGap = Dshm.spacing.composerImeGap,
        composerBottomInset = Dshm.spacing.composerBottomInset,
    )
    // 输入框 + 它下面的留白 = 列表要避开的整块高度（DeepSearch 的 bottomChromeHeight）。
    val bottomChromeHeight = composerHeight + composerBottomPadding
    var chatError by remember { mutableStateOf<String?>(null) }
    var stopError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Dshm.colors.background,
        // 系统栏 inset 全部自己消化，内容铺满整屏；只有输入法顶开内容区。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                        if (bot.isGroup) {
                            RoomAvatar(
                                members = bot.roomMembers,
                                size = Dshm.spacing.avatar,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .zIndex(1f),
                            )
                        } else {
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
        // 悬浮式底部（照 DeepSearch 的 NmChatBottomLayout）：
        // 消息列表铺满整屏，输入框叠在它上面 —— 列表能穿过输入框下面看到内容，
        // 最后一条消息靠 contentPadding 避开输入框。
        // 照 DeepSearch（ChatScreen.kt 2720）：整个内容区被输入法顶上去，
        // 输入框自己再处理系统栏留白。两者分开，别手动混算。
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 无输入法时不留任何底部 padding —— 消息列表一直画到物理底边，
                // 小白条浮在内容上；输入框自己用 composerBottomPadding 让开它。
                .imePadding(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
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
                        activity = state.activity,
                        lastSendAt = lastSendAt,
                        bot = bot,
                        fontScale = fontScale,
                        topPadding = innerPadding.calculateTopPadding(),
                        // 最后一条消息避开输入框（输入框浮在列表上面）：
                        // 输入框高度 + 一点留白。
                        bottomPadding = chatListBottomPadding(
                            composerHeight = composerHeight,
                            composerBottomPadding = composerBottomPadding,
                            composerClearance = Dshm.spacing.composerClearance,
                        ),
                        working = working,
                        hasEarlier = state.hasEarlier,
                        loadingEarlier = state.loadingEarlier,
                        sendCount = sendCount,
                        bottomChromeHeight = bottomChromeHeight,
                        autoLoadEnabled = state.error == null,
                        onLoadEarlier = viewModel::loadEarlier,
                        onOpenMember = onOpenMember,
                    )
                }
            }
            // 审批卡片：Bot 要跑命令时，DSH 那边等着一个确认。
            //
            // 位置：贴在输入框**正上方**（输入框是浮在消息列表上的）。
            // 之前没对齐，卡片跑到了屏幕顶部被顶栏盖住。
            // 多条请求要竖着排：每张卡各自 align(BottomCenter) 会完全叠在一起，
            // 群聊里两个成员同时等确认时就只看得到最上面那一张。
            if (state.approvals.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            start = Dshm.spacing.lg,
                            end = Dshm.spacing.lg,
                            // 紧贴输入框的上沿 —— 输入框底边已经在 Box 底边了
                            // （外层的 padding(bottom) 扣掉了输入法/系统栏），
                            // 这里只要让开输入框自己的高度。
                            bottom = bottomChromeHeight + Dshm.spacing.xs,
                        ),
                    verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xs),
                ) {
                    state.approvals.forEach { approval ->
                        ApprovalCard(
                            approval = approval,
                            busy = answeringApproval,
                            onAnswer = { allow ->
                                answeringApproval = true
                                viewModel.answerApproval(approval, allow) { error ->
                                    answeringApproval = false
                                    if (error != null) chatError = error
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
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
            stopError?.let { error ->
                Text(
                    text = error,
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
                        sendCount += 1
                        lastSendAt = System.currentTimeMillis()
                    }
                },
                placeholder = stringResource(R.string.composer_placeholder, bot.name),
                textScale = fontScale,
                voiceMode = voiceMode,
                speech = viewModel.speechRepository,
                sendEnabled = ComposerRules.canSend(draft) && !state.sending,
                stopVisible = state.stopVisible,
                onStop = {
                    stopError = null
                    viewModel.stopRunning { error -> stopError = error }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = Dshm.spacing.lg,
                        end = Dshm.spacing.lg,
                        bottom = composerBottomPadding,
                    )
                    // 放在 padding 内层，测到的才是输入框自身高度（不含下面那段留白）。
                    .onSizeChanged { size -> composerHeight = with(density) { size.height.toDp() } },
            )
        }
    }
    if (quickSettings) {
        if (bot.isGroup) {
            RoomSettingsSheet(
                row = bot,
                endpoint = endpoint,
                onDismiss = { quickSettings = false },
                onDetails = {
                    quickSettings = false
                    onDetails()
                },
                onOpenMember = onOpenMember,
            )
        } else {
            BotSettingsSheet(
                bot = bot,
                endpoint = endpoint,
                onDismiss = { quickSettings = false },
                onDetails = {
                    quickSettings = false
                    onDetails()
                },
                onBrowser = {},
            )
        }
    }
}

/**
 * 距顶多少像素内就自动往前翻页。
 *
 * 照 dsh-Go（`bot_chat_kit.dart` 的 `above < 400`）：不是要滑到最顶，
 * 而是接近顶部就开始加载，用户不用刻意拉到头。
 */
private const val EARLIER_TRIGGER_PX = 400

/**
 * 倒序列表里，末尾这么多项之内就算「接近顶部」（要往前翻历史了）。
 */
private const val EARLIER_TRIGGER_ITEMS = 3

/** 发送后这段时间内强制跟随最新消息，跨过用户气泡→思考→流式气泡的切换。 */
private const val STREAM_FOLLOW_GRACE_MS = 2_000L

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

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
private fun ChatMessages(
    entries: List<ChatEntry>,
    streaming: String,
    activity: ChatActivity?,
    bot: ChatRowUi,
    fontScale: Float,
    topPadding: Dp,
    bottomPadding: Dp,
    working: Boolean,
    hasEarlier: Boolean = false,
    loadingEarlier: Boolean = false,
    /** 发送了几次 —— 每发一次就重新钉到底。 */
    sendCount: Int = 0,
    /** 最近一次发送时间：发送后的短暂宽限内强制跟随，不让新气泡藏在输入框下。 */
    lastSendAt: Long = 0L,
    /** 输入框 + 它下面留白的实测高度 —— 列表要留出这么多才不会被盖住。 */
    bottomChromeHeight: Dp = 56.dp,
    /** 上次加载出错时不再自动触发（否则会一直重试）。 */
    autoLoadEnabled: Boolean = true,
    onOpenMember: (String) -> Unit = {},
    onLoadEarlier: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val typing = activity != null
    val streamingNow = streaming.isNotEmpty()

    // 列表是**倒序**的（照 DeepSearch 的 ChatScreen）：index 0 是最新消息、
    // 显示在底部。Compose 的 reverseLayout 原生就会把底部锚住 ——
    // 不手动 scrollToItem，让它自己贴底（手动滚反而会和它打架）。
    //
    // DeepSearch 的四条结论：
    // 1. 信 reverseLayout 的原生底部锚定，不要每个流式 token 都 scrollToItem；
    // 2. 只在「终止事件」（发送、流结束）才自动跟随，不逐 token 跟；
    // 3. 「用户离开了底部」是**派生值**，不是手势改出来的状态；
    // 4. 另给一个「回到底部」按钮当万能出口。
    //
    // 「在底部」的判定：`firstVisibleItemIndex == 0` 且滚动偏移在 32dp 内。
    val scope = rememberCoroutineScope()
    val draggedByUser by listState.interactionSource.collectIsDraggedAsState()
    var userDragged by remember { mutableStateOf(false) }
    val nearBottomThresholdPx = with(LocalDensity.current) { 32.dp.toPx() }.toInt()
    val isNearBottom by remember(listState, nearBottomThresholdPx) {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset <= nearBottomThresholdPx
        }
    }
    // 只有手指真正拖过列表才算「用户离开底部」。流式气泡自己长高会让
    // firstVisibleItemIndex / isNearBottom 漂移，那个不是滚动意图；
    // 用 isScrollInProgress 判断会把自动跟随自己关掉，流式就跟丢了。
    LaunchedEffect(draggedByUser, isNearBottom) {
        if (draggedByUser && !isNearBottom) userDragged = true
        if (!draggedByUser && isNearBottom) userDragged = false
    }
    LaunchedEffect(streamingNow) {
        if (streamingNow) userDragged = false
    }
    LaunchedEffect(sendCount) {
        if (sendCount > 0) userDragged = false
    }

    // 滑到接近顶部就自动往前翻一页。
    //
    // 倒序列表里 `firstVisibleItemIndex` 恒为 0（视觉底部就是最新），
    // 判断「滑到了顶部」要看**最后一个可见项**离列表末尾有多远 ——
    // 参照 DeepSearch 用 layoutInfo 定位可见集合的做法。
    LaunchedEffect(hasEarlier, loadingEarlier, entries.size, autoLoadEnabled) {
        if (!hasEarlier || loadingEarlier || !autoLoadEnabled) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            (last?.index ?: 0) to info.totalItemsCount
        }.collect { (lastIndex, total) ->
            // 距离列表末尾只剩几项了 → 快到顶了，往前翻一页。
            if (total > 0 && total - lastIndex <= EARLIER_TRIGGER_ITEMS) {
                onLoadEarlier()
            }
        }
    }

    // 发送后立刻滚到底（照 DeepSearch 的 SEND-PATH）：
    // 第一次钉在回显之前，等 100ms 让用户消息到达，再钉一次。
    LaunchedEffect(sendCount, lastSendAt) {
        if (sendCount == 0) return@LaunchedEffect
        listState.scrollToItem(0)
        delay(100)
        listState.scrollToItem(0)
    }

    // 流式输出时跟着到底（照 DeepSearch 的 T256 流水线）：
    // snapshotFlow 发元组 → conflate 丢掉中间值 → sample 限频到约 8Hz。
    // 不用「每个 token 都重启一次滚动动画」—— 那会把 UI 线程压垮。
    LaunchedEffect(listState, bottomChromeHeight) {
        snapshotFlow { streaming.length to bottomChromeHeight }
            .conflate()
            .sample(120L)
            .collect { (length, _) ->
                if (length == 0 || userDragged || draggedByUser) return@collect
                if (listState.isScrollInProgress) return@collect
                listState.scrollToItem(0)
            }
    }

    // 钉到底（照 DeepSearch 的 T170「三段式钉住」）：
    // 第一次 scrollToItem(0) 钉的是自增高之前的位置，等一帧再钉一次
    // 就能抓住「同一格子里内容又长高了」之后的位置。
    //
    // 什么时候钉：
    // - 第一次有内容（进对话要看到最新消息）—— 无条件钉；
    // - 来了新消息 —— 用户还在底部才钉（否则会抢走他的滚动）。
    var pinnedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(entries.size) {
        if (entries.isEmpty()) return@LaunchedEffect
        val isFirst = !pinnedOnce
        pinnedOnce = true
        val sendGrace = lastSendAt > 0L &&
            System.currentTimeMillis() - lastSendAt <= STREAM_FOLLOW_GRACE_MS
        if (!isFirst && userDragged) return@LaunchedEffect
        if (!isFirst && !sendGrace && !isNearBottom) return@LaunchedEffect
        listState.scrollToItem(0)
        // 等一帧再钉一次 —— 抓住同一格子的异步自增高。
        withFrameNanos { }
        if (isNearBottom || isFirst || sendGrace) {
            listState.scrollToItem(0)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = true,
            contentPadding = PaddingValues(
                start = Dshm.spacing.lg,
                end = Dshm.spacing.lg,
                top = topPadding + Dshm.spacing.md,
                bottom = Dshm.spacing.none,
            ),
            // 气泡间距：同一个人的连续气泡 8dp；换人说话（用户→大模型）多留一点，
            // 让「谁在说话」一眼能分出来。
            verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
        ) {
            // reverseLayout 下最先声明的 item 画在视觉最底部。这个 spacer 就是
            // 「最新气泡 → 输入框」之间的真实可滚动留白：scrollToItem(0) 会把它
            // 钉在物理底边，最新气泡自然停在输入框上沿。
            item(key = "composer-clearance") {
                Spacer(Modifier.height(bottomPadding))
            }
            // 倒序：最新的排在 index 0（底部）。所以流式气泡和思考行排在最前。
            if (typing) {
                item(key = "typing") {
                    ChatActivityRow(
                        bot = bot,
                        activity = requireNotNull(activity),
                        fallbackStartedAtMs = lastSendAt,
                    )
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
            items(entries.size, key = { index -> entries[entries.size - 1 - index].id }) { index ->
                val entry = entries[entries.size - 1 - index]
                // 倒序：上一条在列表里是「更新的那条」（index - 1）。
                val newer = entries.getOrNull(entries.size - index)
                val turnsChanged = newer != null && newer.kind != entry.kind &&
                    (newer.kind == ChatEntryKind.USER || entry.kind == ChatEntryKind.USER)
                // 群聊里同一 bot 连续的多条回复合成一组（对齐 DS Bot `stackReplies`）：
                // 只有当视觉上的上一条不是同 bot 的 bot 消息时才显示头像+名字。
                val sameBotContinuation = bot.isGroup && entry.kind == ChatEntryKind.BOT &&
                    entry.botId != null && newer?.kind == ChatEntryKind.BOT && newer.botId == entry.botId
                val showGroupAuthor = bot.isGroup && entry.kind == ChatEntryKind.BOT && !sameBotContinuation
                Column(
                    modifier = Modifier.padding(
                        top = if (turnsChanged) Dshm.spacing.turnGap - Dshm.spacing.sm else Dshm.spacing.xs,
                    ),
                ) {
                when (entry.kind) {
                    ChatEntryKind.USER -> UserBubble(entry.text, fontScale)
                    ChatEntryKind.BOT -> BotBubble(
                        text = entry.text,
                        author = entry.author,
                        showAuthor = showGroupAuthor,
                        botId = entry.botId,
                        members = bot.roomMembers,
                        adminId = bot.roomAdmin,
                        onOpenMember = onOpenMember,
                        fontScale = fontScale,
                        isContinuation = sameBotContinuation,
                    )
                    ChatEntryKind.EVENT -> EventLine(entry.text)
                }
                }
            }
            // 更早的历史：加载圈挂在列表顶部。
            if (loadingEarlier) {
                item(key = "load-earlier") {
                    LoadingEarlierRow()
                }
            }
        }
        // 普通高斯模糊 + 渐变遮罩（B 方案）：`Modifier.blur` 底层就是 Android
        // RenderEffect 的高斯模糊，只作用在这层渐变上；不录消息层、不做 DstIn /
        // 混合采样，也就不会把液态玻璃那套带进来导致渲染一闪一闪。
        // 渐变一直盖到物理底边，小白条下面同样有覆盖。
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(bottomChromeHeight + Dshm.spacing.xxl)
                .blur(
                    radius = Dshm.spacing.composerBlurRadius,
                    edgeTreatment = BlurredEdgeTreatment.Unbounded,
                )
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.28f to Dshm.colors.background.copy(alpha = 0.78f),
                        1f to Dshm.colors.background.copy(alpha = 0.94f),
                    ),
                ),
        )
        if (!isNearBottom) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // 贴在输入框上沿（输入框是浮在列表上的，位置要按它的实测高度算）。
                    .padding(bottom = bottomChromeHeight + Dshm.spacing.composerClearance)
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
                            // 倒序列表：index 0 就是底部（最新）。
                            listState.scrollToItem(0)
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

/**
 * DS Bot 的活动状态行：头像 + 当前状态文案 + 本回合已用时间。
 *
 * 对齐 `ActivityIndicator`：文案至少展示 800ms，计时从回合开始而不是组件挂载开始；
 * 行本身跟着 `running` 走，流式文字开始后也不提前撤掉。
 */
@Composable
private fun ChatActivityRow(
    bot: ChatRowUi,
    activity: ChatActivity,
    fallbackStartedAtMs: Long,
) {
    var shown by remember { mutableStateOf(activity) }
    var shownAt by remember { mutableStateOf(0L) }
    var phraseIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(activity) {
        val now = System.currentTimeMillis()
        val holdLeft = shownAt + CHAT_ACTIVITY_PHRASE_HOLD_MS - now
        if (holdLeft > 0) delay(holdLeft)
        shown = activity
        shownAt = System.currentTimeMillis()
        if (activity.label == null) phraseIndex += 1
    }
    SideEffect {
        if (shownAt == 0L) {
            shown = activity
            shownAt = System.currentTimeMillis()
        }
    }
    val startedAt = shown.startedAtMs?.takeIf { it > 0L } ?: fallbackStartedAtMs
    var elapsedMs by remember { mutableStateOf(0L) }
    LaunchedEffect(startedAt) {
        while (true) {
            elapsedMs = if (startedAt > 0L) {
                (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            } else {
                0L
            }
            kotlinx.coroutines.delay(1_000)
        }
    }
    val label = chatActivityLabel(shown, phraseIndex)
    val transition = rememberInfiniteTransition(label = "thinking")
    val scale by transition.animateFloat(
        initialValue = if (shown.kind == ChatActivityKind.ALERT) 1f else 0.94f,
        targetValue = if (shown.kind == ChatActivityKind.ALERT) 1f else 1.06f,
        animationSpec = infiniteRepeatable(
            tween(if (shown.kind == ChatActivityKind.ALERT) 1_200 else 620),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thinking-scale",
    )
    Row(
        modifier = Modifier.padding(horizontal = Dshm.spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bot.isGroup) {
            RoomAvatar(
                members = bot.roomMembers,
                size = Dshm.spacing.lg + Dshm.spacing.sm,
                modifier = Modifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            )
        } else {
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
        }
        Text(
            text = label,
            style = Dshm.typography.rowPreview,
            color = Dshm.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = "· ${chatActivityElapsed(elapsedMs)}",
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
    botId: String? = null,
    members: List<com.dshm.bot.ui.home.RoomMemberUi> = emptyList(),
    adminId: String? = null,
    onOpenMember: (String) -> Unit = {},
    isContinuation: Boolean = false,
) {
    val member = members.firstOrNull { it.id == botId }
    val name = member?.name ?: author
    val isAdmin = botId != null && botId == adminId
    val avatarSize = Dshm.spacing.lg + Dshm.spacing.sm
    Row(modifier = Modifier.fillMaxWidth()) {
        // 组头显示成员头像；同 bot 连续的消息用等宽占位，让气泡保持对齐。
        if (showAuthor && !name.isNullOrBlank() && !isContinuation) {
            if (member != null) {
                BotAvatar(
                    inkId = member.inkId,
                    glyph = AvatarGlyph.BOT,
                    avatarImage = member.avatarImage,
                    avatarShape = member.avatarShape,
                    size = avatarSize,
                    modifier = Modifier
                        .padding(top = Dshm.spacing.sm)
                        .then(if (botId != null) Modifier.clickable { onOpenMember(botId) } else Modifier),
                )
            } else {
                Spacer(Modifier.width(avatarSize))
            }
        } else if (isContinuation) {
            Spacer(Modifier.width(avatarSize))
        }
        Spacer(Modifier.width(Dshm.spacing.sm))
        Column(modifier = Modifier.weight(1f, fill = false)) {
            if (showAuthor && !name.isNullOrBlank() && !isContinuation) {
                Row(
                    modifier = Modifier
                        .padding(bottom = Dshm.spacing.xxs)
                        .then(
                            if (botId != null) Modifier.clickable { onOpenMember(botId) } else Modifier,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = name,
                        style = Dshm.typography.rowTitle.copy(fontSize = 13.sp),
                        color = Dshm.colors.textPrimary,
                    )
                    if (isAdmin) {
                        Spacer(Modifier.width(Dshm.spacing.xs))
                        Text(
                            text = stringResource(R.string.room_settings_admin_badge),
                            style = Dshm.typography.rowTime.copy(fontSize = 11.sp),
                            color = Dshm.colors.textSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(Dshm.radius.xs))
                                .background(Dshm.colors.surfaceMuted)
                                .padding(horizontal = Dshm.spacing.xs, vertical = Dshm.spacing.xxs),
                        )
                    }
                }
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
