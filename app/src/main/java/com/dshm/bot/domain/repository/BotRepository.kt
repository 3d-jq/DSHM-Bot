package com.dshm.bot.domain.repository

import com.dshm.bot.domain.model.BotSummary
import kotlinx.coroutines.flow.Flow

/**
 * Bot 团队列表的数据源。
 *
 * 当前只有演示实现；下一个里程碑接 DSH 主机后，由 `DsBotRepository`
 * （DS Bot `state` + SSE feed）实现同一个接口，界面与状态层不需要改动。
 */
interface BotRepository {
    fun observeBots(): Flow<List<BotSummary>>
}
