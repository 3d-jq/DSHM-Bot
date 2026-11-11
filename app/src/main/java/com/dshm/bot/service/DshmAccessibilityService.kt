package com.dshm.bot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.HandlerThread
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手机操控的无障碍服务。当前只提供原生的基础动作（点按/滑动/返回/主页/最近任务/
 * 按文本查找），供后续引擎桥接调用；设置页会读取它的系统启用状态。
 */
class DshmAccessibilityService : AccessibilityService() {

    private var gestureThread: HandlerThread? = null
    private var gestureHandler: Handler? = null

    override fun onServiceConnected() {
        instance = this
        PhoneControlSession.reset()
    }

    override fun onDestroy() {
        PhoneOverlay.hideNow()
        PhoneResultOverlay.hide()
        gestureThread?.quitSafely()
        gestureThread = null
        gestureHandler = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun tap(x: Float, y: Float, durationMs: Long = 60): Boolean {
        // 单点 moveTo 在部分 ROM 上不会形成有效笔画，补 0.5px 的极小线段，
        // 与 DeepSearch / dsh-Go 的做法一致，避免偶发点击丢失或偏移。
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 0.5f, y + 0.5f)
        }
        return dispatch(path, durationMs)
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

    fun notifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun findText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        val node = nodes.firstOrNull() ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun dispatch(path: Path, durationMs: Long): Boolean {
        val handler = gestureHandler ?: HandlerThread("dshm-phone-gesture").apply {
            start()
            gestureThread = this
        }.looper.let { looper ->
            Handler(looper).also { gestureHandler = it }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
            .build()
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val sent = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed.set(true)
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            },
            handler,
        )
        if (!sent) return false
        latch.await(durationMs.coerceAtLeast(1) + 2_000L, TimeUnit.MILLISECONDS)
        return completed.get()
    }

    companion object {
        @Volatile
        private var instance: DshmAccessibilityService? = null

        val connected: Boolean get() = instance != null

        internal val current: DshmAccessibilityService? get() = instance

        fun tap(x: Float, y: Float, durationMs: Long = 60): Boolean = instance?.tap(x, y, durationMs) ?: false

        fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float): Boolean =
            instance?.swipe(fromX, fromY, toX, toY) ?: false

        fun back(): Boolean = instance?.back() ?: false

        fun home(): Boolean = instance?.home() ?: false

        fun recents(): Boolean = instance?.recents() ?: false

        fun notifications(): Boolean = instance?.notifications() ?: false

        fun findText(text: String): Boolean = instance?.findText(text) ?: false
    }
}
