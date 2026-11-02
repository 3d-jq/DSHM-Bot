package com.dshm.bot

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dshm.bot.core.design.DshmMotion
import com.dshm.bot.ui.chat.BotChatPlaceholderScreen
import com.dshm.bot.ui.home.HomeScreen
import com.dshm.bot.ui.home.HomeViewModel

/**
 * 应用外壳：主界面 ↔ Bot 对话。
 * 路由状态放在这里，页面本身不感知导航；切换用 iOS 风格的整屏 push / pop 动效。
 */
@Composable
fun DshmBotApp(
    homeViewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory()),
) {
    val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    var openBotId by rememberSaveable { mutableStateOf<String?>(null) }

    // 系统返回手势/返回键：在对话页时先回到主界面，而不是直接退出 App。
    BackHandler(enabled = openBotId != null) {
        openBotId = null
    }

    AnimatedContent(
        targetState = openBotId,
        transitionSpec = {
            val opening = targetState != null
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
                // pop：聊天页向右滑出，主界面从左侧那一点点位置滑回。
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
    ) { botId ->
        val openBot = uiState.rows.firstOrNull { it.id == botId }
        if (botId != null && openBot != null) {
            BotChatPlaceholderScreen(bot = openBot, onBack = { openBotId = null })
        } else {
            HomeScreen(
                uiState = uiState,
                onQueryChange = homeViewModel::setQuery,
                onOpenBot = { id -> openBotId = id },
                onCreateBot = {},
                onCreateGroup = {},
            )
        }
    }
}
