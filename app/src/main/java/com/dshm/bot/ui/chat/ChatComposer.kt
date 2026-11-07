package com.dshm.bot.ui.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.dp
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.SpeechRepository
import com.dshm.bot.domain.model.VoiceMode
import java.util.Locale
import kotlinx.coroutines.launch

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
    voiceMode: VoiceMode = VoiceMode.AUTO,
    speech: SpeechRepository? = null,
    stopVisible: Boolean = false,
    onStop: () -> Unit = {},
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
    var engineActive by remember { mutableStateOf(false) }
    var transcribing by remember { mutableStateOf(false) }
    var engineReady by remember { mutableStateOf(false) }
    val latestValue = rememberUpdatedState(value)
    val latestOnValueChange = rememberUpdatedState(onValueChange)
    val scope = rememberCoroutineScope()
    val engineRecorder = remember { EngineVoiceRecorder(context) }
    LaunchedEffect(speech, voiceMode) {
        if (speech == null || voiceMode == VoiceMode.SYSTEM) {
            engineReady = false
        } else {
            repeat(20) {
                engineReady = runCatching {
                    speech.catalog()?.usableProvider != null
                }
                    .onFailure { android.util.Log.w("DshVoice", "speech/catalog failed", it) }
                    .getOrDefault(false)
                android.util.Log.i("DshVoice", "catalog usable=$engineReady mode=$voiceMode")
                if (engineReady) return@LaunchedEffect
                kotlinx.coroutines.delay(3_000)
            }
        }
    }
    val systemVoiceAvailable = remember {
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
    }
    val voiceAvailable = systemVoiceAvailable || (engineReady && voiceMode != VoiceMode.SYSTEM)

    val recognizer = remember {
        if (systemVoiceAvailable) {
            runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        } else {
            null
        }
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
        onDispose {
            runCatching { recognizer?.destroy() }
            engineRecorder.release()
        }
    }

    fun startSystemListening() {
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

    fun beginDictation() {
        if (voiceMode == VoiceMode.SYSTEM || speech == null) {
            startSystemListening()
            return
        }
        if (!engineReady) {
            if (voiceMode == VoiceMode.ENGINE || !systemVoiceAvailable) {
                Toast.makeText(
                    context,
                    "请先在 设置 → 语音输入 中下载并准备 SenseVoice 模型",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                startSystemListening()
            }
            return
        }
        scope.launch {
            val catalog = runCatching { speech.catalog() }.getOrNull()
            val usable = catalog?.usableProvider
            if (usable != null && engineRecorder.start()) {
                listening = true
                engineActive = true
                transcribing = false
                partial = ""
            } else if (voiceMode == VoiceMode.ENGINE) {
                listening = false
                engineActive = false
                Toast.makeText(
                    context,
                    "请先在 设置 → 语音输入 中下载并准备 SenseVoice 模型",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                startSystemListening()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) beginDictation()
    }

    fun startDictation() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        beginDictation()
    }

    fun cancelDictation() {
        if (engineActive) {
            engineRecorder.cancel()
        } else {
            runCatching { recognizer?.cancel() }
        }
        listening = false
        engineActive = false
        transcribing = false
        partial = ""
        level = 0f
    }

    fun stopDictation() {
        if (!engineActive) {
            runCatching { recognizer?.stopListening() }
            return
        }
        val repo = speech
        engineActive = false
        listening = false
        partial = ""
        level = 0f
        if (repo == null) return
        transcribing = true
        engineRecorder.stopAndTranscribe(
            scope = scope,
            speech = repo,
            onText = { text ->
                val merged = listOf(latestValue.value, text)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                latestOnValueChange.value(merged)
                transcribing = false
            },
            onError = { message ->
                transcribing = false
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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
                onClick = { cancelDictation() },
            )
        } else {
            ComposerKey(
                icon = R.drawable.ic_lucide_plus,
                contentDescription = stringResource(R.string.composer_attach),
                onClick = onAttach,
            )
        }
        Spacer(Modifier.width(spacing.sm))

        if (listening || transcribing) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = spacing.composerTextPad),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (transcribing) {
                        stringResource(R.string.composer_voice_transcribing)
                    } else {
                        partial.ifBlank { stringResource(R.string.composer_voice_listening) }
                    },
                    style = scaledText,
                    color = if (transcribing || partial.isBlank()) colors.textTertiary else colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (listening) {
                    VoiceLevelBar(level = if (engineActive) engineRecorder.level else level)
                }
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

        when (
            composerTrailingAction(
                listening = listening,
                transcribing = transcribing,
                stopVisible = stopVisible,
                draft = value,
                voiceAvailable = voiceAvailable,
            )
        ) {
            ComposerTrailingAction.STOP_DICTATION -> StopKey(onStop = { stopDictation() })
            ComposerTrailingAction.TRANSCRIBING -> TranscribingKey()
            ComposerTrailingAction.STOP_RUN -> StopRunKey(onStop = onStop)
            ComposerTrailingAction.VOICE -> ComposerKey(
                icon = R.drawable.ic_lucide_mic,
                contentDescription = stringResource(R.string.composer_voice),
                onClick = { startDictation() },
            )
            ComposerTrailingAction.SEND -> SendKey(enabled = sendEnabled, onSend = onSend)
        }
    }
}

/** 听写中的音量条：对齐 dsh-Go 的 5 根圆头竖条（4–24dp，真实音量从左进右出）。 */
@Composable
private fun VoiceLevelBar(level: Float) {
    val bars = 5
    val history = remember { mutableStateListOf(0f, 0f, 0f, 0f, 0f) }
    LaunchedEffect(level) {
        history.removeAt(0)
        history.add(level.coerceIn(0f, 1f))
    }
    Row(
        modifier = Modifier.height(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.5.dp),
    ) {
        repeat(bars) { index ->
            val height = 4.dp + (24.dp - 4.dp) * history[index]
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(height)
                    .clip(CircleShape)
                    .background(Dshm.colors.textPrimary.copy(alpha = 0.6f)),
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

/** 转写中：右侧圆键显示进度，避免用户重复点击。 */
@Composable
private fun TranscribingKey() {
    Box(
        modifier = Modifier
            .size(Dshm.spacing.composerKey)
            .clip(CircleShape)
            .background(Dshm.colors.surfaceMuted),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(Dshm.spacing.icon),
            strokeWidth = 2.dp,
            color = Dshm.colors.accent,
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

/** 对话执行中的停止键：主色实心 + 方块，照 DeepSearch 的三态发送键。 */
@Composable
private fun StopRunKey(onStop: () -> Unit) {
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
            contentDescription = stringResource(R.string.composer_stop),
            tint = Dshm.colors.onAccent,
            modifier = Modifier.size(Dshm.spacing.xxl * 0.5f),
        )
    }
}
