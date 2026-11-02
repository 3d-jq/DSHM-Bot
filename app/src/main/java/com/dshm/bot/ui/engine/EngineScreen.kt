package com.dshm.bot.ui.engine

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.runtime.RuntimeState
import com.dshm.bot.ui.components.FloatingIconButton

/** 默认下载地址：我们自己的 Release 运行时包（可改）。 */
const val DEFAULT_RUNTIME_URL = "https://github.com/3d-jq/DSHM-Bot/releases/latest/download/dshm-runtime-arm64-v8a.zip"

/** 本机引擎 / 开屏引导：说明 → 下载安装运行时 → 进度 / 错误重试。 */
@Composable
fun EngineScreen(
    state: RuntimeState,
    onBack: (() -> Unit)? = null,
    onInstall: (String) -> Unit,
    onStart: () -> Unit = {},
    onStop: () -> Unit = {},
    onDone: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize(), containerColor = Dshm.colors.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Dshm.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Dshm.spacing.lg),
        ) {
            Row(
                modifier = Modifier.padding(vertical = Dshm.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    FloatingIconButton(
                        icon = R.drawable.ic_lucide_arrow_left,
                        contentDescription = stringResource(R.string.action_back),
                        onClick = onBack,
                    )
                    Spacer(Modifier.width(Dshm.spacing.sm))
                }
                Text(stringResource(R.string.engine_title), style = Dshm.typography.pageTitle, color = Dshm.colors.textPrimary)
            }

            Icon(
                painter = painterResource(R.drawable.ic_lucide_server),
                contentDescription = null,
                tint = Dshm.colors.iconTint,
                modifier = Modifier.size(Dshm.spacing.avatar),
            )
            Text(stringResource(R.string.engine_intro), style = Dshm.typography.rowTitle, color = Dshm.colors.textPrimary)
            Text(stringResource(R.string.engine_intro_hint), style = Dshm.typography.rowPreview, color = Dshm.colors.textSecondary)

            when (state) {
                is RuntimeState.Installing -> {
                    LinearProgressIndicator(
                        progress = { if (state.progress > 0) state.progress / 100f else 0f },
                        modifier = Modifier.fillMaxWidth(),
                        color = Dshm.colors.accent,
                    )
                    Text("${state.message} ${if (state.progress > 0) "${state.progress}%" else ""}", style = Dshm.typography.rowPreview, color = Dshm.colors.textSecondary)
                }

                is RuntimeState.Installed -> Text(
                    stringResource(R.string.engine_installed, state.version),
                    style = Dshm.typography.rowTitle,
                    color = Dshm.colors.accent,
                )

                is RuntimeState.Failed -> Text(state.message, style = Dshm.typography.rowPreview, color = Dshm.colors.danger)

                is RuntimeState.Starting -> Text(stringResource(R.string.engine_starting), style = Dshm.typography.rowPreview, color = Dshm.colors.textSecondary)

                is RuntimeState.Running -> Text(stringResource(R.string.engine_running, state.endpoint.baseUrl), style = Dshm.typography.rowPreview, color = Dshm.colors.accent)


                RuntimeState.Stopped -> Text(stringResource(R.string.engine_stopped), style = Dshm.typography.rowPreview, color = Dshm.colors.textSecondary)

                RuntimeState.NotInstalled -> Text(
                    stringResource(R.string.engine_not_installed),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
            }

            if (state is RuntimeState.Running) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.engine_continue)) }
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.engine_stop_action)) }
            }
            if (state is RuntimeState.Installed || state is RuntimeState.Stopped) {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.engine_start_action)) }
            }
            Button(
                onClick = { onInstall(DEFAULT_RUNTIME_URL) },
                enabled = state !is RuntimeState.Installing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.engine_install_action))
            }
        }
    }
}




