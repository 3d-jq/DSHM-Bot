package com.dshm.bot.data.remote

import com.dshm.bot.domain.model.BotSummary
import com.dshm.bot.domain.repository.BotRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** 引擎未运行时的空数据源（不再使用演示 Bot）。 */
class EmptyBotRepository : BotRepository {
    override fun observeBots(): Flow<List<BotSummary>> = flowOf(emptyList())
}
