package com.dshm.bot.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.ui.components.BotAvatar
import com.dshm.bot.ui.home.ChatRowUi

/**
 * Bot 对话占位页：证明导航与状态打通；真实对话在 DSH 客户端里程碑接入
 * （dsh-Go 的 `packages/dsh_client` 协议参考）。
 */
@Composable
fun BotChatPlaceholderScreen(
    bot: ChatRowUi,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Dshm.colors.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dshm.spacing.topBarHeight)
                    .padding(horizontal = Dshm.spacing.sm)
                    .padding(top = Dshm.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_arrow_left),
                        contentDescription = stringResource(R.string.action_back),
                        tint = Dshm.colors.iconTint,
                    )
                }
                BotAvatar(
                    inkId = bot.inkId,
                    glyph = bot.glyph,
                    size = Dshm.spacing.iconButton,
                )
                Spacer(Modifier.size(Dshm.spacing.sm))
                Text(
                    text = bot.name,
                    style = Dshm.typography.screenTitle,
                    color = Dshm.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = Dshm.spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.chat_placeholder_title, bot.name),
                style = Dshm.typography.emptyTitle,
                color = Dshm.colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Dshm.spacing.sm))
            Text(
                text = stringResource(R.string.chat_placeholder_hint),
                style = Dshm.typography.emptyHint,
                color = Dshm.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
