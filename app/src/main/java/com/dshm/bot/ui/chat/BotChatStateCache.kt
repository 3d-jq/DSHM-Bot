package com.dshm.bot.ui.chat

/**
 * 进程级对话状态缓存（对齐 dsh-Go / Flutter 的进程级状态 + `_retained` 口径）。
 *
 * 为什么需要：对话 ViewModel 挂在 Activity 的 `ViewModelStore` 上 —— 用户按返回退出、
 * 或从任务列表重新进入，Activity 被销毁重建，VM 跟着没了，界面就回到「loading + 空
 * 列表」的骨架屏，然后整条时间线再重放一遍。真机日志实测过这条：进程 PID 没变、
 * 引擎没重启（token 尾号相同），只有 `MainActivity onCreate` 出现。
 *
 * Flutter 的 dsh-Go 没有这个问题（Dart 状态活在进程里），而且它连骨架屏都没有：
 * 同步 / 重连期间用 `_retained(items: transcript.items, loading: items.isEmpty)`
 * 把**已有内容**先发布出去（`dsh-Go/app/lib/state/chat_state.dart`）。
 * 这里就是 Android 侧的等价物：按 botId 留下最近一次的 UiState，VM 重建时先铺上，
 * 再后台静默刷新。
 *
 * 只留最近 [MAX_BOTS] 个（LRU）；只存引用 —— `entries` 是不可变列表，写入几乎零成本。
 */
internal object BotChatStateCache {
    /** 只留最近这么多个 bot 的对话（LRU）。测试会用它算边界。 */
    internal const val MAX_BOTS = 6

    private val states = object : LinkedHashMap<String, BotChatUiState>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, BotChatUiState>?,
        ): Boolean = size > MAX_BOTS
    }

    fun get(botId: String): BotChatUiState? = synchronized(states) { states[botId] }

    fun put(botId: String, state: BotChatUiState) {
        synchronized(states) { states[botId] = state }
    }

    /**
     * 引擎换端点（重启 / 换 token）时清空：旧引擎的 seq 空间和行 key 都不再成立，
     * 画到新会话上只会是错的内容。
     */
    fun clear() {
        synchronized(states) { states.clear() }
    }
}
