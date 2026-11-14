package com.dshm.bot.data.remote

import android.content.Context
import org.json.JSONObject
import androidx.core.content.edit

/**
 * 预览表的容量与清理规则（纯逻辑，JVM 单测直跑，不碰 Android）。
 *
 * 之前的缓存一个 prefs key 存全部预览：无条数上限、删除的 Bot 条目永不清理、
 * 每条 feed 消息全量重写整张表，表会无限膨胀。这里对齐 BotCache 的
 * MAX_CHARS 上限做法收紧三件事：
 * - [prune] 先按「还活着的 Bot」清条目 —— 删掉的 Bot 不再永久占位；
 * - 条数超过 [MAX_ENTRIES] 时丢最旧的一条（LRU，调用方按最旧在前给顺序）；
 * - 整表序列化估算超过 [MAX_CHARS] 时继续从最旧的丢（软上限，估算含引号/逗号开销）。
 */
internal object BotPreviewStore {

    /** 条数上限：256 个 Bot 的预览足够，再多多半是引擎残留。 */
    const val MAX_ENTRIES = 256

    /** 整表字符软上限（对齐 BotCache 的 MAX_CHARS 做法）。 */
    const val MAX_CHARS = 64 * 1024

    /**
     * 清理 + 截断：先按 [liveIds] 清条目（null = 不按活跃清单清），再从
     * [previews] 的**最旧条目在前**的顺序里丢到条数 / 字符上限以内。
     */
    fun prune(
        previews: Map<String, String>,
        liveIds: Set<String>?,
        maxEntries: Int = MAX_ENTRIES,
        maxChars: Int = MAX_CHARS,
    ): Map<String, String> {
        val entries = LinkedHashMap<String, String>()
        previews.forEach { (id, text) ->
            if (id.isNotBlank() && text.isNotBlank() && (liveIds == null || id in liveIds)) {
                entries[id] = BotPreview.oneLine(text)
            }
        }
        while (entries.size > maxEntries) {
            dropOldest(entries) ?: break
        }
        while (entries.isNotEmpty() && serializedChars(entries) > maxChars) {
            dropOldest(entries) ?: break
        }
        return entries
    }

    /** JSON 序列化后的近似字符数（引号、冒号、逗号加固定余量）。 */
    private fun serializedChars(entries: Map<String, String>): Int =
        entries.entries.sumOf { (id, text) -> id.length + text.length + 4 }

    private fun <K, V> dropOldest(entries: LinkedHashMap<K, V>): Boolean {
        val iterator = entries.keys.iterator()
        if (!iterator.hasNext()) return false
        iterator.next()
        iterator.remove()
        return true
    }
}

/**
 * DsBotRepository 内存里的预览表：LRU 上限的 LinkedHashMap（accessOrder）。
 *
 * feed 更新、chat 回填、界面读取来自不同协程 —— 统一加锁；快照按
 * 「最旧在前」给出去，[BotPreviewStore.prune] 据此淘汰最久没更新的条目。
 */
internal class LruPreviewTable(
    initial: Map<String, String> = emptyMap(),
    private val maxEntries: Int = BotPreviewStore.MAX_ENTRIES,
) {
    private val lock = Any()
    private val table = LinkedHashMap<String, String>(16, 0.75f, true)

    init {
        synchronized(lock) {
            initial.forEach { (id, text) ->
                if (id.isNotBlank() && text.isNotBlank()) table[id] = BotPreview.oneLine(text)
            }
        }
    }

    fun get(id: String): String? = synchronized(lock) { table[id] }

    fun put(id: String, text: String) {
        if (id.isBlank() || text.isBlank()) return
        synchronized(lock) {
            table[id] = BotPreview.oneLine(text)
            while (table.size > maxEntries) {
                if (!dropOldestUnsafe()) break
            }
        }
    }

    /** 快照（最旧在前，访问序）：给 save / prune 用。 */
    fun snapshot(): Map<String, String> = synchronized(lock) { LinkedHashMap(table) }

    private fun dropOldestUnsafe(): Boolean {
        val iterator = table.keys.iterator()
        if (!iterator.hasNext()) return false
        iterator.next()
        iterator.remove()
        return true
    }
}

/**
 * 单 Bot 的最新回复预览缓存。
 *
 * `state.rooms[]` 自带群聊预览，但 `state.bots[]` 只有 role；单 Bot 的预览是从
 * `chat` / feed 合成出来的。把它持久化后，热启动不必等 `usage + chat` 回填，
 * 也不会先显示角色标签、过一会儿才跳成对话预览。
 *
 * 持久化走 [BotPreviewStore.prune]：条数 LRU 上限 + 删除 Bot 清条目 + 整表字符软上限。
 */
class BotPreviewCache(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("dshm_bot_previews", Context.MODE_PRIVATE)

    fun load(): Map<String, String> = runCatching {
        val raw = prefs.getString(KEY_PREVIEWS, null) ?: return emptyMap()
        val parsed = buildMap {
            val json = JSONObject(raw)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) put(key, value)
            }
        }
        BotPreviewStore.prune(parsed, liveIds = null)
    }.getOrDefault(emptyMap())

    /** [liveIds] 传当前还活着的 Bot id（null = 只按容量截断）；被删的 Bot 条目就地清掉。 */
    fun save(previews: Map<String, String>, liveIds: Set<String>? = null) {
        runCatching {
            val kept = BotPreviewStore.prune(previews, liveIds)
            val json = JSONObject()
            kept.forEach { (id, text) -> json.put(id, text) }
            prefs.edit { putString(KEY_PREVIEWS, json.toString()) }
        }
    }

    private companion object {
        const val KEY_PREVIEWS = "previews"
    }
}
