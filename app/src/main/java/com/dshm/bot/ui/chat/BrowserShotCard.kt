package com.dshm.bot.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm

/**
 * 对话里的浏览器缩略图卡：一轮浏览器操作结束后贴一张「它当时看到的页面」。
 *
 * 图来自应用缓存目录（聊天页抓的缩略图），不是引擎工作区里的那份截图；
 * 点卡片直接打开这个 Bot 的浏览器。
 */
@Composable
fun BrowserShotCard(
    shot: ChatBrowserShot?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    if (shot == null) return
    val bitmap = remember(shot.path) {
        runCatching { BitmapFactory.decodeFile(shot.path) }.getOrNull()
    }
    val shape = RoundedCornerShape(Dshm.radius.md)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Dshm.colors.surface)
            .border(Dshm.spacing.hairline, Dshm.colors.divider, shape)
            .clickable(onClick = onClick),
    ) {
        if (bitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SHOT_HEIGHT)
                    .clip(RoundedCornerShape(topStart = Dshm.radius.md, topEnd = Dshm.radius.md)),
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Dshm.colors.surfaceMuted)
                .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_globe),
                contentDescription = null,
                tint = Dshm.colors.textSecondary,
                modifier = Modifier.size(Dshm.spacing.settingsRowIcon),
            )
            Spacer(Modifier.size(Dshm.spacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    text = shot.title.ifBlank { browserHostOf(shot.url) ?: shot.url },
                    style = Dshm.typography.settingsRowTitle,
                    color = Dshm.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = shot.url,
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_lucide_chevron_right),
                contentDescription = null,
                tint = Dshm.colors.textSecondary,
                modifier = Modifier
                    .padding(start = Dshm.spacing.xs)
                    .size(Dshm.spacing.topBarIcon),
            )
        }
    }
}

private val SHOT_HEIGHT = 168.dp
