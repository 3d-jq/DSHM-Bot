package com.dshm.bot.ui.home

import com.dshm.bot.domain.model.AvatarGlyph

/** 主界面一行（Bot 或群聊）的展示模型，已按界面需要格式化好。 */
data class ChatRowUi(
    val id: String,
    val name: String,
    val preview: String,
    val timeLabel: String,
    val inkId: String?,
    val glyph: AvatarGlyph,
    val pinned: Boolean,
    val isGroup: Boolean,
    val unreadCount: Int,
    val avatarImage: String? = null,
    val avatarShape: String? = null,
    /** 群聊成员头像信息（主页/群面板用）；单 Bot 为空。 */
    val roomMembers: List<RoomMemberUi> = emptyList(),
    /** 群管理员 id；单 Bot 为 null。 */
    val roomAdmin: String? = null,
    /** 实时状态：`running` 干活中 / `waiting` 等你批准 / null 空闲（主页行的头像状态环）。 */
    val activity: String? = null,
    /** 是不是主 Bot（长按菜单据此显示「设为主 Bot / 取消主 Bot」）。 */
    val isMain: Boolean = false,
)

/** 真值已下沉到 `domain/model/RoomMemberUi.kt`（domain 反向依赖 UI 的层次倒置）。
 *  旧 import 路径（`data/remote` 等仍在用）经这里再导出，迁移完即可删除。 */
typealias RoomMemberUi = com.dshm.bot.domain.model.RoomMemberUi

/** 团队里的一个群聊（事件行认群用：`阅读群聊` 要画成员头像堆叠）。 */
data class TeamRoomUi(
    val id: String,
    val name: String,
    val members: List<RoomMemberUi>,
)

/** 主界面状态：单一数据源，UI 只渲染，不判断业务。 */
data class HomeUiState(
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val query: String = "",
    val rows: List<ChatRowUi> = emptyList(),
) {
    val unreadTotal: Int get() = rows.sumOf { it.unreadCount }
    val showEmptyState: Boolean get() = !isLoading && !hasError && rows.isEmpty()
    val showNoResults: Boolean get() = showEmptyState && query.isNotBlank()
}
