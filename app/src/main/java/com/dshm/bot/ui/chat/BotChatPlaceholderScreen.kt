package com.dshm.bot.ui.chat

import android.net.Uri
import android.provider.OpenableColumns
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
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
import androidx.core.content.FileProvider
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.runtime.RuntimeEndpoint
import com.dshm.bot.domain.model.AvatarGlyph
import com.dshm.bot.domain.model.VoiceMode
import com.dshm.bot.ui.components.BotAvatar
import com.dshm.bot.ui.components.FloatingIconButton
import com.dshm.bot.ui.home.ChatRowUi
import com.dshm.bot.ui.home.RoomMemberUi
import com.dshm.bot.ui.home.TeamRoomUi
import com.dshm.bot.ui.settings.SettingsRow
import com.dshm.bot.ui.settings.SettingsRowDivider
import com.dshm.bot.ui.settings.SettingsSection
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
    var pendingAttachments by remember { mutableStateOf<List<ChatAttachmentDraft>>(emptyList()) }
    val attachContext = LocalContext.current
    val attachScope = rememberCoroutineScope()
    var cameraFile by remember { mutableStateOf<File?>(null) }

    // 一轮浏览器操作结束后，往对话里贴一张页面缩略图（这一轮只贴一张，抓不到就跳过）。
    val browserStore = remember { BotBrowserStore.of(attachContext) }
    var browserTurn by remember(bot.id) { mutableStateOf(false) }
    LaunchedEffect(state.running) {
        if (state.running) {
            browserTurn = true
            return@LaunchedEffect
        }
        if (!browserTurn) return@LaunchedEffect
        browserTurn = false
        val session = browserStore.existing(bot.id) ?: return@LaunchedEffect
        if (!session.consumeAgentMark()) return@LaunchedEffect
        val tab = session.activeTab() ?: return@LaunchedEffect
        val bitmap = session.captureThumbnail() ?: return@LaunchedEffect
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(attachContext.cacheDir, "browser-shots").apply { mkdirs() }
                val target = File(dir, "shot-${System.currentTimeMillis()}.png")
                target.outputStream().use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 85, out)
                }
                target
            }.getOrNull()
        }
        bitmap.recycle()
        if (file == null) return@LaunchedEffect
        viewModel.addBrowserShot(
            ChatBrowserShot(
                path = file.absolutePath,
                url = tab.url,
                title = tab.title,
                timeMs = System.currentTimeMillis(),
            ),
        )
    }

    fun displayName(uri: Uri): String {
        val fromResolver = runCatching {
            attachContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        return fromResolver?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "file"
    }

    fun addAttachment(uri: Uri, isImage: Boolean) {
        attachScope.launch {
            val draftAttachment = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = attachContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null || bytes.isEmpty()) return@runCatching null
                    val name = displayName(uri)
                    // 留一份在缓存里：气泡里的缩略图 / 文件卡按路径读，内存里的字节发完就没了。
                    val dir = File(attachContext.cacheDir, "chat-attachments").apply { mkdirs() }
                    val cached = File(dir, "${System.currentTimeMillis()}-${name.replace('/', '_')}")
                    cached.outputStream().use { it.write(bytes) }
                    ChatAttachmentDraft(name, bytes, isImage, cached.absolutePath)
                }.getOrNull()
            }
            if (draftAttachment == null) {
                Toast.makeText(attachContext, "读取附件失败", Toast.LENGTH_SHORT).show()
            } else {
                pendingAttachments = (pendingAttachments + draftAttachment).take(10)
            }
        }
    }

    fun addAttachment(file: File, isImage: Boolean) {
        attachScope.launch {
            val draftAttachment = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = file.readBytes()
                    if (bytes.isEmpty()) {
                        null
                    } else {
                        ChatAttachmentDraft(file.name, bytes, isImage, file.absolutePath)
                    }
                }.getOrNull()
            }
            if (draftAttachment == null) {
                Toast.makeText(attachContext, "读取附件失败", Toast.LENGTH_SHORT).show()
            } else {
                pendingAttachments = (pendingAttachments + draftAttachment).take(10)
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri -> addAttachment(uri, isImage = false) }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { uri -> addAttachment(uri, isImage = true) }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraFile?.let { addAttachment(it, isImage = true) }
        cameraFile = null
    }
    fun takePhoto() {
        val dir = File(attachContext.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        cameraFile = file
        val uri = FileProvider.getUriForFile(attachContext, attachContext.packageName + ".fileprovider", file)
        cameraLauncher.launch(uri)
    }
    var quickSettings by remember { mutableStateOf(false) }
    var exchangeWith by remember(bot.id) { mutableStateOf<String?>(null) }
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
    /** 正在提交上方的模型选择：chip 禁用，避免重复点。 */
    var pickingModel by remember { mutableStateOf(false) }

    // 「复制整段对话」：按当前界面上的条目生成 Markdown（网页版详情抽屉的 Share）。
    fun copyTranscript() {
        copyMessage(
            attachContext,
            conversationMarkdown(
                title = bot.name,
                botName = bot.name,
                entries = state.entries,
            ),
        )
    }

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

                    state.loading && state.entries.isEmpty() -> ChatSkeleton(
                        isGroup = bot.isGroup,
                        topPadding = innerPadding.calculateTopPadding(),
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
                        endpoint = endpoint,
                        members = if (bot.isGroup) bot.roomMembers else state.teamMembers,
                        rooms = state.teamRooms,
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
                        onOpenExchange = { id -> exchangeWith = id },
                        onOpenBrowser = onOpenBrowser,
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
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = Dshm.spacing.lg,
                        end = Dshm.spacing.lg,
                        bottom = composerBottomPadding,
                    )
                    // 放在 padding 内层，测到的是「待办 dock + 输入框」整块的自身高度
                    // （不含下面那段留白）—— 列表留白要跟着它走，卡片才不会压住消息。
                    .onSizeChanged { size -> composerHeight = with(density) { size.height.toDp() } },
            ) {
            // 待办 dock：提问 / 视图 / 审批（DS Bot 的 `conversation.input.dock`）。
            PendingDock(
                botName = bot.name,
                approvals = state.approvals,
                questions = state.pendingQuestions,
                views = state.pendingViews,
                secrets = state.pendingSecrets,
                busy = answeringApproval || state.answeringPending,
                onAnswerApproval = { approval, allow ->
                    answeringApproval = true
                    viewModel.answerApproval(approval, allow) { error ->
                        answeringApproval = false
                        if (error != null) chatError = error
                    }
                },
                onAnswerQuestionApproval = { approval, selected ->
                    answeringApproval = true
                    viewModel.answerQuestion(approval, selected) { error ->
                        answeringApproval = false
                        if (error != null) chatError = error
                    }
                },
                onAnswerQuestion = { card, selected, custom ->
                    viewModel.answerChatQuestion(card, selected, custom) { error ->
                        if (error != null) chatError = error
                    }
                },
                onAnswerView = { card, action, values ->
                    viewModel.answerChatView(card, action, values) { error ->
                        if (error != null) chatError = error
                    }
                },
                onDismissView = { card -> viewModel.dismissChatView(card) },
                onSecretSubmit = { request, value, onlyThisBot ->
                    viewModel.submitChatSecret(request, value, onlyThisBot) { error ->
                        if (error != null) chatError = error
                    }
                },
                onSecretAllow = { request ->
                    viewModel.allowChatSecret(request) { error ->
                        if (error != null) chatError = error
                    }
                },
                onSecretCancel = { request ->
                    viewModel.cancelChatSecret(request) { error ->
                        if (error != null) chatError = error
                    }
                },
            )
            // 还没有模型（或模型没了）：先让用户选一个，否则这个 Bot 谁也喊不动。
            state.modelPrompt?.let { prompt ->
                ModelChoiceCard(
                    prompt = prompt,
                    botName = bot.name,
                    busy = pickingModel,
                    onPick = { ref ->
                        pickingModel = true
                        viewModel.chooseModel(ref) { error ->
                            pickingModel = false
                            if (error != null) chatError = error
                        }
                    },
                )
            }
            // 群聊跑到中转上限会停下等用户：这一行 + 「继续」走 `room-continue`。
            RoomPausedCard(
                paused = state.roomPaused,
                busy = state.roomContinuing,
                onContinue = {
                    viewModel.continueRoom { error -> if (error != null) chatError = error }
                },
            )
            ChatComposer(
                value = draft,
                onValueChange = { draft = it },
                onSend = {
                    if ((ComposerRules.canSend(draft) || pendingAttachments.isNotEmpty()) && !state.sending) {
                        viewModel.send(draft, pendingAttachments)
                        draft = ""
                        pendingAttachments = emptyList()
                        sendCount += 1
                        lastSendAt = System.currentTimeMillis()
                    }
                },
                placeholder = stringResource(R.string.composer_placeholder, bot.name),
                textScale = fontScale,
                voiceMode = voiceMode,
                speech = viewModel.speechRepository,
                onPickFile = { filePicker.launch(arrayOf("*/*")) },
                onPickImage = { imagePicker.launch("image/*") },
                onTakePhoto = { takePhoto() },
                mentionCandidates = if (bot.isGroup) bot.roomMembers.map { it.name } else emptyList(),
                attachments = pendingAttachments,
                onRemoveAttachment = { index ->
                    pendingAttachments = pendingAttachments.filterIndexed { i, _ -> i != index }
                },
                sendEnabled = (ComposerRules.canSend(draft) || pendingAttachments.isNotEmpty()) && !state.sending,
                stopVisible = state.stopVisible,
                onStop = {
                    stopError = null
                    viewModel.stopRunning { error -> stopError = error }
                },
            )
            }
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
                onCopyTranscript = { copyTranscript() },
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
                onBrowser = onOpenBrowser,
                onCopyTranscript = { copyTranscript() },
            )
        }
    }
    // Bot 间对话：DS Bot 的 `ExchangeDialog`（点「消息发给了 X / 回复来自 X」打开）。
    exchangeWith?.let { otherId ->
        BotExchangeDialog(
            selfId = bot.id,
            otherId = otherId,
            endpoint = endpoint,
            selfLook = state.teamMembers.firstOrNull { it.id == bot.id } ?: bot.asMemberLook(),
            otherLook = state.teamMembers.firstOrNull { it.id == otherId },
            fontScale = fontScale,
            onDismiss = { exchangeWith = null },
        )
    }
}

/** 本对话 Bot 的身份（团队表里没有时自己拼一份）。 */
private fun ChatRowUi.asMemberLook(): RoomMemberUi = RoomMemberUi(
    id = id,
    name = name,
    inkId = inkId,
    avatarImage = avatarImage,
    avatarShape = avatarShape,
)

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
    endpoint: RuntimeEndpoint?,
    /** 事件行认人用的身份表：群聊用成员，单聊用团队。 */
    members: List<com.dshm.bot.ui.home.RoomMemberUi>,
    /** 团队的群聊（事件行「阅读群聊 / 群发言」要画成员头像堆叠）。 */
    rooms: List<com.dshm.bot.ui.home.TeamRoomUi>,
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
    /** 点事件行打开「Bot 间对话」（DS Bot `openExchange`）。 */
    onOpenExchange: (String) -> Unit = {},
    onOpenBrowser: () -> Unit = {},
    onLoadEarlier: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val streamingNow = streaming.isNotEmpty()
    // DS Bot `rowVisible`：正在写文字时不显示活动行（文字气泡自己就是「在做」），
    // 但如果这一刻有工具在跑（写入/搜索…），活动行仍然留着。
    val typing = activity != null && (!streamingNow || activity.kind != ChatActivityKind.THINKING)

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

    // 流式态 / 思考行和正式条目走同一套间距规则（`chatItemSpacing`）：
    // 否则「流式中 8dp → 落地 12dp」会抖一下。这里要在 LazyColumn 外算好 ——
    // `Dshm.spacing` 是 @Composable，LazyListScope 里取不到。
    val pendingSpacing = chatItemSpacing(
        spacing = Dshm.spacing,
        entryKind = ChatEntryKind.BOT,
        entryBotId = bot.id,
        olderKind = entries.lastOrNull()?.kind,
        olderBotId = entries.lastOrNull()?.botId,
        isGroup = bot.isGroup,
    )

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
            // 间距全部由每条自己的 top padding 决定（同人 4dp / 换人 12dp），
            // 这里不再叠加，否则两条规则会互相抵消。
            verticalArrangement = Arrangement.spacedBy(Dshm.spacing.none),
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
                    Box(Modifier.padding(top = pendingSpacing.top)) {
                        ChatActivityRow(
                            bot = bot,
                            activity = requireNotNull(activity),
                            fallbackStartedAtMs = lastSendAt,
                        )
                    }
                }
            }
            if (streaming.isNotEmpty()) {
                item(key = "streaming") {
                    Box(Modifier.padding(top = pendingSpacing.top)) {
                        BotBubble(
                            text = streaming,
                            author = bot.name,
                            fontScale = fontScale,
                            // 和最终那条 BOT 条目画得一模一样（群聊里同样有头像 + 名字），
                            // 这样文字写完落地时形状不变。
                            isGroup = bot.isGroup,
                            showName = pendingSpacing.groupStart,
                            botId = bot.id,
                            members = bot.roomMembers,
                            adminId = bot.roomAdmin,
                            onOpenMember = onOpenMember,
                        )
                    }
                }
            }
            items(entries.size, key = { index -> entries[entries.size - 1 - index].id }) { index ->
                val chrono = entries.size - 1 - index
                val entry = entries[chrono]
                // 视觉相邻：older 在上面，newer 在下面（chronological 顺序）。
                val older = entries.getOrNull(chrono - 1)
                // 间距 / 分组只有一套规则：见 `chatItemSpacing`（流式态也用它）。
                val spacing = chatItemSpacing(
                    spacing = Dshm.spacing,
                    entryKind = entry.kind,
                    entryBotId = entry.botId,
                    olderKind = older?.kind,
                    olderBotId = older?.botId,
                    isGroup = bot.isGroup,
                )
                Column(
                    modifier = Modifier.padding(top = spacing.top),
                ) {
                when (entry.kind) {
                    ChatEntryKind.USER -> UserBubble(
                        text = entry.text,
                        fontScale = fontScale,
                        attachments = entry.attachments,
                        items = entry.attachmentItems,
                        images = entry.images,
                        timeMs = null,
                    )
                    ChatEntryKind.BOT -> BotBubble(
                        text = entry.text,
                        author = entry.author,
                        fontScale = fontScale,
                        isGroup = bot.isGroup,
                        showName = spacing.groupStart,
                        botId = entry.botId,
                        members = bot.roomMembers,
                        adminId = bot.roomAdmin,
                        onOpenMember = onOpenMember,
                        timeMs = null,
                        durationMs = null,
                        whole = entry.whole,
                        endpoint = endpoint,
                    )
                    ChatEntryKind.EVENT -> {
                        // 工具事件的目标：DS Bot `renderCall` 给的是目标 Bot / 群聊的头像，
                        // 点开的是目标（`openExchange`）。relay 事件的目标是发件人（`botId`）。
                        val targetMember = entry.target
                            ?.let { name -> members.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                        val targetRoom = entry.target
                            ?.let { name -> rooms.firstOrNull { it.name == name } }
                        val clickId = entry.botId?.takeIf { it != bot.id } ?: targetMember?.id
                        EventLine(
                            text = entry.text,
                            kind = entry.eventKind,
                            name = entry.author,
                            code = entry.eventCode,
                            timeMs = null,
                            botId = clickId,
                            // 工具事件行画的是「目标」，不套本对话 Bot 自己的头像。
                            bot = if (bot.isGroup || entry.tool != null) null else bot,
                            members = members,
                            room = targetRoom,
                            iconRes = when (entry.tool) {
                                "remember", "forget" -> R.drawable.ic_dsbot_memory
                                else -> null
                            },
                            onOpen = clickId?.let { id -> { onOpenExchange(id) } },
                        )
                    }
                    ChatEntryKind.ARTIFACTS -> ArtifactCard(
                        artifacts = entry.artifacts,
                        endpoint = endpoint,
                        sessionId = bot.id,
                    )

                    ChatEntryKind.BROWSER_SHOT -> BrowserShotCard(
                        shot = entry.shot,
                        onClick = onOpenBrowser,
                    )

                    // 网页版 `chat` items 的另外三种 kind：分隔线 / 只读视图卡 / 问答回执。
                    ChatEntryKind.DIVIDER -> PartDividerRow(timeMs = entry.timeMs)

                    ChatEntryKind.VIEW -> ViewCardReadOnly(card = entry.view)

                    // 回答过的提问 / 视图卡：网页版分别是 `AnsweredCell` / `AnsweredView`。
                    ChatEntryKind.ANSWERED -> if (entry.answeredView != null) {
                        AnsweredViewCard(answer = entry.answeredView)
                    } else {
                        AnsweredQuestionCard(answered = entry.answered)
                    }
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
    // DS Bot `bt-typing`：群聊状态行显示「正在发言的那个成员」的头像，不是群头像。
    val speakingMember = if (bot.isGroup && shown.memberId != null) {
        bot.roomMembers.firstOrNull { it.id == shown.memberId }
    } else {
        null
    }
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
        if (speakingMember != null) {
            BotAvatar(
                inkId = speakingMember.inkId,
                glyph = AvatarGlyph.BOT,
                avatarImage = speakingMember.avatarImage,
                avatarShape = speakingMember.avatarShape,
                size = Dshm.spacing.lg + Dshm.spacing.sm,
                modifier = Modifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            )
        } else if (bot.isGroup) {
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
private fun UserBubble(
    text: String,
    fontScale: Float,
    attachments: List<String> = emptyList(),
    items: List<ChatAttachmentItem> = emptyList(),
    /** 历史回读里的图片数量（只有计数，没有图）。 */
    images: Int = 0,
    timeMs: Long? = null,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .pointerInput(text) {
                    detectTapGestures(onLongPress = { copyMessage(context, text) })
                }
                .background(
                    Dshm.colors.bubbleUser,
                    RoundedCornerShape(Dshm.radius.lg),
                )
                .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
        ) {
            if (items.isNotEmpty()) {
                UserAttachmentCards(items)
                if (text.isNotBlank()) Spacer(Modifier.height(Dshm.spacing.xs))
            }
            if (text.isNotBlank()) {
                Text(
                    text = text,
                    style = Dshm.typography.composerText.copy(
                        fontSize = Dshm.typography.composerText.fontSize * fontScale,
                        lineHeight = Dshm.typography.composerText.lineHeight * fontScale,
                    ),
                    color = Dshm.colors.onBubbleUser,
                )
            }
            // 老路径（只拿到名字，没有本地文件）：保留原来的回退样子。
            if (items.isEmpty()) attachments.forEach { name ->
                Text(
                    text = "📎 $name",
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.onBubbleUser.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = Dshm.spacing.xxs),
                )
            }
            ImageCountCaption(count = images)
        }
        timeMs?.let { t ->
            Text(
                text = clockLabel(t),
                style = Dshm.typography.rowTime.copy(fontSize = 11.sp),
                color = Dshm.colors.textTertiary,
                modifier = Modifier.padding(top = Dshm.spacing.xxs, end = Dshm.spacing.xs),
            )
        }
    }
}

/** 用户气泡里的附件：图片缩略图 + 文件卡（都画在气泡的主色底上）。 */
@Composable
private fun UserAttachmentCards(items: List<ChatAttachmentItem>) {
    val images = items.filter { it.isImage }
    val files = items.filterNot { it.isImage }
    Column(horizontalAlignment = Alignment.End) {
        if (images.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.xs),
            ) {
                images.take(MAX_USER_THUMBS).forEach { item ->
                    UserThumb(item, Modifier.size(THUMB_SIZE))
                }
            }
            if (images.size > MAX_USER_THUMBS) {
                Text(
                    text = "+${images.size - MAX_USER_THUMBS}",
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.onBubbleUser.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = Dshm.spacing.xxs),
                )
            }
        }
        files.forEach { item ->
            Row(
                modifier = Modifier
                    .padding(top = Dshm.spacing.xs)
                    .clip(RoundedCornerShape(Dshm.radius.sm))
                    .background(Dshm.colors.onBubbleUser.copy(alpha = 0.16f))
                    .padding(horizontal = Dshm.spacing.sm, vertical = Dshm.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_file_text),
                    contentDescription = null,
                    tint = Dshm.colors.onBubbleUser.copy(alpha = 0.85f),
                    modifier = Modifier.size(Dshm.spacing.md),
                )
                Spacer(Modifier.width(Dshm.spacing.xxs))
                Text(
                    text = item.name,
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.onBubbleUser,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 气泡里的一张图片缩略图（方形、圆角、按高度裁切）。 */
@Composable
private fun UserThumb(item: ChatAttachmentItem, modifier: Modifier = Modifier) {
    val bitmap = remember(item.path) {
        runCatching {
            val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
            android.graphics.BitmapFactory.decodeFile(item.path, options)
        }.getOrNull()
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Dshm.spacing.sm))
            .background(Dshm.colors.onBubbleUser.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_image),
                contentDescription = item.name,
                tint = Dshm.colors.onBubbleUser.copy(alpha = 0.8f),
                modifier = Modifier.size(Dshm.spacing.lg),
            )
        }
    }
}

private const val MAX_USER_THUMBS = 3
private val THUMB_SIZE = 96.dp

/**
 * Bot 消息气泡：
 * - 群聊：头像与名字同一行（水平对齐），气泡在下方顶格左对齐（与头像左边缘垂直对齐）；
 *   同一 bot 连续消息只在组首显示一次头像+名字。
 * - 单 Bot：无头像、无名字，只显示气泡。
 */
@Composable
private fun BotBubble(
    text: String,
    author: String?,
    fontScale: Float,
    isGroup: Boolean = false,
    showName: Boolean = false,
    botId: String? = null,
    members: List<com.dshm.bot.ui.home.RoomMemberUi> = emptyList(),
    adminId: String? = null,
    onOpenMember: (String) -> Unit = {},
    timeMs: Long? = null,
    durationMs: Long? = null,
    /** 长文消息：正文折起，完整内容在附件里（网页版「显示全部」）。 */
    whole: ChatWholePost? = null,
    endpoint: RuntimeEndpoint? = null,
) {
    val member = members.firstOrNull { it.id == botId }
    val name = member?.name ?: author
    val isAdmin = botId != null && botId == adminId
    val avatarSize = Dshm.spacing.lg + Dshm.spacing.xs
    val context = LocalContext.current
    var expanded by remember(text) { mutableStateOf(false) }
    // DS Bot 群聊才有「显示全部 / 收起」；单 Bot 不折叠。
    val collapsible = isGroup && text.length > 600

    val bubble: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .pointerInput(text) {
                        detectTapGestures(onLongPress = { copyMessage(context, text) })
                    }
                    .clip(RoundedCornerShape(Dshm.radius.lg))
                    .background(Dshm.colors.bubbleAssistant),
            ) {
                Box(
                    modifier = Modifier
                        .then(
                            if (collapsible && !expanded) {
                                Modifier.heightIn(max = 280.dp).clipToBounds()
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
                ) {
                    MarkdownText(markdown = text, fontScale = fontScale, mentionMembers = members)
                }
                if (collapsible) {
                    androidx.compose.material3.HorizontalDivider(color = Dshm.colors.divider)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded }
                            .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (expanded) {
                                stringResource(R.string.chat_collapse)
                            } else {
                                stringResource(
                                    R.string.chat_show_all,
                                    String.format(java.util.Locale.getDefault(), "%,d", text.length),
                                )
                            },
                            style = Dshm.typography.rowTime,
                            color = Dshm.colors.textTertiary,
                        )
                    }
                }
                // 正文被折起的长文：完整内容在附件里（网页版「显示全部」按 `file-text` 读）。
                whole?.let { post ->
                    androidx.compose.material3.HorizontalDivider(color = Dshm.colors.divider)
                    WholeAttachmentRow(post = post, endpoint = endpoint)
                }
            }
        }
    }

    if (!isGroup) {
        Column(modifier = Modifier.fillMaxWidth()) { bubble() }
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (showName && !name.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .padding(bottom = Dshm.spacing.xxs)
                    .then(
                        if (botId != null) Modifier.clickable { onOpenMember(botId) } else Modifier,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (member != null) {
                    BotAvatar(
                        inkId = member.inkId,
                        glyph = AvatarGlyph.BOT,
                        avatarImage = member.avatarImage,
                        avatarShape = member.avatarShape,
                        size = avatarSize,
                    )
                    Spacer(Modifier.width(Dshm.spacing.sm))
                }
                Text(
                    text = name,
                    style = Dshm.typography.rowTitle.copy(fontSize = 13.sp),
                    // DS Bot 群名行用成员自己的 BotInk 色，不是统一黑。
                    color = member?.let {
                        androidx.compose.ui.graphics.Color(
                            com.dshm.bot.domain.model.BotInks.color(it.inkId, Dshm.colors.isDark),
                        )
                    } ?: Dshm.colors.textPrimary,
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
                timeMs?.let { t ->
                    Spacer(Modifier.width(Dshm.spacing.xs))
                    Text(
                        text = clockLabel(t),
                        style = Dshm.typography.rowTime.copy(fontSize = 11.sp),
                        color = Dshm.colors.textTertiary,
                    )
                }
                durationMs?.let { d ->
                    Spacer(Modifier.width(Dshm.spacing.xs))
                    Text(
                        text = "· ${chatActivityElapsed(d)}",
                        style = Dshm.typography.rowTime.copy(fontSize = 11.sp),
                        color = Dshm.colors.textTertiary,
                    )
                }
            }
        }
        bubble()
    }
}

/** 系统事件（完成、停止、失败等）显示为一行居中小字，对齐 DS Bot `bt-event`。 */
@Composable
private fun EventLine(
    text: String,
    kind: String? = null,
    name: String? = null,
    code: String? = null,
    timeMs: Long? = null,
    botId: String? = null,
    bot: ChatRowUi? = null,
    members: List<RoomMemberUi> = emptyList(),
    room: TeamRoomUi? = null,
    @androidx.annotation.DrawableRes iconRes: Int? = null,
    onOpen: (() -> Unit)? = null,
) {
    val member = members.firstOrNull { it.id == botId }
    // relay 事件（消息来自 / 回复来自 / 叫停）里的 `botId` 是「对方」的会话 id：
    // 先按 id 认人（DS Bot 也是从 roster 认 sender），认不出来就不要乱套本对话的头像。
    val relaySender = RELAY_EVENT_KINDS.contains(kind)
    val subject: Any? = when {
        member != null -> member
        room != null -> room
        // 记忆事件用 DS Bot 的 MemoryIcon，不套自己的头像。
        iconRes != null -> null
        relaySender && botId != null -> null
        bot != null && bot.isGroup -> null
        else -> bot
    }
    val subjectName = when (subject) {
        is RoomMemberUi -> subject.name
        is TeamRoomUi -> subject.name
        is ChatRowUi -> subject.name
        else -> relaySenderName(name)
    }
    val label = eventLineLabel(kind, subjectName, text, code)
    // 失败类事件（failed / missed / cut）用危险色，跟普通事件行分开 ——
    // 否则「没能回答」看起来跟「查阅了自己的对话」一模一样，等于静默。
    val tone = chatEventTone(kind)
    val ink = when {
        tone == ChatEventTone.ERROR -> Dshm.colors.danger
        subject != null -> Dshm.colors.textSecondary
        else -> Dshm.colors.textTertiary
    }
    // DS Bot 的事件行是「前缀 + 头像 + 名字」（`prefix` 在 `BotMark` 前面），
    // 所以把文案按名字切开：名字跟头像贴在一起，前缀留在头像前面。
    val nameAt = subjectName?.let { label.indexOf(it) }?.takeIf { it >= 0 }
    val head = nameAt?.let { label.take(it) }.orEmpty()
    val tail = if (nameAt != null && subjectName != null) {
        label.substring(nameAt + subjectName.length)
    } else {
        ""
    }
    val inlineName = subjectName != null && nameAt != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(vertical = Dshm.spacing.xs),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (inlineName && head.isNotBlank()) {
            Text(
                text = head.trimEnd(),
                style = Dshm.typography.rowTime,
                color = ink,
            )
            Spacer(Modifier.width(Dshm.spacing.xs))
        }
        if (subject != null) {
            when (subject) {
                is TeamRoomUi -> RoomAvatar(members = subject.members, size = Dshm.spacing.lg)
                is RoomMemberUi -> BotAvatar(
                    inkId = subject.inkId,
                    glyph = AvatarGlyph.BOT,
                    avatarImage = subject.avatarImage,
                    avatarShape = subject.avatarShape,
                    size = Dshm.spacing.lg,
                )
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    val row = subject as ChatRowUi
                    BotAvatar(
                        inkId = row.inkId,
                        glyph = row.glyph,
                        avatarImage = row.avatarImage,
                        avatarShape = row.avatarShape,
                        size = Dshm.spacing.lg,
                    )
                }
            }
            Spacer(Modifier.width(Dshm.spacing.xs))
        } else if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = Dshm.colors.textSecondary,
                modifier = Modifier.size(Dshm.spacing.lg),
            )
            Spacer(Modifier.width(Dshm.spacing.xs))
        }
        Text(
            text = if (inlineName) requireNotNull(subjectName) + tail else label,
            style = Dshm.typography.rowTime,
            color = ink,
            textAlign = if (inlineName && head.isNotBlank()) TextAlign.Start else TextAlign.Center,
            modifier = Modifier.weight(1f, fill = false),
        )
        timeMs?.let { t ->
            Spacer(Modifier.width(Dshm.spacing.xs))
            Text(
                text = clockLabel(t),
                style = Dshm.typography.rowTime,
                color = Dshm.colors.textTertiary,
            )
        }
    }
}

/** relay 事件的种类：这些行的「谁」是对方 Bot，不是本对话的 Bot。 */
private val RELAY_EVENT_KINDS = setOf("request", "reply", "report", "stopped")

/** relay 事件里的名字：DS Bot 认不出对方时写的是 `You`，不能当名字用。 */
private fun relaySenderName(name: String?): String? =
    name?.trim()?.takeIf { it.isNotEmpty() && !it.equals("You", ignoreCase = true) }

/** DS Bot `bt-event` 文案：按 event kind 映射，落回原文。 */
@Composable
private fun eventLineLabel(kind: String?, name: String?, fallback: String, code: String?): String {
    val who = name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_event_some_bot)
    val prefix = when (kind) {
        "request" -> "消息来自 $who"
        "reply" -> "回复来自 $who"
        "report" -> "来自群聊的回复"
        "post", "post_to_group" -> if (who.isNotBlank()) "$who 在群里发言" else "在群里发言"
        "create_group" -> if (fallback.isNotBlank()) "创建群聊：$fallback" else "创建了群聊"
        "resume" -> "重启后接着做之前的工作"
        "retry" -> "已要求重试写成文本的工具调用"
        "secret" -> "密钥请求"
        "done" -> "$who 做完了"
        "stopped" -> "你停下了 $who 的工作"
        "working" -> "$who 开始做了"
        "approval" -> "$who 停下来等你批准"
        "missed" -> "$who 没有及时回应"
        "lookup" -> if (fallback.isNotBlank()) "$who 在对话里查找「$fallback」" else "$who 查看了自己的对话"
        "resumed" -> "$who 的工作被重启打断，已接着做"
        "cut" -> "$who 的工作又被重启打断，已停下"
        "schedule" -> fallback.ifBlank { "定时任务已启动" }
        // 失败原因优先按 code 翻成人话（对齐 DS Bot `failureLabel`），没有 code 才用原文。
        "failed" -> "$who 没能回答：${failureLabel(code, fallback)}"
        else -> null
    }
    return prefix ?: (if (fallback.isNotBlank()) fallback else (kind ?: "更新"))
}


private fun clockLabel(timeMs: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = timeMs }
    val hh = String.format("%02d", c.get(java.util.Calendar.HOUR_OF_DAY))
    val mm = String.format("%02d", c.get(java.util.Calendar.MINUTE))
    return "$hh:$mm"
}

private fun copyMessage(context: Context, text: String) {
    if (text.isBlank()) return
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    manager?.setPrimaryClip(ClipData.newPlainText("message", text))
    Toast.makeText(context, context.getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
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
