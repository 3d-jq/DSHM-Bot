package com.dshm.bot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 手机操控的无障碍服务。当前只提供原生的基础动作（点按/滑动/返回/主页/最近任务/
 * 按文本查找），供后续引擎桥接调用；设置页会读取它的系统启用状态。
 */
class DshmAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 60)
    }

    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        return dispatch(path, durationMs)
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun recents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun findText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        val node = nodes.firstOrNull() ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun dispatch(path: Path, durationMs: Long): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
            .build()
        return dispatchGesture(gesture, null, Handler(Looper.getMainLooper()))
    }

    companion object {
        @Volatile
        private var instance: DshmAccessibilityService? = null

        val connected: Boolean get() = instance != null

        fun tap(x: Float, y: Float): Boolean = instance?.tap(x, y) ?: false

        fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float): Boolean =
            instance?.swipe(fromX, fromY, toX, toY) ?: false

        fun back(): Boolean = instance?.back() ?: false

        fun home(): Boolean = instance?.home() ?: false

        fun recents(): Boolean = instance?.recents() ?: false

        fun findText(text: String): Boolean = instance?.findText(text) ?: false
    }
}
