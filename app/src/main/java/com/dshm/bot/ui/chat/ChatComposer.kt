package com.dshm.bot.ui.chat

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm

/** 输入框纯逻辑（便于单测）：空白（含全空格）不能发送。 */
object ComposerRules {
    fun canSend(text: String): Boolean = text.isNotBlank()
}

/** 测试用 tag：用于断言输入框高度随笔行数增长。 */
const val COMPOSER_TEST_TAG = "chat_composer"

/**
 * 对话输入框（参照 dsh-Go 的 composer）：
 * - 28dp 圆角胶囊，单行高 56dp（40dp 圆键 + 上下 8dp 间隙）
 * - 左侧 40dp「＋」附件圆键（淡底）
 * - 中间多行输入（1–6 行自适应），空白时显示提示
 * - 右侧 40dp 发送圆键：有内容时主色实心 + ↑，无内容时淡底灰箭头（禁用）
 */
@Composable
fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onAttach: () -> Unit = {},
    sendEnabled: Boolean = ComposerRules.canSend(value),
    maxLines: Int = 6,
    textScale: Float = 1f,
) {
    val colors = Dshm.colors
    val spacing = Dshm.spacing
    val shape = RoundedCornerShape(Dshm.radius.composer)
    val context = LocalContext.current
    // 语音输入：用系统识别服务（dsh-Go 的「自动/系统」路径），结果回填输入框。
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            onValueChange(listOf(value, spoken).filter { it.isNotBlank() }.joinToString(" "))
        }
    }
    val scaledText = Dshm.typography.composerText.copy(
        fontSize = Dshm.typography.composerText.fontSize * textScale,
        lineHeight = Dshm.typography.composerText.lineHeight * textScale,
        color = colors.textPrimary,
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(COMPOSER_TEST_TAG)
            .shadow(
                elevation = spacing.composerElevation,
                shape = shape,
                ambientColor = colors.shadowSoft,
                spotColor = colors.shadowSoft,
            )
            .clip(shape)
            .background(colors.surface)
            .border(spacing.hairline, colors.divider, shape)
            .padding(spacing.composerKeyGap),
        verticalAlignment = Alignment.Bottom,
    ) {
        ComposerKey(
            icon = R.drawable.ic_lucide_plus,
            contentDescription = stringResource(R.string.composer_attach),
            onClick = onAttach,
        )
        Spacer(Modifier.width(spacing.sm))
        ComposerKey(
            icon = R.drawable.ic_lucide_mic,
            contentDescription = stringResource(R.string.composer_voice),
            onClick = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, placeholder)
                }
                runCatching { speechLauncher.launch(intent) }
            },
        )
        Spacer(Modifier.width(spacing.sm))
        // 直接放进 Row：minLines=1、maxLines=6，按行数自然增高；
        // 单行时靠上下 padding 在 40dp 圆键高度内居中，多行时整体向上长高。
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = scaledText,
            cursorBrush = SolidColor(colors.accent),
            minLines = 1,
            maxLines = maxLines,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = spacing.composerTextPad),
            decorationBox = { innerTextField ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = scaledText.copy(color = colors.textTertiary),
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                innerTextField()
            },
        )
        Spacer(Modifier.width(spacing.sm))
        SendKey(enabled = sendEnabled, onSend = onSend)
    }
}

/** 左/右 40dp 圆键（淡底圆）。 */
@Composable
private fun ComposerKey(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(Dshm.spacing.composerKey)
            .clip(CircleShape)
            .background(Dshm.colors.surfaceMuted)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = Dshm.colors.iconTint,
            modifier = Modifier.size(Dshm.spacing.icon),
        )
    }
}

/** 发送键：有内容主色实心，无内容淡底灰箭头（禁用）。 */
@Composable
private fun SendKey(enabled: Boolean, onSend: () -> Unit) {
    Box(
        modifier = Modifier
            .size(Dshm.spacing.composerKey)
            .clip(CircleShape)
            .background(if (enabled) Dshm.colors.accent else Dshm.colors.surfaceMuted)
            .clickable(enabled = enabled) { onSend() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lucide_arrow_up),
            contentDescription = stringResource(R.string.composer_send),
            tint = if (enabled) Dshm.colors.onAccent else Dshm.colors.textTertiary,
            modifier = Modifier.size(Dshm.spacing.composerSendIcon),
        )
    }
}
