package com.dshm.bot.ui.engine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.runtime.RuntimeState

/**
 * 开屏引导页：首次启动专用，只负责「下载运行环境 → 启动 → 进入」。
 * 与设置里的「本机引擎」页分开，后者用于查看状态/日志/重置。
 */
@Composable
fun OnboardingScreen(
    state: RuntimeState,
    onInstall: (String) -> Unit,
    onStart: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize(), containerColor = Dshm.colors.pageBackground) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dshm.spacing.xl, vertical = Dshm.spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Dshm.spacing.lg),
        ) {
            Spacer(Modifier.height(Dshm.spacing.xl))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_bot),
                    contentDescription = null,
                    tint = Dshm.colors.accent,
                    modifier = Modifier.size(Dshm.spacing.avatar),
                )
                Spacer(Modifier.size(Dshm.spacing.md))
                Text(
                    text = stringResource(R.string.app_name),
                    style = Dshm.typography.pageTitle,
                    color = Dshm.colors.textPrimary,
                )
            }
            Text(
                text = stringResource(R.string.onboarding_title),
                style = Dshm.typography.sheetTitle,
                color = Dshm.colors.textPrimary,
            )
            Text(
                text = stringResource(R.string.onboarding_intro),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
            )
            Text(
                text = stringResource(R.string.onboarding_download_hint),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textTertiary,
            )

            when (state) {
                is RuntimeState.Installing -> {
                    LinearProgressIndicator(
                        progress = { if (state.progress > 0) state.progress / 100f else 0f },
                        modifier = Modifier.fillMaxWidth(),
                        color = Dshm.colors.accent,
                    )
                    Text(
                        text = "${state.message} ${if (state.progress > 0) "${state.progress}%" else ""}",
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.textSecondary,
                    )
                }

                is RuntimeState.Failed -> Text(
                    text = state.message,
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.danger,
                )

                is RuntimeState.Starting -> Text(
                    text = stringResource(R.string.engine_starting),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )

                else -> Unit
            }

            val installButton = state is RuntimeState.NotInstalled || state is RuntimeState.Failed
            if (installButton) {
                Button(onClick = { onInstall(DEFAULT_RUNTIME_URL) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.engine_install_action))
                }
            }
            if (state is RuntimeState.Installed || state is RuntimeState.Stopped) {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.engine_start_action))
                }
            }
            if (state is RuntimeState.Running) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.engine_continue))
                }
            }
        }
    }
}
