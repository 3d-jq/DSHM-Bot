package com.dshm.bot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.ModelDefaults
import com.dshm.bot.data.remote.ProviderHealth
import com.dshm.bot.data.remote.ProviderModelSpec
import com.dshm.bot.data.remote.formatTokens

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

/**
 * 表单字段块，对齐 dsh-Go 的 `FormFieldBlock`：
 * 字段组顶部 [top]、标题缩进 4dp、标题到字段 4dp、字段下方 16dp。
 */
@Composable
fun ProviderField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    /** 字段组顶部留白；每张卡的第一个字段用 md（dsh-Go 的 FormFieldGroup 上 12dp）。 */
    top: Dp = Dshm.spacing.none,
    /** 标签行右侧的控件（如「粘贴」）。 */
    trailing: (@Composable () -> Unit)? = null,
    /** 输入框内的尾部控件（如 API Key 的显示/隐藏）。 */
    suffix: (@Composable () -> Unit)? = null,
    /** 字段下方的错误说明。 */
    error: String? = null,
) {
    Column(
        modifier = Modifier.padding(horizontal = Dshm.spacing.lg, vertical = top),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = Dshm.typography.formFieldLabel,
                color = Dshm.colors.textPrimary,
                // dsh-Go：字段标题缩进 4dp，与字段边缘对齐。
                modifier = Modifier.padding(start = Dshm.spacing.xs),
            )
            if (trailing != null) {
                Spacer(Modifier.width(Dshm.spacing.sm))
                trailing()
            }
        }
        Spacer(Modifier.height(Dshm.spacing.xs))
        SettingsInput(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            label = label,
            keyboardType = keyboardType,
            visualTransformation = visualTransformation,
            trailing = suffix,
        )
        if (error != null) {
            Spacer(Modifier.height(Dshm.spacing.xs))
            Text(
                text = error,
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.danger,
                modifier = Modifier.padding(start = Dshm.spacing.xs),
            )
        }
        Spacer(Modifier.height(Dshm.spacing.lg))
    }
}

/**
 * 模型行的第二行：id（当它有名字时）、上下文、识图、思考。
 * 对齐 dsh-Go 的 `modelSummary`；没有可说的事时返回空串。
 */
@Composable
fun providerModelSummary(model: ProviderModelSpec, defaults: ModelDefaults): String {
    val parts = mutableListOf<String>()
    if (model.label != model.id) parts += model.id
    model.contextWindow?.let {
        parts += stringResource(R.string.settings_model_item_context_value, formatTokens(it))
    }
    if (model.acceptsImages(defaults.input)) {
        parts += stringResource(R.string.settings_model_item_vision)
    }
    if (model.thinks) {
        parts += stringResource(R.string.settings_model_item_thinking_badge)
    }
    return parts.joinToString(" · ")
}
