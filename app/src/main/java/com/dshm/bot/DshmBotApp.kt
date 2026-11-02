package com.dshm.bot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dshm.bot.ui.chat.BotChatPlaceholderScreen
import com.dshm.bot.ui.home.HomeScreen
import com.dshm.bot.ui.home.HomeViewModel

/**
 * 应用外壳：主界面 ↔ Bot 对话。路由状态放在这里，页面本身不感知导航。
 */
@Composable
fun DshmBotApp(
    homeViewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory()),
) {
    val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    var openBotId by rememberSaveable { mutableStateOf<String?>(null) }
    val openBot = uiState.rows.firstOrNull { it.id == openBotId }

    if (openBot != null) {
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
