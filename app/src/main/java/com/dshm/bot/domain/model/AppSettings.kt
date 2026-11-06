package com.dshm.bot.domain.model

/** 对话字号（设置 → 外观 → 对话字号）。 */
enum class ChatFontSize { SMALL, STANDARD, LARGE, XLARGE }

/** 对话文字缩放（标准 = 1.0），供聊天页实际应用。 */
fun ChatFontSize.scale(): Float = when (this) {
    ChatFontSize.SMALL -> 0.9f
    ChatFontSize.STANDARD -> 1f
    ChatFontSize.LARGE -> 1.12f
    ChatFontSize.XLARGE -> 1.24f
}

/** 界面语言（设置 → 通用 → 语言）。 */
enum class AppLanguage { SYSTEM, CHINESE, ENGLISH }

/** 界面模式（原生 / 网页）。 */
enum class InterfaceMode { NATIVE, WEB }

/** 语音输入（自动 / 系统 / 引擎）。 */
enum class VoiceMode { AUTO, SYSTEM, ENGINE }

/** 性能模式（自动 / 完整 / 轻量）。 */
enum class PerformanceMode { AUTO, FULL, LIGHT }

/** 宠物形象（设置 → 外观 → 宠物），对应 dsh-Go 的两套宠物包。 */
enum class PetStyle { DEEPY_WHALE, WHALE_GIRL }

/** 宠物大小。 */
enum class PetSize { SMALL, MEDIUM, LARGE }

/** 手机操控的确认方式。 */
enum class PhoneConfirmMode { ASK, TASK, ALWAYS }

/**
 * dsh-Go 设置 IA 里由 App 本地持有的细分偏好，对齐
 * `dsh-Go/docs/guide/zh/10-settings.md`。
 *
 * 只保留设置页里真实暴露的项：通知四类与「完成后收起过程」。
 * 宠物与性能页按产品范围不做，相关偏好一律不预留。
 * [flag] / [setFlag] 不依赖 Android，可用 JVM 单测覆盖。
 */
data class DshGoPreferences(
    val notifyTurnDone: Boolean = true,
    val notifyError: Boolean = true,
    val notifyApproval: Boolean = true,
    val notifyQuestion: Boolean = true,
    val foldProcess: Boolean = true,
) {
    fun flag(key: String): Boolean = when (key) {
        KEY_NOTIFY_TURN_DONE -> notifyTurnDone
        KEY_NOTIFY_ERROR -> notifyError
        KEY_NOTIFY_APPROVAL -> notifyApproval
        KEY_NOTIFY_QUESTION -> notifyQuestion
        KEY_FOLD_PROCESS -> foldProcess
        else -> error("未知的设置偏好 key: $key")
    }

    fun setFlag(key: String, value: Boolean): DshGoPreferences {
        // 先校验 key，避免静默写入一个不存在的偏好。
        flag(key)
        return when (key) {
            KEY_NOTIFY_TURN_DONE -> copy(notifyTurnDone = value)
            KEY_NOTIFY_ERROR -> copy(notifyError = value)
            KEY_NOTIFY_APPROVAL -> copy(notifyApproval = value)
            KEY_NOTIFY_QUESTION -> copy(notifyQuestion = value)
            KEY_FOLD_PROCESS -> copy(foldProcess = value)
            else -> error("未知的设置偏好 key: $key")
        }
    }

    companion object {
        const val KEY_NOTIFY_TURN_DONE = "notify_turn_done"
        const val KEY_NOTIFY_ERROR = "notify_error"
        const val KEY_NOTIFY_APPROVAL = "notify_approval"
        const val KEY_NOTIFY_QUESTION = "notify_question"
        const val KEY_FOLD_PROCESS = "fold_process"

        val BOOLEAN_KEYS = listOf(
            KEY_NOTIFY_TURN_DONE,
            KEY_NOTIFY_ERROR,
            KEY_NOTIFY_APPROVAL,
            KEY_NOTIFY_QUESTION,
            KEY_FOLD_PROCESS,
        )
    }
}
