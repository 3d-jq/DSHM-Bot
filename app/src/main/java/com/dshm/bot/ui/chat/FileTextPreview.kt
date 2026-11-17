package com.dshm.bot.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.DshClients
import com.dshm.bot.data.runtime.RuntimeEndpoint
import com.dshm.bot.ui.settings.SettingsPrimaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 读一个附件的**正文**（宿主只读端点 `file-text`）—— 全 App 只留这一个实现。
 *
 * 两处用户入口用的是同一条契约：大模型长文消息的「显示全部」、用户自己发的文档
 * 点开预览。以前各自抄了一份 `DshClients.of(endpoint).bot("file-text", …)`，连
 * 失败时的表现都各写各的 —— 用户感受就是「同一个功能两种样子」。
 *
 * @return 正文；读不到（端点缺失 / 宿主 404 / 空文本）一律 `null`。
 */
internal suspend fun readFileText(endpoint: RuntimeEndpoint?, file: JSONObject?): String? {
    if (endpoint == null || file == null) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            DshClients.of(endpoint)
                .bot("file-text", JSONObject().put("file", file))
                .optString("text")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

/**
 * 附件正文的弹层（两处入口共用）。
 *
 * @param body 正文；为 `null` 且 [failed] 为 false 表示**还在读**（显示转圈）。
 * @param failed 读不到：显示统一的失败文案（比行内那句更具体，说明可能被引擎归档）。
 */
@Composable
internal fun FileTextPreviewDialog(
    title: String,
    body: String?,
    failed: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Dshm.colors.surface,
        title = {
            Text(
                text = title,
                style = Dshm.typography.settingsRowTitle,
                color = Dshm.colors.textPrimary,
                // 文件名可能很长（`1000027794.heic` 这类），两行截断，别把弹层顶高。
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    failed -> Text(
                        text = stringResource(R.string.chat_file_preview_failed),
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.textSecondary,
                    )

                    body == null -> CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = Dshm.colors.accent,
                        modifier = Modifier.size(Dshm.spacing.lg),
                    )

                    else -> Text(
                        text = body,
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.textPrimary,
                    )
                }
            }
        },
        confirmButton = {
            // 弹层的主操作统一用实心按钮（项目规则，别用 TextButton）。
            SettingsPrimaryButton(
                text = stringResource(R.string.action_done),
                onClick = onDismiss,
                fillWidth = false,
            )
        },
    )
}
