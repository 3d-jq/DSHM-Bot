package com.dshm.bot.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.runtime.RuntimeEndpoint
import com.dshm.bot.ui.components.FloatingIconButton
import com.dshm.bot.ui.settings.WebHostScreen

/** Bot 的「浏览器」：全屏承载本机 DSH Web，左上角保留返回键。 */
@Composable
fun BotBrowserScreen(
    endpoint: RuntimeEndpoint?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        WebHostScreen(endpoint = endpoint, modifier = Modifier.fillMaxSize())
        FloatingIconButton(
            icon = R.drawable.ic_lucide_arrow_left,
            contentDescription = stringResource(R.string.action_back),
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(Dshm.spacing.lg),
        )
    }
}
