package com.dshm.bot.core.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * 触感反馈（轻震动）的分级与总开关。
 *
 * 项目原本零触感；对齐 dsh-Go 的 85 处 `HapticFeedback.*` 后，落到我们的界面动作上。
 *
 * 档位映射（用 `javap` 反编译 `DefaultHapticFeedback` 核实过每个 Compose 类型
 * 落到哪个 `View.HapticFeedbackConstants`，全走系统 `View.performHapticFeedback`，
 * **无需任何权限**）：
 *
 * | 档位 | Compose 类型 | View 常量 | 最低 API |
 * |---|---|---|---|
 * | [LIGHT]  | `VirtualKey`   | `VIRTUAL_KEY`   | 3  |
 * | [MEDIUM] | `ContextClick` | `CONTEXT_CLICK` | 23 |
 * | [HEAVY]  | `LongPress`    | `LONG_PRESS`    | 1  |
 *
 * ⚠️ **不要用 `Confirm` / `Reject` / `ToggleOn` 这些**：它们映射到 API 30 才有的常量，
 * 我们 `minSdk 26`——在 Android 8/9/10 上会静默 no-op（不崩、也没手感）。
 *
 * 只在**动作**上触发（发送、停止、审批、复制…）；普通点一下气泡不震（那是查看不是动作）。
 */
enum class HapticLevel { LIGHT, MEDIUM, HEAVY }

/**
 * 档位 → 平台触感类型。抽成函数是为了可单测（映射写错会整档手感走样；
 * 新增档位忘记映射会在这里直接 `when` 不穷尽、编译失败）。
 */
internal fun hapticEffectFor(level: HapticLevel): HapticFeedbackType = when (level) {
    // 短促的「键按」一下：发送、复制、选择这类高频轻确认。
    HapticLevel.LIGHT -> HapticFeedbackType.VirtualKey
    // 锐一点的「点击」：停止回合、审批同意这类要让人确认「已经执行了」的动作。
    HapticLevel.MEDIUM -> HapticFeedbackType.ContextClick
    // 最重的一记：留给将来（删除/危险操作）。
    HapticLevel.HEAVY -> HapticFeedbackType.LongPress
}

/** 总开关：整个 App 的震动是否启用（由「设置 → 个性化 → 震动反馈」控制）。 */
val LocalHapticsEnabled = staticCompositionLocalOf { true }

/**
 * 把触感总开关注入整棵树。挂在 `MainActivity` 的 `setContent` 里（`DshmTheme` 内层），
 * 值来自 `SettingsUiState.hapticsEnabled`。
 */
@Composable
fun HapticsRoot(enabled: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHapticsEnabled provides enabled, content = content)
}

/**
 * 取一个「按级别触发震动」的动作，受总开关 [LocalHapticsEnabled] 控制。
 * 在 onClick 等回调里调用即可：`haptics(HapticLevel.LIGHT)`。
 *
 * ⚠️ 返回的 lambda 是**稳定**的（`remember`）且通过 `rememberUpdatedState` 读实时开关：
 * 长按这类回调常被 `pointerInput(Unit)` 在**首帧**捕获、之后不再重建 —— 若闭包里
 * 直接捕获布尔值，「关掉总开关后长按仍然会震」。
 */
@Composable
fun rememberHaptics(): (HapticLevel) -> Unit {
    val enabled = rememberUpdatedState(LocalHapticsEnabled.current)
    val feedback = LocalHapticFeedback.current
    return remember(feedback) {
        { level -> if (enabled.value) feedback.performHapticFeedback(hapticEffectFor(level)) }
    }
}