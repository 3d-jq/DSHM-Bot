package com.dshm.bot.ui.chat

import androidx.compose.foundation.ScrollState

/** 表格横向滚动状态按身份复用（DeepSearch `TableHScrollStates`）。 */
internal object TableHScrollStates {
    private val states = object : LinkedHashMap<String, ScrollState>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ScrollState>?,
        ): Boolean = size > 64
    }

    fun stateFor(key: String): ScrollState = synchronized(states) {
        states.getOrPut(key) { ScrollState(0) }
    }
}
