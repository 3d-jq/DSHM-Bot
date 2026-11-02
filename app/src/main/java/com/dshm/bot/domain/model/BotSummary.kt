package com.dshm.bot.domain.model

import java.time.Instant

/** 头像占位图形。真实 Bot 头像（DS Bot 的 3D 角色或用户照片）接入后由服务端下发。 */
enum class AvatarGlyph {
    BOT,
    WORK,
    BOOK,
    CODE,
    SEARCH,
    GROUP,
    SPARK,
}

/**
 * 列表里的一个 Bot 或群聊。
 *
 * 这是 DS Bot 团队视图的最小投影：名字、颜色、最后一句、时间、未读。
 * 服务端真实数据由后续里程碑的 `BotRepository` 实现提供（DS Bot `state` 接口）。
 */
data class BotSummary(
    val id: String,
    val name: String,
    val preview: String,
    val lastActiveAt: Instant,
    val inkId: String? = null,
    val glyph: AvatarGlyph = AvatarGlyph.BOT,
    val unreadCount: Int = 0,
    val pinned: Boolean = false,
    val isGroup: Boolean = false,
)
