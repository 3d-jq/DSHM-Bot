package com.dshm.bot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.ProviderHealth

/** dsh-Go 的 Monogram：圆形字母块。用本项目中性面，着色留给后续主题。 */
@Composable
fun Monogram(text: String, size: Dp = Dshm.spacing.composerKey) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Dshm.colors.surfaceMuted),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.trim().take(1).uppercase().ifEmpty { "?" },
            style = Dshm.typography.rowTitle,
            color = Dshm.colors.textPrimary,
        )
    }
}

/** dsh-Go 的渠道状态点：8dp，语义色区分四态。 */
enum class StatusTone { Ok, Idle, Warn, Error, Busy }

fun ProviderHealth.toStatusTone(): StatusTone = when (this) {
    ProviderHealth.READY -> StatusTone.Ok
    ProviderHealth.MISSING_KEY -> StatusTone.Warn
    ProviderHealth.MISCONFIGURED -> StatusTone.Error
    ProviderHealth.DISABLED -> StatusTone.Idle
}

@Composable
fun StatusDot(tone: StatusTone) {
    val color = when (tone) {
        StatusTone.Ok -> Dshm.colors.success
        StatusTone.Idle -> Dshm.colors.textTertiary
        StatusTone.Warn -> Dshm.colors.warning
        StatusTone.Error -> Dshm.colors.danger
        StatusTone.Busy -> Dshm.colors.accent
    }
    Box(
        modifier = Modifier
            .size(Dshm.spacing.statusDot)
            .clip(CircleShape)
            .background(color),
    )
}
