package com.dshm.bot.data.remote

import com.dshm.bot.domain.model.BotSummary
import com.dshm.bot.domain.repository.BotRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach

/** 真实 DS Bot 数据源：`/api/bot` 的 `state` + `/api/bot/feed` 的 revision/live。 */
class DsBotRepository(private val client: DshClient) : BotRepository {

    override fun observeBots(): Flow<List<BotSummary>> = flow {
        emit(fetch())
        client.feed().collect { event ->
            if (event.event == "hello" || event.event == "revision" || event.event == "live") {
                emit(fetch())
            }
        }
    }.conflate().flowOn(Dispatchers.IO)

    suspend fun fetch(): List<BotSummary> =
        DsBotParser.parseState(client.bot("state"))
}
