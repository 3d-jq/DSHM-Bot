package com.dshm.bot.ui.chat

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.BotExchangeEntry
import com.dshm.bot.data.remote.BotExchangeText
import com.dshm.bot.data.remote.DshClients
import com.dshm.bot.data.remote.parseBotExchange
import com.dshm.bot.data.runtime.RuntimeEndpoint
import com.dshm.bot.domain.model.AvatarGlyph
import com.dshm.bot.domain.model.BotInks
import com.dshm.bot.ui.components.BotAvatar
import com.dshm.bot.ui.home.RoomMemberUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Date
import com.dshm.bot.ui.components.rememberWindowHeightDp

/** 两条 Bot 消息之间超过这个间隔就插一行日期（DS Bot `ExchangeDialog` 同款）。 */
private const val DAY_GAP_MS = 30 * 60 * 1000L

/**
 * DS Bot 的「Bot 间对话」（`src/client/exchange.js` 的 `ExchangeDialog`）。
 *
 * 打开时拉 `exchange {a,b}`（主机记下的每一次 Bot→Bot 投递），每 2 秒刷新一次；
 * 长消息带 `length`，点「显示全部」再走 `exchange-text` 取全文。
 *
 * 壳子用底部面板（占屏幕 80%），和本 App 其它面板一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotExchangeDialog(
    selfId: String,
    otherId: String,
    endpoint: RuntimeEndpoint?,
    selfLook: RoomMemberUi?,
    otherLook: RoomMemberUi?,
    fontScale: Float,
    onDismiss: () -> Unit,
) {
    val client = remember(endpoint) { endpoint?.let { DshClients.of(it) } }
    val scope = rememberCoroutineScope()
    var entries by remember(selfId, otherId) { mutableStateOf<List<BotExchangeEntry>?>(null) }
    var error by remember(selfId, otherId) { mutableStateOf<String?>(null) }
    var expanded by remember(selfId, otherId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetHeight = rememberWindowHeightDp() * 0.8f

    LaunchedEffect(selfId, otherId, client) {
        if (client == null) {
            entries = emptyList()
            return@LaunchedEffect
        }
        while (true) {
            try {
                val value = client.botValue(
                    "exchange",
                    JSONObject().put("a", selfId).put("b", otherId),
                )
                entries = parseBotExchange(value)
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "加载失败"
            }
            delay(2_000)
        }
    }

    fun showWhole(entry: BotExchangeEntry) {
        if (expanded.containsKey(entry.id)) return
        scope.launch {
            val text = try {
                BotExchangeText.fromJson(
                    client?.botValue("exchange-text", JSONObject().put("id", entry.id)),
                )?.text
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (text != null) expanded = expanded + (entry.id to text)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Dshm.colors.pageBackground,
        shape = RoundedCornerShape(topStart = Dshm.radius.xl, topEnd = Dshm.radius.xl),
        scrimColor = Dshm.colors.scrim,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .navigationBarsPadding(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                ExchangePairPill(self = selfLook, other = otherLook, selfId = selfId, otherId = otherId)
            }
            when {
                entries == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Dshm.colors.accent, modifier = Modifier.size(Dshm.spacing.xxl))
                }

                entries.isNullOrEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = error ?: stringResource(R.string.exchange_empty),
                        style = Dshm.typography.rowPreview,
                        color = if (error != null) Dshm.colors.danger else Dshm.colors.textSecondary,
                    )
                }

                else -> ExchangeLog(
                    entries = requireNotNull(entries),
                    looks = listOfNotNull(selfLook, otherLook).associateBy { it.id },
                    expanded = expanded,
                    fontScale = fontScale,
                    onShowWhole = ::showWhole,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 顶栏胶囊：`[头像] A ⇄ [头像] B`。 */
@Composable
private fun ExchangePairPill(
    self: RoomMemberUi?,
    other: RoomMemberUi?,
    selfId: String,
    otherId: String,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Dshm.radius.pill))
            .background(Dshm.colors.surface)
            .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        self?.let { ExchangeAvatar(it, Dshm.spacing.icon) }
        Text(
            text = self?.name ?: selfId,
            style = Dshm.typography.settingsRowTitle,
            color = Dshm.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Dshm.spacing.xs),
        )
        Text(text = "⇄", style = Dshm.typography.settingsRowTitle, color = Dshm.colors.textTertiary)
        Text(
            text = other?.name ?: otherId,
            style = Dshm.typography.settingsRowTitle,
            color = Dshm.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Dshm.spacing.xs),
        )
        other?.let { ExchangeAvatar(it, Dshm.spacing.icon) }
    }
}

@Composable
private fun ExchangeAvatar(member: RoomMemberUi, size: androidx.compose.ui.unit.Dp) {
    BotAvatar(
        inkId = member.inkId,
        glyph = AvatarGlyph.BOT,
        avatarImage = member.avatarImage,
        avatarShape = member.avatarShape,
        size = size,
    )
}

@Composable
private fun ExchangeLog(
    entries: List<BotExchangeEntry>,
    looks: Map<String, RoomMemberUi>,
    expanded: Map<String, String>,
    fontScale: Float,
    onShowWhole: (BotExchangeEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Dshm.spacing.lg,
            end = Dshm.spacing.lg,
            top = Dshm.spacing.sm,
            bottom = Dshm.spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Dshm.spacing.md),
    ) {
        itemsIndexed(entries) { index, entry ->
            val previous = entries.getOrNull(index - 1)
            if (previous == null || entry.timeMs - previous.timeMs > DAY_GAP_MS) {
                Text(
                    text = DateFormat.getMediumDateFormat(context).format(Date(entry.timeMs)),
                    style = Dshm.typography.rowTime,
                    color = Dshm.colors.textTertiary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val look = looks[entry.from]
            val name = look?.name ?: entry.from
            val body = expanded[entry.id] ?: entry.text
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                look?.let { ExchangeAvatar(it, Dshm.spacing.avatarGlyph + Dshm.spacing.xs) }
                Spacer(Modifier.width(Dshm.spacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = Dshm.typography.rowPreview,
                        color = look?.let { Color(BotInks.color(it.inkId, Dshm.colors.isDark)) }
                            ?: Dshm.colors.textSecondary,
                    )
                    Spacer(Modifier.size(Dshm.spacing.xxs))
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Dshm.radius.md))
                            .background(Dshm.colors.bubbleAssistant)
                            .padding(horizontal = Dshm.spacing.md, vertical = Dshm.spacing.sm),
                    ) {
                        MarkdownText(markdown = body, fontScale = fontScale)
                        val truncated = entry.length != null && expanded[entry.id] == null
                        if (truncated) {
                            TextButton(onClick = { onShowWhole(entry) }) {
                                Text(
                                    text = stringResource(R.string.exchange_show_all, entry.length ?: 0),
                                    style = Dshm.typography.rowTime,
                                    color = Dshm.colors.accent,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
