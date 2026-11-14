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

/** 真实 DS Bot 数据源：`/api/bot` 的 `state` + `/api/bot/feed` 的 revision/live。 */
class DsBotRepository(
    private val client: DshClient,
    private val cache: BotCache? = null,
    private val previewCache: BotPreviewCache? = null,
) : BotRepository {

    override fun observeBots(): Flow<List<BotSummary>> = flow {
        // 冷启动先给上次的团队快照，界面不用一直转圈等引擎。
        //
        // ⚠️ 必须把持久化的预览一起贴回去：快照里 `bots[].preview` 是 `state` 的
        // role（角色描述），直接 emit 会把 `HomeViewModel` 用预览缓存拼好的那一版
        // 顶掉 —— 表现就是「界面早就画好了，预览却先显示角色标签、过一会儿才跳回来」。
        cache?.load()?.takeIf { it.isNotEmpty() }?.let { emit(applyKnownPreviews(it, previews.snapshot())) }
        var current = fetchWithRetry()
        emit(current)
        // 首屏已经上屏了；预览（每 Bot 一次 chat 往返）留在这儿补，补到变化再推一版。
        // 冷启动时这几跳都不该挡住列表 —— 用户看到的应该是「列表先出来，预览随后填上」。
        runCatching { fillMissingPreviews(current) }.getOrNull()?.let { patched ->
            current = patched
            emit(patched)
        }
        client.feed().collect { event ->
            when (event.event) {
                // 模型的新输出 —— 记下来当这个 Bot 的预览，并**立刻**推给界面。
                //
                // 以前这里只写本地表、不发新列表，主页要等下一次 `revision`
                // （引擎保存团队状态时才发）才重新拉一遍 —— 表现为「答案早就出来了，
                // 预览还是上一轮」。feed 已经把文本送到了，就地打补丁零网络。
                EVENT_MESSAGE -> event.data.let(::previewFromFeed)?.let { (botId, text) ->
                    val preview = BotPreview.oneLine(text)
                    if (previews.get(botId) != preview) {
                        previews.put(botId, preview)
                        previewCache?.save(previews.snapshot(), liveBotIds(current))
                        patchBotPreview(current, botId, preview)?.let { patched ->
                            current = patched
                            emit(patched)
                        }
                    }
                }

                EVENT_HELLO, EVENT_REVISION, EVENT_LIVE -> {
                    current = fetchWithRetry()
                    emit(current)
                }
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

    /**
     * 团队列表（只要 `state`）。
     *
     * **不等预览**：预览是每个 Bot 一次 `chat` 往返，冷启动时引擎刚起、每次 RPC 都慢，
     * 串在首屏后面就是「列表转半天」。首屏只贴本地缓存里的预览，缺的由
     * [fillMissingPreviews] 在首屏画出来之后再补（见 `observeBots`）。
     */
    suspend fun fetch(): List<BotSummary> {
        val state = client.bot("state")
        cache?.save(state)
        return withPreviews(DsBotParser.parseState(state))
    }

    /**
     * 补齐缺失的预览（每个 Bot 一次 `chat` 往返），返回打上补丁的新列表；
     * 没有任何变化时返回 null（调用方据此决定要不要再推一版）。
     */
    suspend fun fillMissingPreviews(bots: List<BotSummary>): List<BotSummary>? {
        val missing = bots.filter { !it.isGroup }.map { it.id }.filter { previews.get(it).isNullOrBlank() }
        if (missing.isEmpty()) return null
        fillPreviews(missing)
        var changed = false
        val patched = bots.map { bot ->
            val preview = previews.get(bot.id)
            val next = if (!bot.isGroup && !preview.isNullOrBlank() && preview != bot.preview) {
                changed = true
                bot.copy(preview = preview)
            } else {
                bot
            }
            next
        }
        return if (changed) patched else null
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
            val missing = bots.filter { !it.isGroup && previews.get(it.id).isNullOrBlank() }
            fillPreviews(missing.map { it.id })
            previewCache?.save(previews.snapshot(), liveBotIds(bots))
            bots.map { bot ->
                if (bot.isGroup) return@map bot
                val preview = previews.get(bot.id)
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
        val missing = botIds.filter { previews.get(it).isNullOrBlank() }
        if (missing.isEmpty()) return@coroutineScope
        missing.map { id ->
                async {
                    val preview = runCatching {
                        BotPreview.fromChatPage(client.bot("chat", JSONObject().put("botId", id)))
                    }.getOrNull()
                    if (!preview.isNullOrBlank()) previews.put(id, BotPreview.oneLine(preview))
                }
            }.awaitAll()
    }

    /** 还活着的单 Bot id（群聊预览以 `state.rooms[].preview` 为准，不进预览缓存）。 */
    private fun liveBotIds(bots: List<BotSummary>): Set<String> =
        bots.filter { !it.isGroup }.map { it.id }.toSet()

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

    /**
     * 每个 Bot 最新的模型输出（预览）。feed 推来的消息会更新它。
     * LRU 上限 + 持久化按 [BotPreviewStore.prune] 清理（删 Bot 清条目、容量截断）。
     */
    private val previews = LruPreviewTable(
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

/**
 * 把一条新预览就地打进当前列表（feed `message` 事件用）。
 *
 * 只改单 Bot 行：群聊预览以 `state.rooms[].preview` 为准，不在这里混。
 * 返回 null 表示这一行没变化，调用方就不用重复 emit（省一次整表重组）。
 */
internal fun patchBotPreview(
    bots: List<BotSummary>,
    botId: String,
    preview: String,
): List<BotSummary>? {
    var changed = false
    val patched = bots.map { bot ->
        if (!bot.isGroup && bot.id == botId && bot.preview != preview) {
            changed = true
            bot.copy(preview = preview)
        } else {
            bot
        }
    }
    return if (changed) patched else null
}

/**
 * 把已知的预览（持久化 + feed 累积）贴到名单上。
 *
 * 冷启动的名单快照来自 `state`，单 Bot 的 `preview` 字段是 role（角色描述）——
 * 不贴回来的话，界面会先把画好的对话预览顶成角色标签，等 `chat` 回来再跳一次。
 */
internal fun applyKnownPreviews(
    bots: List<BotSummary>,
    previews: Map<String, String>,
): List<BotSummary> = bots.map { bot ->
    if (bot.isGroup) {
        bot
    } else {
        previews[bot.id]?.takeIf { it.isNotBlank() }?.let { bot.copy(preview = it) } ?: bot
    }
}
