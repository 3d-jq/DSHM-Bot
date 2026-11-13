package com.dshm.bot.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.domain.model.ChatApproval

/**
 * 审批卡片：Bot 要跑命令/调工具时，DSH 那边会等一个审批。
 *
 * 规格照 DS Bot 的 `client/member-status.js` 的 `ApprovalCard`：
 * 工具名 → 详情文本 → 原因 → 错误码 → 动作。
 *
 * 动作分两种（由 [ChatApproval.Action] 决定，界面不再自己猜）：
 * - [ChatApproval.Action.ANSWER]：「拒绝 / 允许一次」可点；
 * - [ChatApproval.Action.ANSWER_ELSEWHERE]：应答入口不在这儿，说明去哪儿答。
 */
@Composable
fun ApprovalCard(
    approval: ChatApproval,
    busy: Boolean,
    onAnswer: (allow: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onAnswerQuestion: (List<String>) -> Unit = {},
) {
    val shape = RoundedCornerShape(Dshm.radius.md)
    var selected by remember(approval.key) { mutableStateOf(emptyList<String>()) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Dshm.colors.surface)
            .border(Dshm.spacing.hairline, Dshm.colors.divider, shape)
            .padding(Dshm.spacing.md),
    ) {
        Text(
            text = stringResource(
                if (approval.kind == ChatApproval.Kind.QUESTION) {
                    R.string.question_waiting
                } else {
                    R.string.approval_waiting
                },
            ),
            style = Dshm.typography.rowTime,
            color = Dshm.colors.warning,
        )
        if (approval.tool.isNotEmpty()) {
            Spacer(Modifier.height(Dshm.spacing.xs))
            Text(
                text = approval.tool,
                style = Dshm.typography.settingsRowTitle.copy(fontFamily = FontFamily.Monospace),
                color = Dshm.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        approval.detail?.let { detail ->
            Spacer(Modifier.height(Dshm.spacing.xs))
            Text(
                text = detail,
                style = Dshm.typography.rowPreview.copy(fontFamily = FontFamily.Monospace),
                color = Dshm.colors.textPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (approval.reason.isNotEmpty()) {
            Spacer(Modifier.height(Dshm.spacing.xs))
            Text(
                text = approval.reason,
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        approval.code?.let { code ->
            Spacer(Modifier.height(Dshm.spacing.xs))
            Text(
                text = code,
                style = Dshm.typography.rowTime.copy(fontFamily = FontFamily.Monospace),
                color = Dshm.colors.textTertiary,
            )
        }
        Spacer(Modifier.height(Dshm.spacing.sm))
        val question = approval.kind == ChatApproval.Kind.QUESTION && approval.options.isNotEmpty()
        if (question) {
            approval.options.forEach { option ->
                val picked = option in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Dshm.spacing.xxs)
                        .clip(RoundedCornerShape(Dshm.radius.sm))
                        .background(if (picked) Dshm.colors.accent.copy(alpha = 0.12f) else Dshm.colors.surfaceMuted)
                        .clickable(enabled = !busy) {
                            if (approval.multiSelect) {
                                selected = if (picked) selected - option else selected + option
                            } else {
                                onAnswerQuestion(listOf(option))
                            }
                        }
                        .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = option,
                        style = Dshm.typography.rowPreview,
                        color = if (picked) Dshm.colors.accent else Dshm.colors.textPrimary,
                    )
                }
            }
            if (approval.multiSelect) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                ) {
                    TextButton(
                        onClick = { onAnswerQuestion(selected) },
                        enabled = selected.isNotEmpty() && !busy,
                    ) {
                        Text(
                            text = stringResource(R.string.question_submit),
                            style = Dshm.typography.sheetAction,
                            color = Dshm.colors.accent,
                        )
                    }
                }
            }
        } else if (approval.action == ChatApproval.Action.ANSWER_ELSEWHERE) {
            Text(
                text = stringResource(R.string.approval_in_dsh),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textTertiary,
            )
        } else {
            val clickable = approval.action == ChatApproval.Action.ANSWER && !busy
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onAnswer(false) }, enabled = clickable) {
                    Text(
                        text = stringResource(R.string.approval_reject),
                        style = Dshm.typography.sheetAction,
                        color = Dshm.colors.textSecondary,
                    )
                }
                Spacer(Modifier.width(Dshm.spacing.sm))
                TextButton(onClick = { onAnswer(true) }, enabled = clickable) {
                    Text(
                        text = stringResource(R.string.approval_allow_once),
                        style = Dshm.typography.sheetAction,
                        color = Dshm.colors.accent,
                    )
                }
            }
        }
    }
}
