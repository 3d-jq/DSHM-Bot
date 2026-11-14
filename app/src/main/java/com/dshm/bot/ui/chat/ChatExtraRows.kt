package com.dshm.bot.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.DshClients
import com.dshm.bot.data.runtime.RuntimeEndpoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.dshm.bot.ui.settings.SettingsPrimaryButton

/**
 * `divider`：一段会话的起点（换对话 / 换目录后）。
 *
 * 对齐 DS Bot 网页版 `PartLine`：一条细线 + 日期时间（今天只显示「今天 HH:mm」）。
 */
@Composable
fun PartDividerRow(timeMs: Long?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Dshm.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.HorizontalDivider(
            color = Dshm.colors.divider,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = partDividerLabel(timeMs),
            style = Dshm.typography.rowTime,
            color = Dshm.colors.textTertiary,
            modifier = Modifier.padding(horizontal = Dshm.spacing.sm),
        )
        androidx.compose.material3.HorizontalDivider(
            color = Dshm.colors.divider,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 分隔线上的日期：今天 → 「今天 HH:mm」，其它 → 月日 + 周几 + 时间。 */
@Composable
private fun partDividerLabel(timeMs: Long?): String {
    val time = timeMs ?: return stringResource(R.string.chat_part_now)
    val date = Date(time)
    val today = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = time }
    val sameDay = today.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        today.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
    if (sameDay) return stringResource(R.string.chat_part_today, clock)
    return SimpleDateFormat(
        stringResource(R.string.chat_part_date_format),
        Locale.getDefault(),
    ).format(date)
}

/**
 * `answer`：用户回答过的提问。
 *
 * 对齐 DS Bot 网页版 `AnsweredCell` / `SettledQuestion`：问题 + 「你的回答」+ 选中的选项；
 * 一条都没选时显示「未回答」（DS Bot 的 `Dismissed`）。
 */
@Composable
fun AnsweredQuestionCard(
    answered: List<ChatAnsweredQuestion>,
    modifier: Modifier = Modifier,
) {
    if (answered.isEmpty()) return
    val shape = RoundedCornerShape(Dshm.radius.lg)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Dshm.colors.bubbleAssistant)
            .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xs),
    ) {
        answered.forEach { item ->
            if (item.question.isNotBlank()) {
                Text(
                    text = item.question,
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textPrimary,
                )
            }
            if (item.answers.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_answered_dismissed),
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.textTertiary,
                )
            } else {
                Text(
                    text = stringResource(R.string.chat_answered_your_answer),
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.textSecondary,
                )
                item.answers.forEach { answer ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_lucide_check),
                            contentDescription = null,
                            tint = Dshm.colors.accent,
                            modifier = Modifier.size(Dshm.spacing.md),
                        )
                        Spacer(Modifier.width(Dshm.spacing.xs))
                        Text(
                            text = answer,
                            style = Dshm.typography.rowPreview,
                            color = Dshm.colors.textPrimary,
                        )
                    }
                }
            }
        }
    }
}

/** 只读视图卡的外框：标题 + 内容（内容由 `PendingDock` 里的 `ViewNode` 画）。 */
@Composable
fun ReadOnlyCardFrame(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(Dshm.radius.lg)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Dshm.colors.surface)
            .border(Dshm.spacing.hairline, Dshm.colors.divider, shape)
            .padding(Dshm.spacing.md),
        verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
    ) {
        Text(
            text = title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_view_title),
            style = Dshm.typography.settingsRowTitle,
            color = Dshm.colors.textPrimary,
        )
        content()
    }
}

/**
 * 用户消息下的图片计数（历史回读只有数量，没有图）。
 *
 * 画在气泡**外面**（附件区），所以用页面上的次要文字色，而不是气泡里的白字。
 */
@Composable
fun ImageCountCaption(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Row(
        modifier = modifier.padding(top = Dshm.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lucide_image),
            contentDescription = null,
            tint = Dshm.colors.textSecondary,
            modifier = Modifier.size(Dshm.spacing.md),
        )
        Spacer(Modifier.width(Dshm.spacing.xxs))
        Text(
            text = stringResource(R.string.chat_user_images, count),
            style = Dshm.typography.rowTime,
            color = Dshm.colors.textSecondary,
        )
    }
}

/**
 * 群聊被中转上限暂停时的卡片（输入框上方）。
 *
 * 对齐 DS Bot 的 `paused` 行 + 「Continue」按钮（`client.js:7784`）：那一行告诉用户
 * 「Bot 互相喊到这个数就停下了、谁在等」，按钮走 `room-continue`。
 */
@Composable
fun RoomPausedCard(
    paused: List<ChatPausedMember>,
    busy: Boolean,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (paused.isEmpty()) return
    val shape = RoundedCornerShape(Dshm.radius.lg)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = Dshm.spacing.xs)
            .clip(shape)
            .background(Dshm.colors.surface)
            .border(Dshm.spacing.hairline, Dshm.colors.divider, shape)
            .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xxs),
    ) {
        Text(
            text = stringResource(R.string.chat_room_paused, paused.size),
            style = Dshm.typography.settingsRowTitle,
            color = Dshm.colors.textPrimary,
        )
        Text(
            text = stringResource(
                R.string.chat_room_paused_waiting,
                paused.joinToString("、") { it.name },
            ),
            style = Dshm.typography.rowTime,
            color = Dshm.colors.textSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(enabled = !busy, onClick = onContinue) {
                Text(
                    text = stringResource(R.string.chat_room_continue),
                    color = if (busy) Dshm.colors.textTertiary else Dshm.colors.accent,
                )
            }
        }
    }
}

/**
 * 「先给我选个模型」卡片（网页版 `ModelChoiceDock`）。
 *
 * Bot 还没有模型、或原来的模型没了时，输入框上方出这张卡：一句话 + 一排模型 chip。
 */
@Composable
fun ModelChoiceCard(
    prompt: ChatModelPrompt,
    botName: String,
    busy: Boolean,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Dshm.radius.lg)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = Dshm.spacing.xs)
            .clip(shape)
            .background(Dshm.colors.bubbleAssistant)
            .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xs),
    ) {
        Text(
            text = "$botName：${prompt.message}",
            style = Dshm.typography.rowPreview,
            color = Dshm.colors.textPrimary,
        )
        if (prompt.choices.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xs),
            ) {
                prompt.choices.forEach { choice ->
                    Text(
                        text = choice.name,
                        style = Dshm.typography.rowTime,
                        color = if (busy) Dshm.colors.textTertiary else Dshm.colors.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Dshm.radius.pill))
                            .border(
                                Dshm.spacing.hairline,
                                Dshm.colors.accent.copy(alpha = if (busy) 0.3f else 1f),
                                RoundedCornerShape(Dshm.radius.pill),
                            )
                            .clickable(enabled = !busy) { onPick(choice.ref) }
                            .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.xs),
                    )
                }
            }
        }
    }
}

/**
 * 长文消息的附件行（`whole`）：点「显示全部 · 文件名」按 DS Bot 的 `file-text`
 * 把完整正文读出来，弹窗里看。
 */
@Composable
fun WholeAttachmentRow(
    post: ChatWholePost,
    endpoint: RuntimeEndpoint?,
    modifier: Modifier = Modifier,
) {
    var full by remember(post.name) { mutableStateOf<String?>(null) }
    var busy by remember(post.name) { mutableStateOf(false) }
    var failed by remember(post.name) { mutableStateOf(false) }
    var shown by remember(post.name) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !busy) {
                scope.launch {
                    if (full == null && !failed) {
                        if (endpoint == null) {
                            failed = true
                        } else {
                            busy = true
                            val loaded = withContext(Dispatchers.IO) {
                                runCatching {
                                    DshClients.of(endpoint)
                                        .bot("file-text", JSONObject().put("file", post.file))
                                        .optString("text")
                                }.getOrNull()
                            }
                            busy = false
                            if (loaded.isNullOrBlank()) failed = true else full = loaded
                        }
                    }
                    if (!failed) shown = true
                }
            }
            .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lucide_file_text),
            contentDescription = null,
            tint = Dshm.colors.textSecondary,
            modifier = Modifier.size(Dshm.spacing.md),
        )
        Spacer(Modifier.width(Dshm.spacing.xxs))
        Text(
            text = if (failed) {
                stringResource(R.string.chat_whole_failed)
            } else {
                stringResource(R.string.chat_whole_show_all, post.name)
            },
            style = Dshm.typography.rowTime,
            color = if (failed) Dshm.colors.danger else Dshm.colors.accent,
        )
        if (busy) {
            Spacer(Modifier.width(Dshm.spacing.xs))
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = Dshm.colors.accent,
                modifier = Modifier.size(Dshm.spacing.md),
            )
        }
    }

    val body = full
    if (shown && body != null) {
        AlertDialog(
            onDismissRequest = { shown = false },
            containerColor = Dshm.colors.surface,
            title = {
                Text(
                    text = post.name,
                    style = Dshm.typography.settingsRowTitle,
                    color = Dshm.colors.textPrimary,
                )
            },
            text = {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = body,
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.textPrimary,
                    )
                }
            },
            confirmButton = {
                SettingsPrimaryButton(
                    text = stringResource(R.string.action_done),
                    onClick = { shown = false },
                    fillWidth = false,
                )
            },
        )
    }
}

