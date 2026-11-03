package com.dshm.bot.ui.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import java.util.Locale

/** 输入框纯逻辑（便于单测）：空白（含全空格）不能发送。 */
object ComposerRules {
    fun canSend(text: String): Boolean = text.isNotBlank()
}

/** 测试用 tag：用于断言输入框高度随笔行数增长。 */
const val COMPOSER_TEST_TAG = "chat_composer"

/**
 * 对话输入框（一比一参照 dsh-Go `composer.dart`）：
 * - 28dp 圆角胶囊，单行高 56dp（40dp 圆键 + 上下 8dp 间隙）
 * - 左侧 40dp「＋」；右侧 40dp 键：草稿为空时是麦克风，有内容时是发送
 * - 听写中：＋ 变 ✕（取消）、右侧变主色停止键，中间显示实时识别结果 + 音量条
 * - 中间多行输入（1–6 行自适应），空白时显示提示
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
    val scaledText = Dshm.typography.composerText.copy(
        fontSize = Dshm.typography.composerText.fontSize * textScale,
        lineHeight = Dshm.typography.composerText.lineHeight * textScale,
        color = colors.textPrimary,
    )

    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }
    val latestValue = rememberUpdatedState(value)
    val latestOnValueChange = rememberUpdatedState(onValueChange)

    val recognizer = remember {
        runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
    }
    DisposableEffect(recognizer) {
        if (recognizer != null) {
            recognizer.setRecognitionListener(
                object : RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {
                        level = (rmsdB / 10f).coerceIn(0f, 1f)
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        listening = false
                        partial = ""
                        level = 0f
                    }

                    override fun onResults(results: android.os.Bundle?) {
                        val spoken = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        if (!spoken.isNullOrBlank()) {
                            val merged = listOf(latestValue.value, spoken)
                                .filter { it.isNotBlank() }
                                .joinToString(" ")
                            latestOnValueChange.value(merged)
                        }
                        listening = false
                        partial = ""
                        level = 0f
                    }

                    override fun onPartialResults(partialResults: android.os.Bundle?) {
                        val text = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        if (!text.isNullOrBlank()) partial = text
                    }

                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                },
            )
        }
        onDispose { runCatching { recognizer?.destroy() } }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            listening = true
            partial = ""
            recognizer?.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                },
            )
        }
    }

    fun startDictation() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        listening = true
        partial = ""
        recognizer?.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            },
        )
    }

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
        // 听写中：＋ → ✕（取消）。
        if (listening) {
            ComposerKey(
                icon = R.drawable.ic_lucide_x,
                contentDescription = stringResource(R.string.composer_voice_cancel),
                onClick = {
                    runCatching { recognizer?.cancel() }
                    listening = false
                    partial = ""
                },
            )
        } else {
            ComposerKey(
                icon = R.drawable.ic_lucide_plus,
                contentDescription = stringResource(R.string.composer_attach),
                onClick = onAttach,
            )
        }
        Spacer(Modifier.width(spacing.sm))

        if (listening) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = spacing.composerTextPad),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = partial.ifBlank { stringResource(R.string.composer_voice_listening) },
                    style = scaledText,
                    color = if (partial.isBlank()) colors.textTertiary else colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                VoiceLevelBar(level = level)
            }
        } else {
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
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                },
            )
        }
        Spacer(Modifier.width(spacing.sm))

        when {
            listening -> StopKey(onStop = { runCatching { recognizer?.stopListening() } })
            value.isBlank() && recognizer != null -> ComposerKey(
                icon = R.drawable.ic_lucide_mic,
                contentDescription = stringResource(R.string.composer_voice),
                onClick = { startDictation() },
            )
            else -> SendKey(enabled = sendEnabled, onSend = onSend)
        }
    }
}

/** 听写中的音量条（用识别 RMS 驱动）。 */
@Composable
private fun VoiceLevelBar(level: Float) {
    val bars = 3
    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Dshm.spacing.xxs)) {
        repeat(bars) { index ->
            val active = level > (index + 1) / (bars + 1f)
            Box(
                modifier = Modifier
                    .size(width = Dshm.spacing.xxs, height = Dshm.spacing.sm)
                    .clip(CircleShape)
                    .background(if (active) Dshm.colors.accent else Dshm.colors.textTertiary.copy(alpha = 0.3f)),
            )
        }
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

/** 发送键：有内容主色实心 + ↑；无内容淡底灰箭头（禁用）。 */
@Composable
private fun SendKey(enabled: Boolean, onSend: () -> Unit) {
    Box(
        modifier = Modifier
            .size(Dshm.spacing.composerKey)
            .clip(CircleShape)
            .background(if (enabled) Dshm.colors.accent else Dshm.colors.surfaceMuted)
            .then(if (enabled) Modifier.clickable(onClick = onSend) else Modifier),
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

/** 听写中的停止键：主色实心 + 方块。 */
@Composable
private fun StopKey(onStop: () -> Unit) {
    Box(
        modifier = Modifier
            .size(Dshm.spacing.composerKey)
            .clip(CircleShape)
            .background(Dshm.colors.accent)
            .clickable(onClick = onStop),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lucide_square),
            contentDescription = stringResource(R.string.composer_voice_stop),
            tint = Dshm.colors.onAccent,
            modifier = Modifier.size(Dshm.spacing.xxl * 0.5f),
        )
    }
}
