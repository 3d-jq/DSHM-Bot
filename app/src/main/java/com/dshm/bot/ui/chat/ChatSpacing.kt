package com.dshm.bot.ui.chat

import androidx.compose.ui.unit.Dp
import com.dshm.bot.core.design.DshmSpacing

/** 一条对话条目的间距与分组结果。 */
data class ChatItemSpacing(
    /** 与**上方**那条的距离。 */
    val top: Dp,
    /** 群聊里这条是不是一组的开头（要显示头像 + 名字）。 */
    val groupStart: Boolean,
    /** 群聊里这条和上方那条是同一个 Bot（组内第二条起）。 */
    val sameBotAsOlder: Boolean,
)

/**
 * 一条气泡和它**上方**那条的关系（间距 / 分组）。
 *
 * 对齐 DS Bot `stackReplies` 与既有的间距约定：
 * - 换人（用户 ↔ Bot）：12dp（`turnGap`）；
 * - 群聊里同一 Bot 连续回复算一组：组内 4dp（`xs`），组首要显示头像 + 名字；
 * - 产物卡自带标题：12dp；
 * - 其余（同一个人接着说）：4dp。
 *
 * 流式气泡 / 思考行也用这个函数 —— 它们「将要成为」一条 BOT 条目，用同一套规则算，
 * 落地时才不会因为间距从 8dp 变成 12dp 而抖一下。
 *
 * 纯函数（`Dshm.spacing` 是 @Composable 取不到），所以间距令牌按参数传，方便单测。
 */
fun chatItemSpacing(
    spacing: DshmSpacing,
    entryKind: ChatEntryKind,
    entryBotId: String?,
    olderKind: ChatEntryKind?,
    olderBotId: String?,
    isGroup: Boolean,
): ChatItemSpacing {
    // 换人：跟上面那条比 —— top padding 决定的正是它和上方气泡的距离。
    val turnsChanged = olderKind != null && olderKind != entryKind &&
        (olderKind == ChatEntryKind.USER || entryKind == ChatEntryKind.USER)
    // 群聊里同一 bot 连续的多条回复合成一组（对齐 DS Bot `stackReplies`）。
    val sameBotAsOlder = isGroup && entryKind == ChatEntryKind.BOT && entryBotId != null &&
        olderKind == ChatEntryKind.BOT && olderBotId == entryBotId
    val groupStart = isGroup && entryKind == ChatEntryKind.BOT && !sameBotAsOlder
    val top = if (groupStart || turnsChanged || entryKind == ChatEntryKind.ARTIFACTS) {
        spacing.turnGap
    } else {
        spacing.xs
    }
    return ChatItemSpacing(top = top, groupStart = groupStart, sameBotAsOlder = sameBotAsOlder)
}
