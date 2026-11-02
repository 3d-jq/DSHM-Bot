package com.dshm.bot.data.demo

import com.dshm.bot.domain.model.AvatarGlyph
import com.dshm.bot.domain.model.BotSummary
import com.dshm.bot.domain.repository.BotRepository
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 演示数据源：让主界面在接入 DSH 之前就能开发与验收。
 * 数据只用于界面，不写入任何存储；接入真实数据后整体替换。
 */
class DemoBotRepository(private val clock: Clock = Clock.systemDefaultZone()) : BotRepository {
    override fun observeBots(): Flow<List<BotSummary>> = flowOf(DemoBots.create(clock.instant()))
}

object DemoBots {
    private const val MINUTE = 1L
    private const val DAY = 24L * 60L

    fun create(now: Instant): List<BotSummary> {
        fun ago(minutes: Long): Instant = now.minus(minutes * MINUTE, ChronoUnit.MINUTES)

        return listOf(
            BotSummary(
                id = "demo-office",
                name = "办公专家",
                preview = "Word 版做好了，心得约 668 个汉字，超…",
                lastActiveAt = ago(50),
                inkId = "blue",
                glyph = AvatarGlyph.WORK,
            ),
            BotSummary(
                id = "demo-mint",
                name = "薄荷",
                preview = "如果你说的是 Nous Research 的 Hermes…",
                lastActiveAt = ago(5 * 60),
                inkId = "red",
                glyph = AvatarGlyph.BOT,
            ),
            BotSummary(
                id = "demo-network",
                name = "计算机网络讲师",
                preview = "你好，Ren da，我是计算机网络讲师，很…",
                lastActiveAt = ago(6 * DAY),
                inkId = "gray",
                glyph = AvatarGlyph.BOOK,
            ),
            BotSummary(
                id = "demo-backend",
                name = "后端讲师",
                preview = "好，用 Qt 的 `QThreadPool + QtConcurr…",
                lastActiveAt = ago(6 * DAY + 30),
                inkId = "yellow",
                glyph = AvatarGlyph.CODE,
            ),
            BotSummary(
                id = "demo-chat",
                name = "闲聊群",
                preview = "我改成了简单文件名，重新发一份：[下…",
                lastActiveAt = ago(11 * DAY),
                inkId = "orange",
                glyph = AvatarGlyph.GROUP,
                isGroup = true,
            ),
            BotSummary(
                id = "demo-search",
                name = "搜索员",
                preview = "找到了金标联盟官方实际接入入口：开放…",
                lastActiveAt = ago(13 * DAY),
                inkId = "yellow",
                glyph = AvatarGlyph.SEARCH,
            ),
        )
    }
}
