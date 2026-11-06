package com.dshm.bot.data.remote

import com.dshm.bot.domain.model.BotSummary
import com.dshm.bot.domain.repository.BotRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject

/** 真实 DS Bot 数据源：`/api/bot` 的 `state` + `/api/bot/feed` 的 revision/live。 */
class DsBotRepository(
    private val client: DshClient,
    private val cache: BotCache? = null,
) : BotRepository {

    override fun observeBots(): Flow<List<BotSummary>> = flow {
        // 冷启动先给上次的团队快照，界面不用一直转圈等引擎。
        cache?.load()?.takeIf { it.isNotEmpty() }?.let { emit(it) }
        emit(fetchWithRetry())
        client.feed().collect { event ->
            if (event.event == EVENT_HELLO || event.event == EVENT_REVISION || event.event == EVENT_LIVE) {
                emit(fetchWithRetry())
            }
        }
    }.conflate().flowOn(Dispatchers.IO)

    suspend fun fetch(): List<BotSummary> {
        val state = client.bot("state")
        cache?.save(state)
        return DsBotParser.parseState(state)
    }

    /**
     * 移动端能力协商。offeredApi 为空时不声明版本，由服务端选默认版本，
     * 避免客户端写死协议号；[DsBotHello.api] 为 null 表示版本不兼容。
     */
    suspend fun hello(offeredApi: List<Int>? = null): DsBotHello {
        val payload = JSONObject()
        offeredApi?.let {
            payload.put("api", JSONArray().apply { it.forEach { version -> put(version) } })
        }
        return DsBotHello.fromJson(client.bot("hello", payload))
    }

    /** 断线补读通知；boot / since 用上次 hello 或 notices 返回的游标。 */
    suspend fun notices(boot: String? = null, since: Long? = null): DsBotNoticePage {
        val payload = JSONObject()
        boot?.let { payload.put("boot", it) }
        since?.let { payload.put("since", it) }
        return DsBotNoticePage.fromJson(client.bot("notices", payload))
    }

    /**
     * 引擎刚启动时 `state` 可能还没就绪（连接被拒 / 500）。有限重试让冷启动自愈；
     * 重试耗尽后仍把异常抛给 flow，界面的错误态语义不变，不吞掉真实故障。
     */
    private suspend fun fetchWithRetry(): List<BotSummary> {
        var lastFailure: Throwable? = null
        for (attempt in 0 until MAX_FETCH_ATTEMPTS) {
            val result = runCatching { fetch() }
            if (result.isSuccess) return result.getOrThrow()
            lastFailure = result.exceptionOrNull()
            if (attempt < MAX_FETCH_ATTEMPTS - 1) delay(FETCH_RETRY_DELAY_MS)
        }
        throw lastFailure ?: IllegalStateException("拉取 Bot 列表失败")
    }

    private companion object {
        const val EVENT_HELLO = "hello"
        const val EVENT_REVISION = "revision"
        const val EVENT_LIVE = "live"
        const val MAX_FETCH_ATTEMPTS = 3
        const val FETCH_RETRY_DELAY_MS = 500L
    }
}
