package com.dshm.bot.ui.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
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
    onPickFile: () -> Unit = {},
    onPickImage: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    mentionCandidates: List<String> = emptyList(),
    attachments: List<ChatAttachmentDraft> = emptyList(),
    onRemoveAttachment: (Int) -> Unit = {},
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
    // 有附件时输入框从胶囊换成圆角方卡（照 DeepSearch 的 20dp），
    // 否则胶囊的圆弧会把缩略图卡的四角裁掉。
    val corner by animateDpAsState(
        targetValue = if (attachments.isEmpty()) Dshm.radius.composer else Dshm.radius.lg,
        animationSpec = tween(180),
        label = "composer-corner",
    )
    val shape = RoundedCornerShape(corner)
    val context = LocalContext.current
    val scaledText = Dshm.typography.composerText.copy(
        fontSize = Dshm.typography.composerText.fontSize * textScale,
        lineHeight = Dshm.typography.composerText.lineHeight * textScale,
        color = colors.textPrimary,
    )
    val mentionStart = value.lastIndexOf('@')
    val mentionActive = mentionCandidates.isNotEmpty() && mentionStart >= 0 &&
        (mentionStart == 0 || value[mentionStart - 1].isWhitespace()) &&
        value.substring(mentionStart + 1).none { it.isWhitespace() }
    val mentionQuery = if (mentionActive) value.substring(mentionStart + 1) else ""
    val mentionEveryone = stringResource(R.string.composer_mention_everyone)
    val mentionMatches = if (mentionActive) {
        (listOf(mentionEveryone) + mentionCandidates)
            .filter { it.contains(mentionQuery, ignoreCase = true) }
            .distinct()
            .take(8)
    } else {
        emptyList()
    }

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

    Column(modifier = modifier.fillMaxWidth()) {
        if (mentionActive && mentionMatches.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = spacing.xs)
                    .clip(RoundedCornerShape(spacing.sm))
                    .background(colors.surface)
                    .border(spacing.hairline, colors.divider, RoundedCornerShape(spacing.sm)),
            ) {
                mentionMatches.forEach { name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onValueChange(value.substring(0, mentionStart) + "@$name ")
                            }
                            .padding(horizontal = spacing.md, vertical = spacing.sm),
                    ) {
                        Text(
                            text = name,
                            style = Dshm.typography.rowPreview,
                            color = colors.textPrimary,
                        )
                    }
                }
            }
        }
        // 输入框本体：附件缩略图也在这块白卡**里面**（照 DeepSearch：缩略图行在
        // 输入框的圆角容器内、文字行上方），不是浮在输入框外面的另一条。
        ComposerFieldSurface(modifier = Modifier.testTag(COMPOSER_TEST_TAG)) {
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = spacing.xs),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(spacing.xs),
            ) {
                attachments.forEachIndexed { index, attachment ->
                    AttachmentChip(
                        attachment = attachment,
                        onRemove = { onRemoveAttachment(index) },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
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
            AttachMenuKey(
                onAttach = onAttach,
                onPickFile = onPickFile,
                onPickImage = onPickImage,
                onTakePhoto = onTakePhoto,
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
                draft = value.ifBlank { if (attachments.isNotEmpty()) " " else "" },
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
    size: Dp = Dshm.spacing.composerKey,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Dshm.colors.surfaceMuted)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = Dshm.colors.iconTint,
            modifier = Modifier.size(if (size < Dshm.spacing.composerKey) size * 0.7f else Dshm.spacing.icon),
        )
    }
}

/** 「＋」附件菜单：文件 / 图片 / 拍照。 */
@Composable
private fun AttachMenuKey(
    onAttach: () -> Unit,
    onPickFile: () -> Unit,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ComposerKey(
            icon = R.drawable.ic_lucide_plus,
            contentDescription = stringResource(R.string.composer_attach),
            onClick = {
                onAttach()
                expanded = true
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = Dshm.colors.surface,
            shape = RoundedCornerShape(Dshm.radius.md),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.composer_add_file)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_lucide_file_text), null) },
                onClick = {
                    expanded = false
                    onPickFile()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.composer_add_image)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_lucide_image), null) },
                onClick = {
                    expanded = false
                    onPickImage()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.composer_take_photo)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_lucide_camera), null) },
                onClick = {
                    expanded = false
                    onTakePhoto()
                },
            )
        }
    }
}

/**
 * 输入卡外壳：对话输入框那一套（28dp 圆角 + 白底 + 细描边 + 投影）。
 *
 * 记忆面板底部的输入框也用它，保证和对话输入框是同款。
 */
@Composable
internal fun ComposerFieldSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Dshm.radius.composer)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = Dshm.spacing.composerElevation,
                shape = shape,
                ambientColor = Dshm.colors.shadowSoft,
                spotColor = Dshm.colors.shadowSoft,
            )
            .clip(shape)
            .background(Dshm.colors.surface)
            .border(Dshm.spacing.hairline, Dshm.colors.divider, shape)
            .padding(Dshm.spacing.composerKeyGap),
        content = content,
    )
}

/** 发送键：有内容主色实心 + ↑；无内容淡底灰箭头（禁用）。 */
@Composable
internal fun SendKey(enabled: Boolean, onSend: () -> Unit) {
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

/**
 * 待发附件的小卡（照 DeepSearch `AttachmentChip`）：
 * 图片显示缩略图，文件显示「图标 + 文件名」；右上角半个圆钮是移除。
 *
 * 外层 72×70 只是为了给移除圆钮留出溢出的位置，卡片本身固定 64dp。
 */
@Composable
private fun AttachmentChip(
    attachment: ChatAttachmentDraft,
    onRemove: () -> Unit,
) {
    val shape = RoundedCornerShape(Dshm.spacing.sm)
    val colors = Dshm.colors
    Box(Modifier.size(width = 72.dp, height = 70.dp)) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(64.dp)
                .clip(shape)
                .background(colors.surfaceMuted)
                .border(Dshm.spacing.hairline, colors.divider, shape),
        ) {
            val image = remember(attachment) {
                if (attachment.isImage) decodeAttachmentThumbnail(attachment.bytes) else null
            }
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Column(
                    modifier = Modifier
                        .matchParentSize()
                        .padding(Dshm.spacing.xxs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_file_text),
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(Dshm.spacing.lg),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = attachment.name,
                        style = Dshm.typography.rowTime.copy(fontSize = 9.sp),
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .background(colors.surface, CircleShape)
                .border(Dshm.spacing.hairline, colors.divider, CircleShape)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_x),
                contentDescription = stringResource(R.string.composer_attach_remove),
                tint = colors.textTertiary,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/** 附件字节 → 缩略图（按 256px 上采样，避免几 MB 的原图整张解码）。 */
private fun decodeAttachmentThumbnail(bytes: ByteArray, max: Int = 256): Bitmap? {
    if (bytes.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth > 0 && bounds.outWidth / sample > max * 2) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
}
