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
    /** 打开 App 时自动启动本机引擎（对齐 dsh-Go 的 `autoStartLocal`）。 */
    val autoStartLocal: Boolean = true,
) {
    fun flag(key: String): Boolean = when (key) {
        KEY_NOTIFY_TURN_DONE -> notifyTurnDone
        KEY_NOTIFY_ERROR -> notifyError
        KEY_NOTIFY_APPROVAL -> notifyApproval
        KEY_NOTIFY_QUESTION -> notifyQuestion
        KEY_FOLD_PROCESS -> foldProcess
        KEY_AUTO_START_LOCAL -> autoStartLocal
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
            KEY_AUTO_START_LOCAL -> copy(autoStartLocal = value)
            else -> error("未知的设置偏好 key: $key")
        }
    }

    companion object {
        const val KEY_NOTIFY_TURN_DONE = "notify_turn_done"
        const val KEY_NOTIFY_ERROR = "notify_error"
        const val KEY_NOTIFY_APPROVAL = "notify_approval"
        const val KEY_NOTIFY_QUESTION = "notify_question"
        const val KEY_FOLD_PROCESS = "fold_process"
        const val KEY_AUTO_START_LOCAL = "auto_start_local"

        val BOOLEAN_KEYS = listOf(
            KEY_NOTIFY_TURN_DONE,
            KEY_NOTIFY_ERROR,
            KEY_NOTIFY_APPROVAL,
            KEY_NOTIFY_QUESTION,
            KEY_FOLD_PROCESS,
            KEY_AUTO_START_LOCAL,
        )
    }
}
