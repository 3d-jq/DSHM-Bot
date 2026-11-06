package com.dshm.bot

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
import com.dshm.bot.data.notify.EngineNotifier
import com.dshm.bot.data.runtime.RuntimeState
import com.dshm.bot.ui.account.AccountScreen
import com.dshm.bot.ui.account.AccountViewModel
import com.dshm.bot.ui.chat.BotBrowserScreen
import com.dshm.bot.ui.chat.BotChatScreen
import com.dshm.bot.ui.chat.BotDetailsScreen
import com.dshm.bot.ui.engine.DiagnosticsScreen
import com.dshm.bot.ui.engine.EngineScreen
import com.dshm.bot.ui.engine.EngineViewModel
import com.dshm.bot.ui.engine.OnboardingScreen
import com.dshm.bot.ui.home.HomeScreen
import com.dshm.bot.ui.home.HomeViewModel
import com.dshm.bot.ui.home.SearchScreen
import com.dshm.bot.ui.settings.AboutScreen
import com.dshm.bot.ui.settings.AgentPresetsScreen
import com.dshm.bot.ui.settings.AppearanceScreen
import com.dshm.bot.ui.settings.BackgroundSettingsScreen
import com.dshm.bot.ui.settings.PluginMarketScreen
import com.dshm.bot.ui.settings.PluginMarketViewModel
import com.dshm.bot.ui.settings.ScheduledTasksScreen
import com.dshm.bot.ui.settings.ScheduledTasksViewModel
import com.dshm.bot.ui.settings.ChatFontScreen
import com.dshm.bot.ui.settings.EngineSettingsScreen
import com.dshm.bot.ui.settings.EngineSettingsViewModel
import com.dshm.bot.ui.settings.InterfaceSettingsScreen
import com.dshm.bot.ui.settings.LanguageScreen
import com.dshm.bot.ui.settings.LicensesScreen
import com.dshm.bot.ui.settings.ModelProvidersScreen
import com.dshm.bot.ui.settings.NotificationSettingsScreen
import com.dshm.bot.ui.settings.PermissionScreen
import com.dshm.bot.ui.settings.PersonalizationScreen
import com.dshm.bot.ui.settings.PhoneControlSettingsScreen
import com.dshm.bot.ui.settings.PluginsScreen
import com.dshm.bot.ui.settings.PrivacySettingsScreen
import com.dshm.bot.ui.settings.SettingsPageId
import com.dshm.bot.ui.settings.SettingsScreen
import com.dshm.bot.ui.settings.SettingsViewModel
import com.dshm.bot.ui.settings.VoiceSettingsScreen
import com.dshm.bot.ui.settings.WebHostScreen
import com.dshm.bot.ui.settings.WebSearchScreen
import com.dshm.bot.ui.settings.chatFontLabel
import com.dshm.bot.ui.settings.languageLabel
import com.dshm.bot.domain.model.DshGoPreferences
import com.dshm.bot.domain.model.InterfaceMode
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
private const val SCREEN_WEB = "web"
private const val SCREEN_DIAGNOSTICS = "diagnostics"
private const val CHAT_PREFIX = "chat:"
private const val BOT_DETAILS_PREFIX = "bot-details:"
private const val SCREEN_BOT_BROWSER = "bot-browser"

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
    val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val engineState by engineViewModel.state.collectAsStateWithLifecycle()
    val accountUi by accountViewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(engineState) {
        if (engineState is RuntimeState.Running && settingsUiState.notificationsEnabled) {
            EngineNotifier.notifyReady(appContext)
        }
    }
    LaunchedEffect((engineState as? RuntimeState.Running)?.endpoint) {
        val endpoint = (engineState as? RuntimeState.Running)?.endpoint
        accountViewModel.setEndpoint(endpoint)
        scheduledTasksViewModel.setEndpoint(endpoint)
        pluginMarketViewModel.setEndpoint(endpoint)
        engineSettingsViewModel.setEndpoint(endpoint)
        homeViewModel.setEndpoint(endpoint)
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
    var botBrowserOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var engineOpen by rememberSaveable { mutableStateOf(false) }
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    var chatFontOpen by rememberSaveable { mutableStateOf(false) }
    var languageOpen by rememberSaveable { mutableStateOf(false) }
    var accountOpen by rememberSaveable { mutableStateOf(false) }
    var diagnosticsOpen by rememberSaveable { mutableStateOf(false) }
    // 从本机引擎页直接打开网页版（不用先切「界面模式」）。
    var webOpen by rememberSaveable { mutableStateOf(false) }
    // 设置子页用栈保存，返回时回到上一页而不是直接回设置首页。
    var settingsStack by rememberSaveable { mutableStateOf(ArrayList<String>()) }
    LaunchedEffect(settingsStack) {
        if (settingsStack.any { runCatching { SettingsPageId.valueOf(it) }.isFailure }) {
            settingsStack = ArrayList()
        }
    }
    var navForward by remember { mutableStateOf(true) }
    val webMode = settingsUiState.interfaceMode == InterfaceMode.WEB && engineState is RuntimeState.Running
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
    LaunchedEffect(Unit) {
        val autoStart = settingsUiState.dshGo.flag(DshGoPreferences.KEY_AUTO_START_LOCAL)
        if (autoStart &&
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
        webMode && !settingsOpen && openBotId == null && !searchOpen -> SCREEN_WEB
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
        botDetailsOpen && openBotId != null -> BOT_DETAILS_PREFIX + openBotId
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

    // 系统返回手势/返回键：先在 App 内部逐层返回，最后才交给系统退出。
    // 开屏引导期间拦截返回：必须完成安装+启动才能进入。
    BackHandler(enabled = openBotId != null || botDetailsOpen || botBrowserOpen || searchOpen || settingsOpen || engineOpen || appearanceOpen || chatFontOpen || languageOpen || accountOpen || diagnosticsOpen || settingsStack.isNotEmpty() || webMode || webOpen || !onboardingDone) {
        navForward = false
        when {
            !onboardingDone -> Unit
            botBrowserOpen -> botBrowserOpen = false
            botDetailsOpen -> botDetailsOpen = false
            diagnosticsOpen -> {
                diagnosticsOpen = false
                engineOpen = true
            }
            engineOpen -> engineOpen = false
            appearanceOpen -> appearanceOpen = false
            chatFontOpen -> chatFontOpen = false
            languageOpen -> languageOpen = false
            // 账号是从模型页等子页打开的：先关账号，回到原来的子页。
            accountOpen -> accountOpen = false
            settingsStack.isNotEmpty() -> {
                settingsStack = ArrayList(settingsStack).apply { removeAt(lastIndex) }
                settingsOpen = true
            }
            openBotId != null -> openBotId = null
            settingsOpen -> settingsOpen = false
            searchOpen -> {
                searchOpen = false
                homeViewModel.setQuery("")
            }
            // 网页模式是主界面本身：内部页面都关掉后才退出网页模式。
            webOpen -> webOpen = false
            webMode -> settingsViewModel.setInterfaceMode(InterfaceMode.NATIVE)
        }
    }

    val screenStateHolder = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize()) {
    AnimatedContent(
        targetState = screenKey,
        modifier = Modifier
            .fillMaxSize()
            .background(Dshm.colors.pageBackground),
        transitionSpec = {
            val opening = navForward
            val duration = if (reduceMotion) 0 else DshmMotion.screenTransitionMs
            val fadeDuration = if (reduceMotion) 0 else DshmMotion.fadeMs
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

            key == SCREEN_BOT_BROWSER -> BotBrowserScreen(
                endpoint = (engineState as? RuntimeState.Running)?.endpoint,
                onBack = {
                    navForward = false
                    botBrowserOpen = false
                },
            )

            key.startsWith(BOT_DETAILS_PREFIX) -> {
                val botId = key.removePrefix(BOT_DETAILS_PREFIX)
                val openBot = uiState.rows.firstOrNull { it.id == botId }
                if (openBot != null) {
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
                    )
                } else {
                    HomeScreen(
                        uiState = uiState,
                        onQueryChange = homeViewModel::setQuery,
                        onOpenBot = {
                            navForward = true
                            openBotId = it
                        },
                        onCreateBot = {},
                        onCreateGroup = {},
                        onOpenSearch = {
                            navForward = true
                            searchOpen = true
                        },
                        onOpenSettings = {
                            navForward = true
                            settingsOpen = true
                        },
                        engineLabel = engineNotice,
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
                        onOpenBrowser = {
                            navForward = true
                            botBrowserOpen = true
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
                        onCreateBot = {},
                        onCreateGroup = {},
                        onOpenSearch = {
                            navForward = true
                            searchOpen = true
                        },
                        onOpenSettings = {
                            navForward = true
                            settingsOpen = true
                        },
                        engineLabel = engineNotice,
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
                },                onOpenBackground = {
                    // 「后台运行」是设置里的一个子页，从引擎页进去后仍能原路返回。
                    navForward = true
                    engineOpen = false
                    settingsStack = ArrayList(settingsStack).apply { add(SettingsPageId.BACKGROUND.name) }
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
                    settingsStack = ArrayList(settingsStack).apply {
                        if (isNotEmpty()) removeAt(lastIndex)
                    }
                    settingsOpen = true
                }
                when (page) {
                    SettingsPageId.INTERFACE -> InterfaceSettingsScreen(
                        current = settingsUiState.interfaceMode,
                        onSelect = settingsViewModel::setInterfaceMode,
                        engineSettings = engineSettingsViewModel,
                        onBack = close,
                    )

                    SettingsPageId.NOTIFICATIONS -> NotificationSettingsScreen(
                        enabled = settingsUiState.notificationsEnabled,
                        onChange = settingsViewModel::setNotificationsEnabled,
                        onBack = close,
                    )

                    SettingsPageId.PERSONALIZATION -> PersonalizationScreen(
                        onBack = close,
                        onOpenAgentPresets = {
                            navForward = true
                            settingsStack = ArrayList(settingsStack).apply {
                                add(SettingsPageId.AGENT_PRESETS.name)
                            }
                        },
                    )

                    SettingsPageId.AGENT_PRESETS -> AgentPresetsScreen(
                        viewModel = engineSettingsViewModel,
                        onBack = close,
                    )

                    SettingsPageId.PHONE_CONTROL -> PhoneControlSettingsScreen(
                        enabled = settingsUiState.phoneControlEnabled,
                        confirmMode = settingsUiState.phoneConfirmMode,
                        onEnabledChange = settingsViewModel::setPhoneControlEnabled,
                        onConfirmModeChange = settingsViewModel::setPhoneConfirmMode,
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
                        onOpenMarket = {
                            navForward = true
                            settingsStack = ArrayList(settingsStack).apply {
                                add(SettingsPageId.PLUGIN_MARKET.name)
                            }
                        },
                    )

                    SettingsPageId.ENGINE_PARAMS -> EngineSettingsScreen(
                        viewModel = engineSettingsViewModel,
                        onBack = close,
                        onOpenPlugins = {
                            navForward = true
                            settingsStack = ArrayList(settingsStack).apply {
                                add(SettingsPageId.PLUGINS.name)
                            }
                        },
                        onOpenProviders = {
                            navForward = true
                            settingsStack = ArrayList(settingsStack).apply {
                                add(SettingsPageId.MODEL.name)
                            }
                        },
                    )

                    SettingsPageId.PLUGIN_MARKET -> PluginMarketScreen(
                        viewModel = pluginMarketViewModel,
                        onBack = close,
                    )

                    SettingsPageId.SCHEDULES -> ScheduledTasksScreen(
                        viewModel = scheduledTasksViewModel,
                        onBack = close,
                    )

                    SettingsPageId.BACKGROUND -> BackgroundSettingsScreen(onBack = close)

                    SettingsPageId.PRIVACY -> PrivacySettingsScreen(
                        engineSettings = engineSettingsViewModel,
                        onBack = close,
                    )

                    SettingsPageId.LICENSES -> LicensesScreen(onBack = close)

                    SettingsPageId.ABOUT -> AboutScreen(
                        dshVersion = engineViewModel.version,
                        engineStatus = engineStatusText,
                        onOpenLicenses = {
                            navForward = true
                            settingsStack = ArrayList(settingsStack).apply {
                                add(SettingsPageId.LICENSES.name)
                            }
                        },
                        onBack = close,
                    )

                    null -> Unit
                }
            }

            key == SCREEN_WEB -> WebHostScreen(endpoint = (engineState as? RuntimeState.Running)?.endpoint)

            key == SCREEN_SETTINGS -> SettingsScreen(
                uiState = settingsUiState,
                engineSettings = engineSettingsViewModel,
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
                    settingsStack = ArrayList(settingsStack).apply { add(it.name) }
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
                onCreateBot = {},
                onCreateGroup = {},
                onOpenSearch = {
                    navForward = true
                    searchOpen = true
                },
                onOpenSettings = {
                    navForward = true
                    settingsOpen = true
                },
                        engineLabel = engineNotice,
                        onEngineClick = {
                            navForward = true
                            engineOpen = true
                        },
            )
        }
        }
    }

    }
}









































