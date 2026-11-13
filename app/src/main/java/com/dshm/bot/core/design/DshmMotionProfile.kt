package com.dshm.bot.core.design

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue

/**
 * 动效档位（团队偏好 `prefs.motion`）。
 *
 * 网页端三档：`quiet` 关掉所有循环动画、`normal` 常规、`lively` 额外加空闲
 * 小动作。本机对应：
 *
 * - `quiet` —— 页面转场和控件动画都关掉，循环动画停在起始值；
 * - `normal` —— 默认时长；
 * - `lively` —— 转场更慢一点、循环动画照常（网页端的空闲小动作本机没有
 *   对应物，用稍长的转场体现「更有生气」）。
 */
data class MotionProfile(
    val screenTransitionMs: Int,
    val fadeMs: Int,
    val quickMs: Int,
    /** 是否允许循环动画（思考脉冲、加载骨架等）。 */
    val allowLoops: Boolean,
) {
    companion object {
        val Default = MotionProfile(
            screenTransitionMs = DshmMotion.screenTransitionMs,
            fadeMs = DshmMotion.fadeMs,
            quickMs = DshmMotion.quickMs,
            allowLoops = true,
        )

        /** 偏好里的档位名 → 档位。 */
        fun of(motion: String?): MotionProfile = when (motion) {
            "quiet" -> MotionProfile(0, 0, 0, allowLoops = false)
            "lively" -> MotionProfile(
                screenTransitionMs = DshmMotion.screenTransitionMs + 150,
                fadeMs = DshmMotion.fadeMs + 60,
                quickMs = DshmMotion.quickMs + 60,
                allowLoops = true,
            )
            else -> Default
        }
    }
}

/** 当前动效档位；界面用它取时长和是否允许循环动画。 */
val LocalMotionProfile = staticCompositionLocalOf { MotionProfile.Default }

/**
 * 一个循环动画的当前值。
 *
 * 档位是 `quiet` 时不播动画，直接停在 [from]（网页端 `quiet` 也是这个意思：
 * 元素不动）。放在这里统一处理，各个界面就不用各自判断。
 */
@Composable
fun rememberLoopValue(
    profile: MotionProfile,
    label: String,
    from: Float,
    to: Float,
    durationMs: Int = 1400,
    repeatMode: RepeatMode = RepeatMode.Reverse,
    easing: Easing = FastOutSlowInEasing,
): Float {
    val transition = rememberInfiniteTransition(label = label)
    val animated by transition.animateFloat(
        initialValue = from,
        targetValue = to,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = easing),
            repeatMode = repeatMode,
        ),
        label = "$label-value",
    )
    return if (profile.allowLoops) animated else from
}
