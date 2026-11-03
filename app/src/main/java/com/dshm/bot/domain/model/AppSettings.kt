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

