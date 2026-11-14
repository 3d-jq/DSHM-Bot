package com.dshm.bot

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.core.design.DshmMotion
import com.dshm.bot.data.runtime.RuntimeState
import com.dshm.bot.ui.account.AccountScreen
import com.dshm.bot.ui.account.AccountViewModel
import com.dshm.bot.ui.chat.BotBrowserScreen
import com.dshm.bot.ui.chat.ChatAlertViewModel
import com.dshm.bot.ui.chat.BotBrowserStore
import com.dshm.bot.ui.chat.BrowserGuardStore
import com.dshm.bot.ui.chat.BrowserHandOverStore
import com.dshm.bot.ui.chat.BrowserReadBridge
import com.dshm.bot.service.PhoneReadBridge
import com.dshm.bot.ui.chat.BotChatScreen
import com.dshm.bot.ui.chat.BotDetailsScreen
import com.dshm.bot.ui.chat.BotFolderPage
import com.dshm.bot.ui.chat.BotMemoryPage
import com.dshm.bot.ui.chat.BotNoticePage
import com.dshm.bot.ui.chat.BotSchedulesPage
import com.dshm.bot.ui.chat.BOT_PAGE_FOLDER
import com.dshm.bot.ui.chat.BOT_PAGE_MEMORY
import com.dshm.bot.ui.chat.BOT_PAGE_NOTICE
import com.dshm.bot.ui.chat.BOT_PAGE_SCHEDULES
import com.dshm.bot.ui.chat.NewGroupScreen
import com.dshm.bot.ui.chat.NewBotScreen
import com.dshm.bot.ui.chat.RoomMemberOption
import com.dshm.bot.ui.chat.RoomSettingsScreen
import com.dshm.bot.ui.chat.RoomSettingsViewModel
import com.dshm.bot.ui.engine.DiagnosticsActions
import com.dshm.bot.ui.engine.DiagnosticsScreen
import com.dshm.bot.ui.engine.DiagnosticsViewModel
import com.dshm.bot.ui.engine.EngineScreen
import com.dshm.bot.ui.engine.EngineViewModel
import com.dshm.bot.ui.engine.OnboardingScreen
import com.dshm.bot.ui.home.HomeScreen
import com.dshm.bot.ui.home.HomeViewModel
import com.dshm.bot.ui.home.SearchScreen
import com.dshm.bot.ui.settings.AppVersionScreen
import com.dshm.bot.ui.settings.DshVersionScreen
import com.dshm.bot.ui.settings.AgentPresetsScreen
import com.dshm.bot.ui.settings.AppearanceScreen
import com.dshm.bot.ui.settings.BackgroundSettingsScreen
import com.dshm.bot.ui.settings.PluginMarketViewModel
import com.dshm.bot.ui.settings.ScheduledTasksScreen
import com.dshm.bot.ui.settings.ScheduledTasksViewModel
import com.dshm.bot.ui.launch.LaunchOverlay
import com.dshm.bot.ui.settings.SecretsScreen
import com.dshm.bot.ui.settings.ConnectorsScreen
import com.dshm.bot.ui.settings.CapabilitiesScreen
import com.dshm.bot.ui.settings.UsageScreen
import com.dshm.bot.ui.settings.ChatFontScreen
import com.dshm.bot.ui.settings.EngineSettingsScreen
import com.dshm.bot.ui.settings.EngineSettingsViewModel
import com.dshm.bot.ui.settings.LanguageScreen
import com.dshm.bot.ui.settings.LicensesScreen
import com.dshm.bot.ui.settings.ModelProvidersScreen
import com.dshm.bot.ui.settings.NotificationSettingsScreen
import com.dshm.bot.ui.settings.PermissionScreen
import com.dshm.bot.ui.settings.PersonalizationScreen
import com.dshm.bot.ui.settings.PhoneControlSettingsScreen
import com.dshm.bot.ui.settings.PhoneBotOption
import com.dshm.bot.ui.settings.PluginsScreen
import com.dshm.bot.ui.settings.PrivacySettingsScreen
import com.dshm.bot.ui.settings.SettingsPageId
import com.dshm.bot.ui.settings.BotPrefsViewModel
import com.dshm.bot.ui.settings.SettingsScreen
import com.dshm.bot.ui.settings.SettingsViewModel
import com.dshm.bot.ui.settings.VoiceSettingsScreen
import com.dshm.bot.ui.settings.WebHostScreen
import com.dshm.bot.ui.settings.WebSearchScreen
import com.dshm.bot.ui.settings.chatFontLabel
import com.dshm.bot.ui.settings.languageLabel
import com.dshm.bot.domain.model.DshGoPreferences
import com.dshm.bot.domain.model.scale

private const val SCREEN_HOME = "home"
private const val SCREEN_SEARCH = "search"
private const val SCREEN_SETTINGS = "settings"
private const val SCREEN_ENGINE = "engine"
private const val SCREEN_ONBOARDING = "onboarding"
private const val SCREEN_APPEARANCE = "appearance"
private const val SCREEN_CHAT_FONT = "chat-font"
private const val SCREEN_LANGUAGE = "language"
private const val SCREEN_ACCOUNT = "account"
private const val SCREEN_SETTINGS_PAGE = "settings-page"
private const val SCREEN_NEW_BOT = "new-bot"
private const val SCREEN_NEW_GROUP = "new-group"
private const val SCREEN_DIAGNOSTICS = "diagnostics"
private const val CHAT_PREFIX = "chat:"
private const val BOT_DETAILS_PREFIX = "bot-details:"
private const val ROOM_DETAILS_PREFIX = "room-details:"
private const val BOT_PAGE_PREFIX = "bot-page:"
private const val SCREEN_BOT_BROWSER = "bot-browser"
private const val SCREEN_WEB = "web"

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/**
 * 应用外壳：主界面 ↔ 搜索 ↔ 设置 ↔ Bot 对话。
 * 路由状态放在这里，页面本身不感知导航；所有页面切换都用 iOS 风格整屏 push / pop 动效。
 */
@Composable
fun DshmBotApp(
    homeViewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.factory(LocalContext.current.applicationContext),
    ),
    settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(LocalContext.current.applicationContext),
    ),
    engineViewModel: EngineViewModel = viewModel(
        factory = EngineViewModel.factory(LocalContext.current.applicationContext),
    ),
    accountViewModel: AccountViewModel = viewModel(factory = AccountViewModel.factory(LocalContext.current.applicationContext)),
    engineSettingsViewModel: EngineSettingsViewModel = viewModel(factory = EngineSettingsViewModel.factory()),
    scheduledTasksViewModel: ScheduledTasksViewModel = viewModel(
        factory = ScheduledTasksViewModel.factory(LocalContext.current.applicationContext),
    ),
    pluginMarketViewModel: PluginMarketViewModel = viewModel(factory = PluginMarketViewModel.factory()),
) {
    val appContext = LocalContext.current
    // 首页长按面板的两个动作：所有 HomeScreen 调用点共用（首页有好几个分支，
    // 之前只接了其中一个，实际显示的分支点到的是空回调）。
    val homeTogglePin: (com.dshm.bot.ui.home.ChatRowUi) -> Unit = { row ->
        homeViewModel.setPinned(row.id, !row.pinned) { error ->
            if (error != null) {
                android.widget.Toast.makeText(appContext, error, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    val homeDelete: (com.dshm.bot.ui.home.ChatRowUi) -> Unit = { row ->
        homeViewModel.delete(row.id, row.isGroup) { error ->
            if (error != null) {
                android.widget.Toast.makeText(appContext, error, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    val homeSetMain: (com.dshm.bot.ui.home.ChatRowUi, Boolean) -> Unit = { row, main ->
        homeViewModel.setMain(row.id, main) { error ->
            if (error != null) {
                android.widget.Toast.makeText(appContext, error, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val engineState by engineViewModel.state.collectAsStateWithLifecycle()
    val accountUi by accountViewModel.ui.collectAsStateWithLifecycle()
    val botPrefsViewModel: BotPrefsViewModel = viewModel(
        key = "bot-prefs:${(engineState as? RuntimeState.Running)?.endpoint?.baseUrl.orEmpty()}",
        factory = BotPrefsViewModel.factory((engineState as? RuntimeState.Running)?.endpoint),
    )
    // 对话告警不按端点 key 建 VM：ViewModelStore 不会清理旧 key，引擎每重启一次
    // 就多留一个死端点的订阅。单实例 + setEndpoint，与下面几个 ViewModel 的模式一致。
    val chatAlertViewModel: ChatAlertViewModel = viewModel(
        factory = ChatAlertViewModel.factory(appContext),
    )
    // 诊断页的宿主动作（看完整日志 / 清空诊断）：同样单实例 + setEndpoint。
    val diagnosticsViewModel: DiagnosticsViewModel = viewModel(
        factory = DiagnosticsViewModel.factory(),
    )
    // 「引擎就绪」不再单独弹通知：引擎常驻前台服务自己有一条
    // （`RuntimeForegroundService`），再来一条只是重复打扰。
    LaunchedEffect((engineState as? RuntimeState.Running)?.endpoint) {
        val endpoint = (engineState as? RuntimeState.Running)?.endpoint
        chatAlertViewModel.setEndpoint(endpoint)
        diagnosticsViewModel.setEndpoint(endpoint)
        accountViewModel.setEndpoint(endpoint)
        scheduledTasksViewModel.setEndpoint(endpoint)
        pluginMarketViewModel.setEndpoint(endpoint)
        engineSettingsViewModel.setEndpoint(endpoint)
        homeViewModel.setEndpoint(endpoint)
    }
    // Bot 浏览器的读页通道：桌面版是「窗口长轮询」，手机这边由我们自己的 WebView 应答。
    val browserEndpoint = (engineState as? RuntimeState.Running)?.endpoint
    LaunchedEffect(browserEndpoint) {
        val endpoint = browserEndpoint ?: return@LaunchedEffect
        BrowserReadBridge.loop(endpoint, BotBrowserStore.of(appContext))
    }
    LaunchedEffect(browserEndpoint, settingsUiState.phoneControlEnabled) {
        val endpoint = browserEndpoint ?: return@LaunchedEffect
        if (!settingsUiState.phoneControlEnabled) return@LaunchedEffect
        PhoneReadBridge.loop(endpoint, appContext)
    }
    val engineStatusText = when (engineState) {
        is RuntimeState.Running -> stringResource(R.string.engine_value_running)
        is RuntimeState.Installed -> stringResource(R.string.engine_value_installed)
        is RuntimeState.Starting -> stringResource(R.string.engine_value_starting)
        is RuntimeState.Installing -> stringResource(R.string.engine_value_installing)
        is RuntimeState.Failed -> stringResource(R.string.engine_value_failed)
        RuntimeState.Stopped -> stringResource(R.string.engine_value_stopped)
        RuntimeState.NotInstalled -> stringResource(R.string.engine_value_not_installed)
    }
    var openBotId by rememberSaveable { mutableStateOf<String?>(null) }
    var botDetailsOpen by rememberSaveable { mutableStateOf(false) }
    var newGroupOpen by rememberSaveable { mutableStateOf(false) }
    var newBotOpen by rememberSaveable { mutableStateOf(false) }
    var botBrowserOpen by rememberSaveable { mutableStateOf(false) }
    var botBrowserUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var handOverOpened by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var engineOpen by rememberSaveable { mutableStateOf(false) }
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    var chatFontOpen by rememberSaveable { mutableStateOf(false) }
    var languageOpen by rememberSaveable { mutableStateOf(false) }
    var accountOpen by rememberSaveable { mutableStateOf(false) }
    var diagnosticsOpen by rememberSaveable { mutableStateOf(false) }
    // 引擎页顶栏保留的小入口：用 WebView 打开本机 DSH Web。
    var webOpen by rememberSaveable { mutableStateOf(false) }
    // 设置子页用栈保存，返回时回到上一页而不是直接回设置首页。
    // 用不可变 List：mutableStateOf(ArrayList) 会触发 lint 的
    // MutableCollectionMutableState（状态里塞可变集合，外部改集合不会触发重组）。
    var settingsStack by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(settingsStack) {
        if (settingsStack.any { runCatching { SettingsPageId.valueOf(it) }.isFailure }) {
            settingsStack = emptyList()
        }
    }
    var navForward by remember { mutableStateOf(true) }
    // Bot / 群设置页里推出来的二级整屏页（记忆 / 定时任务 / 工作文件夹 / 说明）。
    var botSubPage by rememberSaveable { mutableStateOf<String?>(null) }
    // 退出设置页或换 Bot 时，把二级页清掉，免得下次进来还停在那。
    LaunchedEffect(openBotId, botDetailsOpen) {
        if (!botDetailsOpen || openBotId == null) botSubPage = null
    }

    // 点对话通知回到对应 Bot：通知栏把 botId 带进来，这里消费掉并打开聊天。
    // 注意 screenKey 的优先级里 web / 诊断 / 账号 / 新建 Bot 等页都排在对话前面，
    // 所以这些全屏页必须一并关掉，否则停在那些页面时点通知等于没点。
    LaunchedEffect(Unit) {
        while (true) {
            MainActivity.consumePendingOpenBot()?.let { id ->
                navForward = true
                newBotOpen = false
                newGroupOpen = false
                webOpen = false
                diagnosticsOpen = false
                engineOpen = false
                appearanceOpen = false
                chatFontOpen = false
                languageOpen = false
                accountOpen = false
                botBrowserOpen = false
                botDetailsOpen = false
                botSubPage = null
                settingsOpen = false
                searchOpen = false
                settingsStack = emptyList()
                openBotId = id
            }
            kotlinx.coroutines.delay(400)
        }
    }
    val systemAnimationsOff = runCatching {
        android.provider.Settings.Global.getFloat(
            appContext.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)
    // 「性能」档位按产品范围不做：系统关掉动画时跟着减动效。
    val reduceMotion = systemAnimationsOff
    // 已装运行时（含已停止）就不再走开屏引导：启动 App 直接进主界面。
    var onboardingDone by rememberSaveable { mutableStateOf(engineState !is RuntimeState.NotInstalled) }

    // 打开 App 自动拉起本机引擎（已安装/已停止时），不挡主界面。
    // 「引擎启动」里那个开关关掉后就不再自动拉起了。
    val autoStartEnabled = settingsUiState.dshGo.flag(DshGoPreferences.KEY_AUTO_START_LOCAL)
    // 自动启动开着、引擎还在拉起（或刚装好等待拉起）时，首页先出骨架，别显示「还没有内容」。
    val engineBooting = engineState is RuntimeState.Starting ||
        (engineState is RuntimeState.Installed && autoStartEnabled)
    LaunchedEffect(Unit) {
        if (autoStartEnabled &&
            (engineState is RuntimeState.Installed || engineState is RuntimeState.Stopped)
        ) {
            engineViewModel.start()
        }
    }

    // 顶栏只用纯文字提示启动过程：启动中从左滑入，成功后从右往左滑出消失。
    val startingText = stringResource(R.string.home_engine_starting)
    val successText = stringResource(R.string.home_engine_started)
    var engineNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(engineState) {
        when (engineState) {
            is RuntimeState.Starting -> engineNotice = startingText
            is RuntimeState.Running -> if (engineNotice == startingText) {
                engineNotice = successText
                kotlinx.coroutines.delay(1_800)
                engineNotice = null
            }
            else -> Unit
        }
    }

    val screenKey = when {
        !onboardingDone -> SCREEN_ONBOARDING
        newBotOpen -> SCREEN_NEW_BOT
        newGroupOpen -> SCREEN_NEW_GROUP
        webOpen -> SCREEN_WEB
        // 诊断页是引擎页推出来的：它要先判，否则 engineOpen 会把诊断页挡住。
        diagnosticsOpen -> SCREEN_DIAGNOSTICS
        engineOpen -> SCREEN_ENGINE
        appearanceOpen -> SCREEN_APPEARANCE
        chatFontOpen -> SCREEN_CHAT_FONT
        languageOpen -> SCREEN_LANGUAGE
        accountOpen -> SCREEN_ACCOUNT
        settingsStack.isNotEmpty() -> "$SCREEN_SETTINGS_PAGE:${settingsStack.last()}"
        botBrowserOpen -> SCREEN_BOT_BROWSER
        // 设置页里的二级整屏页：要排在设置页前面，否则会被设置页挡住。
        botDetailsOpen && openBotId != null && botSubPage != null ->
            "$BOT_PAGE_PREFIX${openBotId}:$botSubPage"
        botDetailsOpen && openBotId != null -> {
            val openBot = uiState.rows.firstOrNull { it.id == openBotId }
            if (openBot?.isGroup == true) ROOM_DETAILS_PREFIX + openBotId else BOT_DETAILS_PREFIX + openBotId
        }
        openBotId != null -> CHAT_PREFIX + openBotId
        settingsOpen -> SCREEN_SETTINGS
        searchOpen -> SCREEN_SEARCH
        else -> SCREEN_HOME
    }

    // 离开搜索页时立刻清焦点并收起输入法：不等 450ms 退场动画结束，
    // 否则键盘会拖到动画末尾才消失，看起来"关得慢"。
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(screenKey) {
        if (screenKey != SCREEN_SEARCH) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }

    // 系统返回手势/返回键：先在 App 内部逐层返回；到首页时和 dsh-Go 一样只把
    // 任务退到后台，不 finish Activity —— 热启动保留进程内状态、对话与事件订阅。
    val activity = appContext.findActivity()
    BackHandler(enabled = true) {
        navForward = false
        when {
            !onboardingDone -> Unit
            newBotOpen -> newBotOpen = false
            newGroupOpen -> newGroupOpen = false
            botBrowserOpen -> botBrowserOpen = false
            botSubPage != null -> botSubPage = null
            botDetailsOpen -> botDetailsOpen = false
            diagnosticsOpen -> {
                diagnosticsOpen = false
                engineOpen = true
            }
            webOpen -> webOpen = false
            engineOpen -> engineOpen = false
            appearanceOpen -> appearanceOpen = false
            chatFontOpen -> chatFontOpen = false
            languageOpen -> languageOpen = false
            // 账号是从模型页等子页打开的：先关账号，回到原来的子页。
            accountOpen -> accountOpen = false
            settingsStack.isNotEmpty() -> {
                settingsStack = settingsStack.dropLast(1)
                settingsOpen = true
            }
            openBotId != null -> openBotId = null
            settingsOpen -> settingsOpen = false
            searchOpen -> {
                searchOpen = false
                homeViewModel.setQuery("")
            }
            else -> if (onboardingDone) activity?.moveTaskToBack(true)
        }
    }

    val screenStateHolder = rememberSaveableStateHolder()
    // 动效档位（团队偏好 `prefs.motion`）：转场时长从这里取。
    // transitionSpec 不是 composable 上下文，所以要在外面取好。
    val motionProfile = Dshm.motion
    Box(Modifier.fillMaxSize()) {
    AnimatedContent(
        targetState = screenKey,
        modifier = Modifier
            .fillMaxSize()
            .background(Dshm.colors.pageBackground),
        transitionSpec = {
            val opening = navForward
            // 动效档位（团队偏好 `prefs.motion`）：`quiet` 直接不转场。
            // transitionSpec 不是 composable 上下文，所以档位在外面取。
            val duration = if (reduceMotion) 0 else motionProfile.screenTransitionMs
            val fadeDuration = if (reduceMotion) 0 else motionProfile.fadeMs
            val parallax: (Int) -> Int = if (reduceMotion) {
                { 0 }
            } else {
                { fullWidth -> (fullWidth * DshmMotion.outgoingParallaxFraction).toInt() }
            }
            if (opening) {
                // push：新页整屏从右滑入，旧页向左退一小段并淡出。
                (
                    slideInHorizontally(tween(duration, easing = DshmMotion.standard)) { it } +
                        fadeIn(tween(fadeDuration))
                    ).togetherWith(
                    slideOutHorizontally(tween(duration, easing = DshmMotion.exit)) { -parallax(it) } +
                        fadeOut(tween(fadeDuration)),
                )
            } else {
                // pop：当前页向右滑出，回退页从左侧那一点点位置滑回。
                (
                    slideInHorizontally(tween(duration, easing = DshmMotion.standard)) { -parallax(it) } +
                        fadeIn(tween(fadeDuration))
                    ).togetherWith(
                    slideOutHorizontally(tween(duration, easing = DshmMotion.standard)) { it } +
                        fadeOut(tween(fadeDuration)),
                )
            }
        },
        label = "screen",
    ) { key ->
        screenStateHolder.SaveableStateProvider(key) {
        when {
            key == SCREEN_ONBOARDING -> OnboardingScreen(
                state = engineState,
                onInstall = engineViewModel::install,
                onStart = engineViewModel::start,
                onDone = {
                    navForward = true
                    onboardingDone = true
                },
            )

            key == SCREEN_NEW_BOT -> NewBotScreen(
                endpoint = (engineState as? RuntimeState.Running)?.endpoint,
                onBack = {
                    navForward = false
                    newBotOpen = false
                },
                onCreated = { botId ->
                    navForward = true
                    newBotOpen = false
                    openBotId = botId
                    botDetailsOpen = true
                },
            )

            key == SCREEN_NEW_GROUP -> {
                val endpoint = (engineState as? RuntimeState.Running)?.endpoint
                NewGroupScreen(
                    bots = uiState.rows.filterNot { it.isGroup }.map { RoomMemberOption(it.id, it.name) },
                    onBack = {
                        navForward = false
                        newGroupOpen = false
                    },
                    onCreate = { name, admin, members ->
                        RoomSettingsViewModel.createRoom(endpoint, name, admin, members)
                    },
                    onCreated = { roomId ->
                        navForward = true
                        newGroupOpen = false
                        openBotId = roomId
                        botDetailsOpen = true
                    },
                )
            }

            key == SCREEN_BOT_BROWSER -> BotBrowserScreen(
                botId = openBotId,
                botName = uiState.rows.firstOrNull { it.id == openBotId }?.name,
                initialUrl = botBrowserUrl,
                onInitialUrlConsumed = { botBrowserUrl = null },
                onBack = {
                    navForward = false
                    botBrowserOpen = false
                    botBrowserUrl = null
                },
            )

            key.startsWith(BOT_PAGE_PREFIX) -> {
                val rest = key.removePrefix(BOT_PAGE_PREFIX)
                val botId = rest.substringBeforeLast(':')
                val page = rest.substringAfterLast(':')
                val openRow = uiState.rows.firstOrNull { it.id == botId }
                val back = {
                    navForward = false
                    botSubPage = null
                }
                if (openRow == null) {
                    // 找不到就退回设置页，别停在空白屏。
                    LaunchedEffect(Unit) { botSubPage = null }
                } else {
                    val endpoint = (engineState as? RuntimeState.Running)?.endpoint
                    when (page) {
                        BOT_PAGE_MEMORY -> BotMemoryPage(openRow, endpoint, onBack = back)
                        BOT_PAGE_SCHEDULES -> BotSchedulesPage(openRow, endpoint, onBack = back)
                        BOT_PAGE_FOLDER -> BotFolderPage(openRow, endpoint, onBack = back)
                        BOT_PAGE_NOTICE -> BotNoticePage(openRow, endpoint, onBack = back)
                        else -> LaunchedEffect(Unit) { botSubPage = null }
                    }
                }
            }

            key.startsWith(ROOM_DETAILS_PREFIX) -> {
                val roomId = key.removePrefix(ROOM_DETAILS_PREFIX)
                val openRoom = uiState.rows.firstOrNull { it.id == roomId }
                if (openRoom?.isGroup == true) {
                    RoomSettingsScreen(
                        row = openRoom,
                        endpoint = (engineState as? RuntimeState.Running)?.endpoint,
                        onBack = {
                            navForward = false
                            botDetailsOpen = false
                        },
                        onDeleted = {
                            navForward = false
                            botDetailsOpen = false
                            openBotId = null
                        },
                        onOpenMember = { memberId ->
                            navForward = true
                            openBotId = memberId
                            botDetailsOpen = false
                        },
                        onOpenPage = { page ->
                            navForward = true
                            botSubPage = page
                        },
                    )
                } else {
                    HomeScreen(
                        uiState = uiState,
                        onQueryChange = homeViewModel::setQuery,
                        onOpenBot = {
                            navForward = true
                            openBotId = it
                        },
                        onCreateBot = {
                            navForward = true
                            newBotOpen = true
                        },
                        onCreateGroup = { newGroupOpen = true },
                        onTogglePin = homeTogglePin,
                        onDelete = homeDelete,
                        onSetMain = homeSetMain,
                        onOpenSearch = {
                            navForward = true
                            searchOpen = true
                        },
                        onOpenSettings = {
                            navForward = true
                            settingsOpen = true
                        },
                        engineLabel = engineNotice,
                        engineBooting = engineBooting,
                        onEngineClick = {
                            navForward = true
                            engineOpen = true
                        },
                    )
                }
            }

            key.startsWith(BOT_DETAILS_PREFIX) -> {
                val botId = key.removePrefix(BOT_DETAILS_PREFIX)
                val openBot = uiState.rows.firstOrNull { it.id == botId }
                if (openBot?.isGroup == false) {
                    BotDetailsScreen(
                        bot = openBot,
                        endpoint = (engineState as? RuntimeState.Running)?.endpoint,
                        onBack = {
                            navForward = false
                            botDetailsOpen = false
                        },
                        onDeleted = {
                            navForward = false
                            botDetailsOpen = false
                            openBotId = null
                        },
                        onOpenPage = { page ->
                            navForward = true
                            botSubPage = page
                        },
                    )
                } else {
                    HomeScreen(
                        uiState = uiState,
                        onQueryChange = homeViewModel::setQuery,
                        onOpenBot = {
                            navForward = true
                            openBotId = it
                        },
                        onCreateBot = {
                            navForward = true
                            newBotOpen = true
                        },
                        onCreateGroup = { newGroupOpen = true },
                        onTogglePin = homeTogglePin,
                        onDelete = homeDelete,
                        onSetMain = homeSetMain,
                        onOpenSearch = {
                            navForward = true
                            searchOpen = true
                        },
                        onOpenSettings = {
                            navForward = true
                            settingsOpen = true
                        },
                        engineLabel = engineNotice,
                        engineBooting = engineBooting,
                        onEngineClick = {
                            navForward = true
                            engineOpen = true
                        },
                    )
                }
            }

            key.startsWith(CHAT_PREFIX) -> {
                val botId = key.removePrefix(CHAT_PREFIX)
                val openBot = uiState.rows.firstOrNull { it.id == botId }
                if (openBot != null) {
                    BotChatScreen(
                        bot = openBot,
                        endpoint = (engineState as? RuntimeState.Running)?.endpoint,
                        onBack = {
                            navForward = false
                            openBotId = null
                        },
                        onDetails = {
                            navForward = true
                            botDetailsOpen = true
                        },
                        onOpenBrowser = { url ->
                            botBrowserUrl = url
                            navForward = true
                            botBrowserOpen = true
                        },
                        onOpenMember = { memberId ->
                            navForward = true
                            openBotId = memberId
                            botDetailsOpen = false
                        },
                        fontScale = settingsUiState.chatFontSize.scale(),
                        voiceMode = settingsUiState.voiceMode,
                    )
                } else {
                    HomeScreen(
                        uiState = uiState,
                        onQueryChange = homeViewModel::setQuery,
                        onOpenBot = {
                            navForward = true
                            openBotId = it
                        },
                        onCreateBot = {
                            navForward = true
                            newBotOpen = true
                        },
                        onCreateGroup = { newGroupOpen = true },
                        onTogglePin = homeTogglePin,
                        onDelete = homeDelete,
                        onSetMain = homeSetMain,
                        onOpenSearch = {
                            navForward = true
                            searchOpen = true
                        },
                        onOpenSettings = {
                            navForward = true
                            settingsOpen = true
                        },
                        engineLabel = engineNotice,
                        engineBooting = engineBooting,
                        onEngineClick = {
                            navForward = true
                            engineOpen = true
                        },
                    )
                }
            }

            key == SCREEN_ENGINE -> EngineScreen(
                state = engineState,
                onBack = {
                    navForward = false
                    engineOpen = false
                },
                onInstall = engineViewModel::install,
                onStart = engineViewModel::start,
                onStop = engineViewModel::stop,
                onRestart = engineViewModel::restart,
                onReset = { keep -> engineViewModel.reset(keep) },
                onOpenWeb = {
                    navForward = true
                    webOpen = true
                },
                onOpenBackground = {
                    // 「后台运行」是设置里的一个子页，从引擎页进去后仍能原路返回。
                    navForward = true
                    engineOpen = false
                    settingsStack = settingsStack + SettingsPageId.BACKGROUND.name
                },
                onOpenDiagnostics = {
                    navForward = true
                    diagnosticsOpen = true
                },
                version = engineViewModel.version,
                nodeVersion = engineViewModel.nodeVersion,
                bundledVersion = engineViewModel.bundledVersion,
                installedVersion = engineViewModel.installedVersion,
                abi = engineViewModel.abi,
                autoStart = settingsUiState.dshGo.flag(DshGoPreferences.KEY_AUTO_START_LOCAL),
                onAutoStartChange = {
                    settingsViewModel.setDshGoPreference(DshGoPreferences.KEY_AUTO_START_LOCAL, it)
                },
                logText = engineViewModel::logText,
            )

            key == SCREEN_DIAGNOSTICS -> {
                // stringResource 只能在 Composable 作用域取，先算好再交给普通 lambda。
                val connectionLabel = if (engineState is RuntimeState.Running) {
                    stringResource(R.string.diagnostics_connected)
                } else {
                    stringResource(R.string.diagnostics_not_connected)
                }
                val diagnosticsUiState by diagnosticsViewModel.state.collectAsStateWithLifecycle()
                DiagnosticsScreen(
                    state = engineState,
                    version = engineViewModel.version,
                    nodeVersion = engineViewModel.nodeVersion,
                    bundledVersion = engineViewModel.bundledVersion,
                    installedVersion = engineViewModel.installedVersion,
                    abi = engineViewModel.abi,
                    hasApiKey = accountUi.hasApiKey,
                    report = {
                        engineViewModel.diagnosticsReport(
                            state = engineState,
                            hasApiKey = accountUi.hasApiKey,
                            connection = connectionLabel,
                        )
                    },
                    diagnostics = diagnosticsUiState,
                    actions = DiagnosticsActions(
                        openLog = diagnosticsViewModel::openLog,
                        closeLog = diagnosticsViewModel::closeLog,
                        retryLog = diagnosticsViewModel::reloadLog,
                        clear = diagnosticsViewModel::clear,
                    ),
                    onBack = {
                        navForward = false
                        diagnosticsOpen = false
                        engineOpen = true
                    },
                )
            }

            key.startsWith("$SCREEN_SETTINGS_PAGE:") -> {
                val page = runCatching {
                    SettingsPageId.valueOf(key.substringAfter(':'))
                }.getOrNull()
                val close = {
                    navForward = false
                    settingsStack = settingsStack.dropLast(1)
                    settingsOpen = true
                }
                when (page) {
                    SettingsPageId.NOTIFICATIONS -> NotificationSettingsScreen(
                        enabled = settingsUiState.notificationsEnabled,
                        onChange = settingsViewModel::setNotificationsEnabled,
                        onBack = close,
                    )

                    SettingsPageId.PERSONALIZATION -> PersonalizationScreen(
                        viewModel = botPrefsViewModel,
                        onBack = close,
                        onOpenAgentPresets = {
                            navForward = true
                            settingsStack = settingsStack + SettingsPageId.AGENT_PRESETS.name
                        },
                    )

                    SettingsPageId.AGENT_PRESETS -> AgentPresetsScreen(
                        viewModel = engineSettingsViewModel,
                        onBack = close,
                    )

                    SettingsPageId.PHONE_CONTROL -> PhoneControlSettingsScreen(
                        enabled = settingsUiState.phoneControlEnabled,
                        confirmMode = settingsUiState.phoneConfirmMode,
                        bots = uiState.rows
                            .filter { !it.isGroup }
                            .map { PhoneBotOption(it.id, it.name) },
                        ownerBotId = settingsUiState.phoneControlBotId,
                        onEnabledChange = { enabled ->
                            val wasRunning = engineState is RuntimeState.Running
                            settingsViewModel.setPhoneControlEnabled(enabled)
                            if (wasRunning) engineViewModel.restart()
                        },
                        onConfirmModeChange = settingsViewModel::setPhoneConfirmMode,
                        onOwnerChange = { botId ->
                            val active = engineState is RuntimeState.Running ||
                                engineState is RuntimeState.Starting
                            if (botId == null) settingsViewModel.setPhoneControlEnabled(false)
                            settingsViewModel.setPhoneControlBotId(botId)
                            if (active) engineViewModel.restart()
                        },
                        onBack = close,
                    )

                    SettingsPageId.VOICE -> VoiceSettingsScreen(
                        current = settingsUiState.voiceMode,
                        onSelect = settingsViewModel::setVoiceMode,
                        onBack = close,
                        viewModel = engineSettingsViewModel,
                    )

                    SettingsPageId.MODEL -> ModelProvidersScreen(
                        viewModel = engineSettingsViewModel,
                        onBack = close,
                        onOpenAccount = {
                            navForward = true
                            accountOpen = true
                        },
                        dshVersion = engineViewModel.version,
                        accountSignedIn = accountUi.name != null,
                        reduceMotion = reduceMotion,
                    )

                    SettingsPageId.PERMISSION -> PermissionScreen(
                        viewModel = engineSettingsViewModel,
                        settings = settingsViewModel,
                        onOpenPhoneControl = {
                            navForward = true
                            settingsStack = settingsStack + SettingsPageId.PHONE_CONTROL.name
                        },
                        onRestartEngine = engineViewModel::restart,
                        onBack = close,
                    )

                    SettingsPageId.WEB_SEARCH -> WebSearchScreen(
                        viewModel = engineSettingsViewModel,
                        onBack = close,
                    )

                    SettingsPageId.PLUGINS -> PluginsScreen(
                        viewModel = engineSettingsViewModel,
                        onBack = close,
                        onRestartEngine = engineViewModel::restart,
                        marketViewModel = pluginMarketViewModel,
                    )

                    SettingsPageId.ENGINE_PARAMS -> EngineSettingsScreen(
                        viewModel = engineSettingsViewModel,
                        onBack = close,
                        onOpenPlugins = {
                            navForward = true
                            settingsStack = settingsStack + SettingsPageId.PLUGINS.name
                        },
                        onOpenProviders = {
                            navForward = true
                            settingsStack = settingsStack + SettingsPageId.MODEL.name
                        },
                    )

                    SettingsPageId.SCHEDULES -> ScheduledTasksScreen(
                        viewModel = scheduledTasksViewModel,
                        onBack = close,
                    )

                    SettingsPageId.SECRETS -> SecretsScreen(
                        endpoint = (engineState as? RuntimeState.Running)?.endpoint,
                        onBack = close,
                    )

                    SettingsPageId.CONNECTORS -> ConnectorsScreen(
                        endpoint = (engineState as? RuntimeState.Running)?.endpoint,
                        onBack = close,
                    )

                    SettingsPageId.CAPABILITIES -> CapabilitiesScreen(
                        endpoint = (engineState as? RuntimeState.Running)?.endpoint,
                        onBack = close,
                    )

                    SettingsPageId.USAGE -> UsageScreen(
                        endpoint = (engineState as? RuntimeState.Running)?.endpoint,
                        onBack = close,
                    )

                    SettingsPageId.BACKGROUND -> BackgroundSettingsScreen(onBack = close)

                    SettingsPageId.PRIVACY -> PrivacySettingsScreen(
                        engineSettings = engineSettingsViewModel,
                        onBack = close,
                    )

                    SettingsPageId.LICENSES -> LicensesScreen(onBack = close)

                    SettingsPageId.APP_VERSION -> AppVersionScreen(onBack = close)

                    SettingsPageId.DSH_VERSION -> DshVersionScreen(
                        dshVersion = engineViewModel.version,
                        onBack = close,
                    )

                    null -> Unit
                }
            }

            key == SCREEN_WEB -> WebHostScreen(endpoint = (engineState as? RuntimeState.Running)?.endpoint)

            key == SCREEN_SETTINGS -> SettingsScreen(
                uiState = settingsUiState,
                engineSettings = engineSettingsViewModel,
                botPrefs = botPrefsViewModel,
                onBack = {
                    navForward = false
                    settingsOpen = false
                },
                onOpenEngine = {
                    navForward = true
                    engineOpen = true
                },
                onThemeChange = settingsViewModel::setThemeMode,
                onOpenChatFont = {
                    navForward = true
                    chatFontOpen = true
                },
                onLanguageChange = settingsViewModel::setLanguage,
                onOpenPage = {
                    navForward = true
                    settingsStack = settingsStack + it.name
                },
                onOpenAccount = {
                    navForward = true
                    accountOpen = true
                },
                accountName = accountUi.name,
                accountBalance = accountUi.balance,
                accountAvatarUrl = accountUi.avatarUrl,
                chatFontLabel = chatFontLabel(settingsUiState.chatFontSize),
                languageLabel = languageLabel(settingsUiState.language),
                engineStatus = engineStatusText,
                dshVersion = engineViewModel.version,
                reduceMotion = reduceMotion,
            )

            key == SCREEN_APPEARANCE -> AppearanceScreen(
                themeMode = settingsUiState.themeMode,
                onThemeChange = settingsViewModel::setThemeMode,
                onBack = {
                    navForward = false
                    appearanceOpen = false
                },
            )

            key == SCREEN_CHAT_FONT -> ChatFontScreen(
                current = settingsUiState.chatFontSize,
                onSelect = settingsViewModel::setChatFontSize,
                onBack = {
                    navForward = false
                    chatFontOpen = false
                },
            )

            key == SCREEN_LANGUAGE -> LanguageScreen(
                current = settingsUiState.language,
                onSelect = settingsViewModel::setLanguage,
                onBack = {
                    navForward = false
                    languageOpen = false
                },
            )

            key == SCREEN_ACCOUNT -> AccountScreen(
                viewModel = accountViewModel,
                onBack = {
                    navForward = false
                    accountOpen = false
                },
            )

            key == SCREEN_SEARCH -> SearchScreen(
                uiState = uiState,
                onQueryChange = homeViewModel::setQuery,
                onOpenBot = {
                    navForward = true
                    openBotId = it
                },
                onBack = {
                    navForward = false
                    searchOpen = false
                    homeViewModel.setQuery("")
                },
            )

            else -> HomeScreen(
                uiState = uiState,
                onQueryChange = homeViewModel::setQuery,
                onOpenBot = {
                    navForward = true
                    openBotId = it
                },
                onCreateBot = {
                    navForward = true
                    newBotOpen = true
                },
                onCreateGroup = { newGroupOpen = true },
                onTogglePin = homeTogglePin,
                onDelete = homeDelete,
                        onSetMain = homeSetMain,
                onOpenSearch = {
                    navForward = true
                    searchOpen = true
                },
                onOpenSettings = {
                    navForward = true
                    settingsOpen = true
                },
                engineLabel = engineNotice,
                engineBooting = engineBooting,
                        onEngineClick = {
                            navForward = true
                            engineOpen = true
                        },
            )
        }
        }
    }

    // 浏览器守卫：Bot 要点「付款 / 发送 / 删除」这类按钮时，先在这里问一次。
    val guardRequest by BrowserGuardStore.pending.collectAsStateWithLifecycle()
    guardRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { BrowserGuardStore.answer(false) },
            title = { Text(stringResource(R.string.browser_guard_title)) },
            text = { Text(request.reason) },
            confirmButton = {
                TextButton(onClick = { BrowserGuardStore.answer(true) }) {
                    Text(stringResource(R.string.browser_guard_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = { BrowserGuardStore.answer(false) }) {
                    Text(stringResource(R.string.browser_guard_deny))
                }
            },
        )
    }

    // 交接给用户：把那个 Bot 的浏览器推到前台（登录 / 验证码 / 付款）。
    val handOver by BrowserHandOverStore.pending.collectAsStateWithLifecycle()
    LaunchedEffect(handOver) {
        val request = handOver
        if (request != null) {
            handOverOpened = true
            openBotId = request.first
            botBrowserOpen = true
            navForward = true
        } else if (handOverOpened) {
            handOverOpened = false
            botBrowserOpen = false
            navForward = false
        }
    }

    // 冷启动的启动画面：图标落定 + 字标 + 一道细光，引擎就绪或动画播完就淡出。
    LaunchOverlay(
        engineReady = engineState is RuntimeState.Running,
        reduceMotion = reduceMotion,
    )

    }
}









































