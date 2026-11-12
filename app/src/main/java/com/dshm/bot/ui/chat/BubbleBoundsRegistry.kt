package com.dshm.bot.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow

/**
 * 对话页里每条气泡的窗口矩形。
 *
 * 长按文字时全局选区手势只给得出一个窗口坐标；气泡把自己的实际布局范围登记进来，
 * 由坐标反查是哪条消息，比 `messageId` 可靠 —— 引擎的 `displayItems` 很多 item 不带
 * `id`，刷新/缓存/多段分页之后同一个 id 会落到不同气泡上。
 */
internal class BubbleBoundsRegistry {
    private data class Entry(val coordinates: LayoutCoordinates, val target: MessageMenuTarget)
    /**
     * 普通 HashMap，不用 `SnapshotStateMap`：滚动时 `onGloballyPositioned` 每帧都会
     * 触发（所有可见气泡一起平移），写快照状态会让 Compose 每帧做一次无效通知。
     * 这里只有长按那一刻才读，普通集合 + synchronized 就够了。
     */
    private val bounds = LinkedHashMap<String, Entry>()

    fun register(key: String, coordinates: LayoutCoordinates, target: MessageMenuTarget) {
        synchronized(bounds) { bounds[key] = Entry(coordinates, target) }
    }

    /** 文字变了但布局没变时（流式输出）也要把面板内容刷新。 */
    fun updateTarget(key: String, target: MessageMenuTarget) {
        synchronized(bounds) {
            bounds[key]?.let { bounds[key] = it.copy(target = target) }
        }
    }

    fun unregister(key: String) {
        synchronized(bounds) { bounds.remove(key) }
    }

    /** 命中判定：坐标在长按那一刻现算，优先面积最小的（重叠时取最内层气泡）。 */
    fun targetAt(point: Offset): MessageMenuTarget? {
        val hits = synchronized(bounds) {
            bounds.values.mapNotNull { entry ->
                val coordinates = entry.coordinates
                if (!coordinates.isAttached) return@mapNotNull null
                val origin = coordinates.positionInWindow()
                val size = coordinates.size
                val rect = Rect(
                    left = origin.x,
                    top = origin.y,
                    right = origin.x + size.width.toFloat(),
                    bottom = origin.y + size.height.toFloat(),
                )
                if (!rect.contains(point)) return@mapNotNull null
                (rect.width * rect.height) to entry.target
            }
        }
        return hits.minByOrNull { it.first }?.second
    }
}

private class BubbleTargetHolder(var target: MessageMenuTarget)

/** 把一个气泡的布局范围登记到 [registry]。 */
@Composable
internal fun rememberBubbleBounds(
    key: String,
    registry: BubbleBoundsRegistry,
    target: MessageMenuTarget,
): (LayoutCoordinates) -> Unit {
    val holder = remember(key, registry) { BubbleTargetHolder(target) }
    holder.target = target
    androidx.compose.runtime.SideEffect { registry.updateTarget(key, holder.target) }
    val listener = remember(key, registry) {
        { coordinates: LayoutCoordinates ->
            registry.register(key, coordinates, holder.target)
        }
    }
    DisposableEffect(key, registry) {
        onDispose { registry.unregister(key) }
    }
    return listener
}
