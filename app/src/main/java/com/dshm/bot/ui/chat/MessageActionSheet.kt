package com.dshm.bot.ui.chat

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.compose.foundation.layout.height
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.ui.components.rememberWindowHeightDp

/**
 * 长按一条消息时弹出的操作面板（对齐 dsh-Go 的 `showMessageMenu`）：
 * 顶上是被按那条消息的预览，下面是动作行。
 */
internal data class MessageMenuTarget(
    val messageId: String,
    val text: String,
    val isUser: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageActionSheet(
    target: MessageMenuTarget,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onSelectText: () -> Unit,
    onShare: () -> Unit,
    onEdit: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Dshm.colors.surface,
        contentColor = Dshm.colors.textPrimary,
        scrimColor = Dshm.colors.scrim,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = Dshm.spacing.md),
        ) {
            // 预览：和 dsh-Go 一样，先给一段这条消息的原文，确认按的是哪条。
            Text(
                text = target.text.trim(),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dshm.spacing.lg)
                    .padding(bottom = Dshm.spacing.md)
                    .clip(RoundedCornerShape(Dshm.radius.md))
                    .background(Dshm.colors.surfaceMuted)
                    .padding(Dshm.spacing.md),
            )
            MessageMenuRow(
                icon = R.drawable.ic_lucide_copy,
                label = stringResource(R.string.chat_menu_copy),
                onClick = onCopy,
            )
            MessageMenuRow(
                icon = R.drawable.ic_lucide_type,
                label = stringResource(R.string.chat_menu_select_text),
                onClick = onSelectText,
            )
            MessageMenuRow(
                icon = R.drawable.ic_lucide_share_2,
                label = stringResource(R.string.chat_menu_share),
                onClick = onShare,
            )
            if (onEdit != null) {
                MessageMenuRow(
                    icon = R.drawable.ic_lucide_pencil,
                    label = stringResource(R.string.chat_menu_edit_resend),
                    hint = stringResource(R.string.chat_menu_edit_resend_hint),
                    onClick = onEdit,
                )
            }
        }
    }
}

@Composable
private fun MessageMenuRow(
    icon: Int,
    label: String,
    hint: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Dshm.colors.textSecondary,
            modifier = Modifier.size(Dshm.spacing.icon - Dshm.spacing.xxs),
        )
        Spacer(Modifier.width(Dshm.spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = Dshm.typography.rowTitle,
                color = Dshm.colors.textPrimary,
            )
            hint?.let {
                Text(
                    text = it,
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.textTertiary,
                )
            }
        }
    }
}

/**
 * 「选择文字」：底部面板，整条消息可以直接拖选复制（对齐 dsh-Go `showSelectTextSheet`）。
 *
 * 回复按 markdown 画（选出来的是看到的文字），用户消息按纯文本画；
 * 面板里关掉聊天页那套自定义选区（`LocalMinisSelectionController`），
 * 改用 Compose 自己的 `SelectionContainer`，避免两个选区系统打架。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectTextSheet(
    text: String,
    markdown: Boolean,
    onDismiss: () -> Unit,
    onCopyAll: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val screenHeight = rememberWindowHeightDp()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Dshm.colors.surface,
        contentColor = Dshm.colors.textPrimary,
        scrimColor = Dshm.colors.scrim,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(screenHeight * 0.85f)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Dshm.spacing.lg, end = Dshm.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.chat_menu_select_text),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCopyAll) {
                    Text(stringResource(R.string.chat_copy_all))
                }
            }
            androidx.compose.runtime.CompositionLocalProvider(
                LocalMinisSelectionController provides null,
                LocalMarkdownMessageId provides "select-text-sheet",
                LocalShardSubIndex provides null,
            ) {
                SelectionContainer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = Dshm.spacing.lg)
                            .padding(top = Dshm.spacing.xs, bottom = Dshm.spacing.xxxl),
                    ) {
                        if (markdown) {
                            MarkdownText(
                                markdown = text,
                                fontScale = 1f,
                                messageId = "",
                            )
                        } else {
                            Text(
                                text = text,
                                style = Dshm.typography.composerText,
                                color = Dshm.colors.textPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 分享一段文字：系统分享面板。 */
internal fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, null))
    }.onFailure {
        Toast.makeText(context, it.message ?: "分享失败", Toast.LENGTH_SHORT).show()
    }
}
