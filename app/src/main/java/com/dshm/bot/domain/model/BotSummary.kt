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
    /** DS Bot `avatar.image`（data URL）；有则优先显示。 */
    val avatarImage: String? = null,
    /** DS Bot 角色形状（`whale` 等）。 */
    val avatarShape: String? = null,
    /** 群聊成员头像信息；单 Bot 为空。 */
    val roomMembers: List<RoomMemberUi> = emptyList(),
    /** 群管理员 id；单 Bot 为空。 */
    val roomAdmin: String? = null,
    /** 是不是主 Bot（DS Bot `mainBotIds`，可以同时有多个）。 */
    val isMain: Boolean = false,
    /**
     * 实时状态（`state.work`）：`running` 正在干活 / `waiting` 等你批准 / null 空闲。
     * 主页行据此画头像状态环（对齐 DS Bot 侧栏的状态点）。
     */
    val activity: String? = null,
)
