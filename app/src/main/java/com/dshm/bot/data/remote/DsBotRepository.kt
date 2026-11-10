package com.dshm.bot.data.remote

import com.dshm.bot.domain.model.BotSummary
import com.dshm.bot.domain.repository.BotRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** 真实 DS Bot 数据源：`/api/bot` 的 `state` + `/api/bot/feed` 的 revision/live。 */
class DsBotRepository(
    private val client: DshClient,
    private val cache: BotCache? = null,
    private val previewCache: BotPreviewCache? = null,
) : BotRepository {

    override fun observeBots(): Flow<List<BotSummary>> = flow {
        // 冷启动先给上次的团队快照，界面不用一直转圈等引擎。
        cache?.load()?.takeIf { it.isNotEmpty() }?.let { emit(it) }
        emit(fetchWithRetry())
        client.feed().collect { event ->
            when (event.event) {
                // 模型的新输出 —— 记下来当这个 Bot 的预览（不用再问引擎）。
                EVENT_MESSAGE -> event.data.let(::previewFromFeed)?.let { (botId, text) ->
                    previews[botId] = BotPreview.oneLine(text)
                    previewCache?.save(previews)
                }

                EVENT_HELLO, EVENT_REVISION, EVENT_LIVE -> emit(fetchWithRetry())
            }
        }
    }.conflate().flowOn(Dispatchers.IO)

    /**
     * 从 feed 的 `message` 事件里取「哪个 Bot 的哪条模型输出」。
     *
     * 事件形状（`host/feed.js`）：`{botId, sessionId, item:{kind,text}}`。
     * 只有模型输出（kind = "bot"）才算预览 —— 用户自己说的话不算。
     */
    private fun previewFromFeed(data: String): Pair<String, String>? {
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return null
        val botId = json.optString("botId").ifEmpty { return null }
        val item = json.optJSONObject("item") ?: return null
        if (item.optString("kind") != "bot") return null
        val text = item.optString("text").trim()
        return if (text.isEmpty()) null else botId to text
    }

    suspend fun fetch(): List<BotSummary> {
        return coroutineScope {
            // 预览要问 `chat`，那是另一跳往返：用上一次的 Bot 名单先把预览请求发出去，
            // 和 `state` 并行跑，首屏就不用等「先 usage 再 chat」两跳。
            val cachedIds = cache?.load().orEmpty()
                .filter { !it.isGroup }
                .map { it.id }
            val stateDeferred = async { client.bot("state") }
            val previewDeferred = if (cachedIds.isEmpty()) {
                null
            } else {
                async { fillPreviews(cachedIds) }
            }
            val state = stateDeferred.await()
            cache?.save(state)
            val bots = DsBotParser.parseState(state)
            previewDeferred?.await()
            withPreviews(bots)
        }
    }

    /**
     * 给每个 Bot 补上「大模型最新输出」的预览。
     *
     * 引擎的 `state` 只给 Bot 一个 `role`（角色描述），没有消息预览 ——
     * 群聊才有 `preview`。Bot 的预览有两个来源：
     * 1. feed 推来的新消息（实时，零额外请求）；
     * 2. 冷启动时问一次 `chat` 拿最后一页（并行，Bot 多也不卡）。
     */
    private suspend fun withPreviews(bots: List<BotSummary>): List<BotSummary> =
        coroutineScope {
            val missing = bots.filter { !it.isGroup && previews[it.id].isNullOrBlank() }
            fillPreviews(missing.map { it.id })
            previewCache?.save(previews)
            bots.map { bot ->
                if (bot.isGroup) return@map bot
                val preview = previews[bot.id]
                if (preview.isNullOrBlank()) {
                    // 还没有模型输出 —— 显示角色描述，别留空。
                    bot
                } else {
                    bot.copy(preview = preview)
                }
            }
        }

    /** 并行问 `chat` 补预览；已经有缓存的直接跳过。 */
    private suspend fun fillPreviews(botIds: List<String>) = coroutineScope {
        val missing = botIds.filter { previews[it].isNullOrBlank() }
        if (missing.isEmpty()) return@coroutineScope
        missing.map { id ->
                async {
                    val preview = runCatching {
                        BotPreview.fromChatPage(client.bot("chat", JSONObject().put("botId", id)))
                    }.getOrNull()
                    if (!preview.isNullOrBlank()) previews[id] = BotPreview.oneLine(preview)
                }
            }.awaitAll()
    }

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

    /** 每个 Bot 最新的模型输出（预览）。feed 推来的消息会更新它。 */
    private val previews = ConcurrentHashMap<String, String>(
        previewCache?.load().orEmpty(),
    )

    private companion object {
        const val EVENT_MESSAGE = "message"
        const val EVENT_HELLO = "hello"
        const val EVENT_REVISION = "revision"
        const val EVENT_LIVE = "live"
        const val MAX_FETCH_ATTEMPTS = 3
        const val FETCH_RETRY_DELAY_MS = 500L
    }
}
