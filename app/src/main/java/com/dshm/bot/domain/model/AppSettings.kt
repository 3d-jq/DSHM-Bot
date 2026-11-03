package com.dshm.bot.domain.model

/** 对话字号（设置 → 外观 → 对话字号）。 */
enum class ChatFontSize { SMALL, STANDARD, LARGE, XLARGE }

/** 界面语言（设置 → 通用 → 语言）。 */
enum class AppLanguage { SYSTEM, CHINESE, ENGLISH }

/** 界面模式（原生 / 网页）。 */
enum class InterfaceMode { NATIVE, WEB }

/** 语音输入（自动 / 系统 / 引擎）。 */
enum class VoiceMode { AUTO, SYSTEM, ENGINE }

/** 性能模式（自动 / 完整 / 轻量）。 */
enum class PerformanceMode { AUTO, FULL, LIGHT }

