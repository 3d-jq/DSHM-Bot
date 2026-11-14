package com.dshm.bot.domain.model

import androidx.compose.runtime.Immutable

/**
 * 群聊成员的头像信息（id / 名字 / 颜色 / 头像 / 角色形状）。
 *
 * 原本定义在 `ui/home`，但 [BotSummary]（domain）直接引用它 —— 领域模型反向
 * 依赖 UI 层是层次倒置，所以把真值下沉到这里；`ui/home/HomeUiState.kt` 里的
 * typealias 是迁移期的再导出（`data/remote` 等旧 import 路径仍可用），
 * 后续批次把旧 import 换掉后即可删除。
 */
@Immutable
data class RoomMemberUi(
    val id: String,
    val name: String,
    val inkId: String?,
    val avatarImage: String?,
    val avatarShape: String?,
    val role: String? = null,
)
