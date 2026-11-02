package com.dshm.bot.core.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * 动效令牌：时长与缓动曲线集中在这里，界面只引用语义名。
 * 页面切换用 iOS 风格：新页面整屏从右侧滑入，旧页面向左退一小段并轻微淡出。
 */
object DshmMotion {
    /** 整屏页面切换时长（push / pop）。 */
    const val screenTransitionMs: Int = 450

    /** 伴随的淡入淡出时长。 */
    const val fadeMs: Int = 300

    /** 推入/弹出主曲线（iOS 风格的快出慢入）。 */
    val standard: Easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    /** 旧页面的退场曲线（更早减速，让层次更自然）。 */
    val exit: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 旧页面在切换时向左退的距离占屏宽的比例（iOS 视差）。 */
    const val outgoingParallaxFraction: Float = 0.25f
}
