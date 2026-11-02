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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.core.design.DshmMotion
import com.dshm.bot.ui.chat.BotChatPlaceholderScreen
import com.dshm.bot.ui.home.HomeScreen
import com.dshm.bot.ui.home.HomeViewModel
import com.dshm.bot.ui.home.SearchScreen
import com.dshm.bot.ui.settings.SettingsScreen
import com.dshm.bot.ui.settings.SettingsViewModel

private const val SCREEN_HOME = "home"
private const val SCREEN_SEARCH = "search"
private const val SCREEN_SETTINGS = "settings"
private const val CHAT_PREFIX = "chat:"

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
) {
    val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    var openBotId by rememberSaveable { mutableStateOf<String?>(null) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    val screenKey = when {
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
    BackHandler(enabled = openBotId != null || searchOpen || settingsOpen) {
        when {
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
            val opening = targetState != SCREEN_HOME &&
                (initialState == SCREEN_HOME || targetState.startsWith(CHAT_PREFIX))
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

            key == SCREEN_SETTINGS -> SettingsScreen(
                uiState = settingsUiState,
                onBack = { settingsOpen = false },
                onThemeChange = settingsViewModel::setThemeMode,
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
