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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.dshm.bot.ui.chat.BotChatPlaceholderScreen
import com.dshm.bot.ui.engine.EngineScreen
import com.dshm.bot.ui.engine.EngineViewModel
import com.dshm.bot.ui.engine.OnboardingScreen
import com.dshm.bot.ui.home.HomeScreen
import com.dshm.bot.ui.home.HomeViewModel
import com.dshm.bot.ui.home.SearchScreen
import com.dshm.bot.ui.settings.AppearanceScreen
import com.dshm.bot.ui.settings.ChatFontScreen
import com.dshm.bot.ui.settings.chatFontLabel
import com.dshm.bot.ui.settings.languageLabel
import com.dshm.bot.ui.settings.LanguageScreen
import com.dshm.bot.ui.settings.SettingsScreen
import com.dshm.bot.ui.settings.SettingsViewModel

private const val SCREEN_HOME = "home"
private const val SCREEN_SEARCH = "search"
private const val SCREEN_SETTINGS = "settings"
private const val SCREEN_ENGINE = "engine"
private const val SCREEN_ONBOARDING = "onboarding"
private const val SCREEN_APPEARANCE = "appearance"
private const val SCREEN_CHAT_FONT = "chat-font"
private const val SCREEN_LANGUAGE = "language"
private const val SCREEN_ACCOUNT = "account"
private const val CHAT_PREFIX = "chat:"

private fun depthOf(key: String): Int = when {
    key == SCREEN_HOME || key == SCREEN_ONBOARDING -> 0
    key == SCREEN_SEARCH || key == SCREEN_SETTINGS -> 1
    key == SCREEN_ENGINE || key == SCREEN_APPEARANCE || key == SCREEN_CHAT_FONT || key == SCREEN_LANGUAGE || key == SCREEN_ACCOUNT || key.startsWith(CHAT_PREFIX) -> 2
    else -> 1
}

/**
 * 应用外壳：主界面 ↔ 搜索 ↔ 设置 ↔ Bot 对话。
 * 路由状态放在这里，页面本身不感知导航；所有页面切换都用 iOS 风格整屏 push / pop 动效。
 */
@Composable
fun DshmBotApp(
    homeViewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory()),
    settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(LocalContext.current.applicationContext),
    ),
    engineViewModel: EngineViewModel = viewModel(
        factory = EngineViewModel.factory(LocalContext.current.applicationContext),
    ),
) {
    val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val engineState by engineViewModel.state.collectAsStateWithLifecycle()
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
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var engineOpen by rememberSaveable { mutableStateOf(false) }
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    var chatFontOpen by rememberSaveable { mutableStateOf(false) }
    var languageOpen by rememberSaveable { mutableStateOf(false) }
    var accountOpen by rememberSaveable { mutableStateOf(false) }
    // 已装运行时（含已停止）就不再走开屏引导：启动 App 直接进主界面。
    var onboardingDone by rememberSaveable { mutableStateOf(engineState !is RuntimeState.NotInstalled) }

    // 打开 App 自动拉起本机引擎（已安装/已停止时），不挡主界面。
    LaunchedEffect(Unit) {
        if (engineState is RuntimeState.Installed || engineState is RuntimeState.Stopped) {
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
        engineOpen -> SCREEN_ENGINE
        appearanceOpen -> SCREEN_APPEARANCE
        chatFontOpen -> SCREEN_CHAT_FONT
        languageOpen -> SCREEN_LANGUAGE
        accountOpen -> SCREEN_ACCOUNT
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
    BackHandler(enabled = openBotId != null || searchOpen || settingsOpen || engineOpen || appearanceOpen || chatFontOpen || languageOpen || accountOpen || !onboardingDone) {
        when {
            !onboardingDone -> Unit
            engineOpen -> engineOpen = false
            appearanceOpen -> appearanceOpen = false
            chatFontOpen -> chatFontOpen = false
            languageOpen -> languageOpen = false
            accountOpen -> accountOpen = false
            openBotId != null -> openBotId = null
            settingsOpen -> settingsOpen = false
            else -> {
                searchOpen = false
                homeViewModel.setQuery("")
            }
        }
    }

    AnimatedContent(
        targetState = screenKey,
        modifier = Modifier
            .fillMaxSize()
            .background(Dshm.colors.background),
        transitionSpec = {
            val opening = depthOf(targetState) >= depthOf(initialState) && targetState != SCREEN_HOME
            val duration = DshmMotion.screenTransitionMs
            val parallax: (Int) -> Int = { fullWidth ->
                (fullWidth * DshmMotion.outgoingParallaxFraction).toInt()
            }
            if (opening) {
                // push：新页整屏从右滑入，旧页向左退一小段并淡出。
                (
                    slideInHorizontally(tween(duration, easing = DshmMotion.standard)) { it } +
                        fadeIn(tween(DshmMotion.fadeMs))
                    ).togetherWith(
                    slideOutHorizontally(tween(duration, easing = DshmMotion.exit)) { -parallax(it) } +
                        fadeOut(tween(duration)),
                )
            } else {
                // pop：当前页向右滑出，回退页从左侧那一点点位置滑回。
                (
                    slideInHorizontally(tween(duration, easing = DshmMotion.standard)) { -parallax(it) } +
                        fadeIn(tween(duration))
                    ).togetherWith(
                    slideOutHorizontally(tween(duration, easing = DshmMotion.standard)) { it } +
                        fadeOut(tween(DshmMotion.fadeMs)),
                )
            }
        },
        label = "screen",
    ) { key ->
        when {
            key == SCREEN_ONBOARDING -> OnboardingScreen(
                state = engineState,
                onInstall = engineViewModel::install,
                onStart = engineViewModel::start,
                onDone = { onboardingDone = true },
            )

            key.startsWith(CHAT_PREFIX) -> {
                val botId = key.removePrefix(CHAT_PREFIX)
                val openBot = uiState.rows.firstOrNull { it.id == botId }
                if (openBot != null) {
                    BotChatPlaceholderScreen(bot = openBot, onBack = { openBotId = null })
                } else {
                    HomeScreen(
                        uiState = uiState,
                        onQueryChange = homeViewModel::setQuery,
                        onOpenBot = { openBotId = it },
                        onCreateBot = {},
                        onCreateGroup = {},
                        onOpenSearch = { searchOpen = true },
                        onOpenSettings = { settingsOpen = true },
                        engineLabel = engineNotice,
                        onEngineClick = { engineOpen = true },
                    )
                }
            }

            key == SCREEN_ENGINE -> EngineScreen(
                state = engineState,
                onBack = { engineOpen = false },
                onInstall = engineViewModel::install,
                onStart = engineViewModel::start,
                onStop = engineViewModel::stop,
                onRestart = engineViewModel::restart,
                version = engineViewModel.version,
                nodeVersion = engineViewModel.nodeVersion,
                abi = engineViewModel.abi,
                logText = engineViewModel::logText,
                onReset = engineViewModel::reset,
            )

            key == SCREEN_SETTINGS -> SettingsScreen(
                uiState = settingsUiState,
                onBack = { settingsOpen = false },
                onThemeChange = settingsViewModel::setThemeMode,
                onOpenEngine = { engineOpen = true },
                onOpenAppearance = { appearanceOpen = true },
                onOpenChatFont = { chatFontOpen = true },
                onOpenLanguage = { languageOpen = true },
                onOpenAccount = { accountOpen = true },
                chatFontLabel = chatFontLabel(settingsUiState.chatFontSize),
                languageLabel = languageLabel(settingsUiState.language),
                engineStatus = engineStatusText,
            )

            key == SCREEN_APPEARANCE -> AppearanceScreen(
                themeMode = settingsUiState.themeMode,
                onThemeChange = settingsViewModel::setThemeMode,
                onBack = { appearanceOpen = false },
            )

            key == SCREEN_CHAT_FONT -> ChatFontScreen(
                current = settingsUiState.chatFontSize,
                onSelect = settingsViewModel::setChatFontSize,
                onBack = { chatFontOpen = false },
            )

            key == SCREEN_LANGUAGE -> LanguageScreen(
                current = settingsUiState.language,
                onSelect = settingsViewModel::setLanguage,
                onBack = { languageOpen = false },
            )

            key == SCREEN_ACCOUNT -> AccountScreen(
                loggedInName = null,
                balanceText = null,
                onBack = { accountOpen = false },
            )

            key == SCREEN_SEARCH -> SearchScreen(
                uiState = uiState,
                onQueryChange = homeViewModel::setQuery,
                onOpenBot = { openBotId = it },
                onBack = {
                    searchOpen = false
                    homeViewModel.setQuery("")
                },
            )

            else -> HomeScreen(
                uiState = uiState,
                onQueryChange = homeViewModel::setQuery,
                onOpenBot = { openBotId = it },
                onCreateBot = {},
                onCreateGroup = {},
                onOpenSearch = { searchOpen = true },
                onOpenSettings = { settingsOpen = true },
                        engineLabel = engineNotice,
                        onEngineClick = { engineOpen = true },
            )
        }
    }
}






















