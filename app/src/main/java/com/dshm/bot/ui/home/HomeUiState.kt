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
