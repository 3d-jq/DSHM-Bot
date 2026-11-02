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
import com.dshm.bot.ui.chat.BotChatPlaceholderScreen
import com.dshm.bot.ui.engine.EngineScreen
import com.dshm.bot.ui.engine.EngineViewModel
import com.dshm.bot.ui.home.HomeScreen
import com.dshm.bot.ui.home.HomeViewModel
import com.dshm.bot.ui.home.SearchScreen
import com.dshm.bot.ui.settings.SettingsScreen
import com.dshm.bot.ui.settings.SettingsViewModel

private const val SCREEN_HOME = "home"
private const val SCREEN_SEARCH = "search"
private const val SCREEN_SETTINGS = "settings"
private const val SCREEN_ENGINE = "engine"
private const val CHAT_PREFIX = "chat:"

private fun depthOf(key: String): Int = when {
    key == SCREEN_HOME -> 0
    key == SCREEN_SEARCH || key == SCREEN_SETTINGS -> 1
    key == SCREEN_ENGINE || key.startsWith(CHAT_PREFIX) -> 2
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
    var onboardingDone by rememberSaveable { mutableStateOf(false) }

    val screenKey = when {
        (!onboardingDone && !engineOpen) || engineOpen -> SCREEN_ENGINE
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
    BackHandler(enabled = openBotId != null || searchOpen || settingsOpen || engineOpen || !onboardingDone) {
        when {
            engineOpen -> engineOpen = false
            openBotId != null -> openBotId = null
            settingsOpen -> settingsOpen = false
            !onboardingDone -> Unit
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
                    )
                }
            }

            key == SCREEN_ENGINE -> EngineScreen(
                state = engineState,
                onBack = if (onboardingDone) ({ engineOpen = false }) else null,
                showContinue = !onboardingDone,
                onDone = {
                    onboardingDone = true
                    engineOpen = false
                },
                onInstall = engineViewModel::install,
                onStart = engineViewModel::start,
                onStop = engineViewModel::stop,
            )

            key == SCREEN_SETTINGS -> SettingsScreen(
                uiState = settingsUiState,
                onBack = { settingsOpen = false },
                onThemeChange = settingsViewModel::setThemeMode,
                onOpenEngine = { engineOpen = true },
                engineStatus = engineStatusText,
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
            )
        }
    }
}










