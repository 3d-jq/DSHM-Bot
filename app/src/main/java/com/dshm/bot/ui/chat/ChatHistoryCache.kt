package com.dshm.bot.ui.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.edit
import java.util.concurrent.Executors

/**
 * 对话首屏缓存：引擎的 `chat` / `room-chat` 返回里能回放的 items。
 *
 * 热启动先用它把上次的消息画出来，随后再向引擎静默对齐；不缓存运行中的临时状态
 * （流式气泡、审批卡、按钮状态），避免把「正在跑」错误地带回新进程。
 *
 * 字节记账与裁剪的纯逻辑在 [HistoryMemory]（无 Android 依赖，JVM 可测）；
 * 序列化 + 写 SharedPreferences 排到后台守护线程，调用线程只动内存副本。
 */
internal class ChatHistoryCache(context: Context, botId: String) {
    private val prefs = context.applicationContext
        .getSharedPreferences("bot-chat-history:$botId", Context.MODE_PRIVATE)
    private val memory = HistoryMemory(
        initialRaw = prefs.getString(KEY_ITEMS, null),
        initialSessionId = prefs.getString(KEY_SESSION, null).orEmpty(),
    )

    /** 落盘队列：单条守护线程按提交顺序写，快照已在调用线程拷好，序列化在后台做。 */
    private val saveExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "chat-history-save").apply { isDaemon = true }
    }

    fun load(): HistoryMemory.Snapshot? = memory.load()

    fun save(response: JSONObject) {
        val snapshot = memory.replaceAll(
            incoming = response.optJSONArray("items") ?: JSONArray(),
            sessionId = response.optString("sessionId"),
        )
        persistAsync(snapshot)
    }

    /**
     * 一条新消息落地时**就地追加**（feed `message` 用）。
     *
     * 这是冷启动快照能跟上的关键：以前 `save` 只在完整重拉时写，
     * 追加消息必须等下一次 `load()` 才进快照 —— 于是「回复完就退 App、冷启动」
     * 读到的是旧快照，重新进一次对话才变新。
     */
    fun appendItem(item: JSONObject) {
        persistAsync(memory.append(item))
    }

    private fun persistAsync(snapshot: HistoryMemory.PersistSnapshot) {
        saveExecutor.execute {
            runCatching {
                prefs.edit {
                    putString(KEY_ITEMS, snapshot.items)
                    putString(KEY_SESSION, snapshot.sessionId)
                }
            }
        }
    }

    private companion object {
        const val KEY_ITEMS = "items"
        const val KEY_SESSION = "session"
    }
}

/**
 * 对话历史内存侧的纯逻辑：items + 每条的序列化长度，增量维护字节数。
 *
 * 追加/去重/裁剪都不再整表 `toString()` 量长度（以前 `keepNewest` 每条消息
 * 落地都要全量序列化一次，主线程 1MB 级开销）；每条的长度只在入表时算一次。
 *
 * @param initialRaw 首次构造时读到的磁盘快照（超 [maxChars] 整体放弃，同旧行为）。
 * @param maxChars 内存副本的字节数上限；超出时从最老的开始丢（保留最新的一段）。
 */
internal class HistoryMemory(
    initialRaw: String?,
    initialSessionId: String,
    private val maxChars: Int = MAX_CHARS,
) {
    private val lock = Any()
    /** 时间顺序的内存副本。 */
    private val items = ArrayList<JSONObject>()
    /** 与 [items] 对齐的每条序列化长度。 */
    private val itemChars = ArrayList<Int>()
    private var totalChars = 0
    private var sessionId = initialSessionId

    init {
        if (initialRaw != null && initialRaw.length <= maxChars) {
            runCatching {
                val array = JSONArray(initialRaw)
                synchronized(lock) {
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.let(::addToMemoryLocked)
                    }
                }
            }
        }
    }

    /** 首屏快照；空副本回 null（冷启动走骨架屏，同旧行为）。 */
    fun load(): Snapshot? = runCatching {
        val snapshot = synchronized(lock) { items.toList() }
        if (snapshot.isEmpty()) return null
        Snapshot(
            items = JSONArray().apply { snapshot.forEach { put(it) } },
            sessionId = sessionId.takeIf { it.isNotEmpty() },
        )
    }.getOrNull()

    /** 完整重拉：整表替换，返回要落盘的快照。 */
    fun replaceAll(incoming: JSONArray, sessionId: String): PersistSnapshot {
        val snapshot = synchronized(lock) {
            items.clear()
            itemChars.clear()
            totalChars = 0
            for (index in 0 until incoming.length()) {
                incoming.optJSONObject(index)?.let(::addToMemoryLocked)
            }
            trimOldestLocked()
            this.sessionId = sessionId
            snapshotLocked()
        }
        return snapshot
    }

    /** 追加一条（同 id / 同 seq 的旧条先删掉，LazyColumn 的 key 不能重复）。 */
    fun append(item: JSONObject): PersistSnapshot {
        val snapshot = synchronized(lock) {
            val id = item.optString("id")
            val seq = item.optLong("seq", -1L)
            // 被删条目的长度直接从增量计数里扣，不用重新序列化。
            var at = items.size - 1
            while (at >= 0) {
                val existing = items[at]
                val sameId = id.isNotEmpty() && existing.optString("id") == id
                val sameSeq = seq >= 0 && existing.optLong("seq", -1L) == seq
                if (sameId || sameSeq) {
                    totalChars -= itemChars.removeAt(at)
                    items.removeAt(at)
                }
                at -= 1
            }
            addToMemoryLocked(item)
            trimOldestLocked()
            snapshotLocked()
        }
        return snapshot
    }

    /** 记一条进内存副本：长度只在入表时算一次。 */
    private fun addToMemoryLocked(item: JSONObject) {
        val chars = item.toString().length
        items.add(item)
        itemChars.add(chars)
        totalChars += chars
    }

    /** 超出上限时从最老的开始丢，保留至少最新一条。 */
    private fun trimOldestLocked() {
        while (totalChars > maxChars && items.size > 1) {
            totalChars -= itemChars.removeAt(0)
            items.removeAt(0)
        }
    }

    /** 当前内存副本的落盘快照（序列化交给调用方，通常在后台线程做）。 */
    private fun snapshotLocked(): PersistSnapshot =
        PersistSnapshot(
            items = JSONArray().apply { items.forEach { put(it) } }.toString(),
            sessionId = sessionId,
        )

    /** 首屏回放的快照。 */
    data class Snapshot(
        val items: JSONArray,
        val sessionId: String?,
    )

    /** 写盘快照：整表 JSON 字符串 + 会话 id。 */
    data class PersistSnapshot(
        val items: String,
        val sessionId: String,
    )

    companion object {
        /** 上限放宽到 1 MB（SharedPreferences 存得下），超出的按「保留最新」裁掉。 */
        const val MAX_CHARS = 1024 * 1024
    }
}
