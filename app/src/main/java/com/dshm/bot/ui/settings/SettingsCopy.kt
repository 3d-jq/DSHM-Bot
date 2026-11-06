package com.dshm.bot.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources

/** 当前界面语言（跟随 App 的语言覆盖，而不是系统 Locale）。 */
@Composable
fun settingsLanguage(): String {
    val configuration = LocalResources.current.configuration
    val locales = configuration.locales
    return if (locales.isEmpty) "zh" else locales[0].language
}

/**
 * 引擎设置的中英文文案（对齐 dsh-Go `engine_settings/namespace_copy.dart` 的子集）。
 * 没收录的字段回退到 camelCase 拆词，避免出现裸 JSON key。
 */
object SettingsCopy {

    fun isChinese(language: String): Boolean = language.lowercase().startsWith("zh")

    fun namespaceTitle(ns: String, language: String): String {
        val zh = isChinese(language)
        return when (ns) {
            "subagent" -> if (zh) "子代理" else "Subagents"
            "subagent-model-selection-settings" -> if (zh) "子代理模型选择" else "Subagent models"
            "agent-loop" -> if (zh) "Agent 循环" else "Agent loop"
            "bash-sandbox", "pwsh-sandbox" -> if (zh) "命令执行" else "Command execution"
            "web", "web-search-deepseek", "web-deepseek-api" -> if (zh) "联网搜索" else "Web search"
            "session-log-deepseek" -> if (zh) "对话日志" else "Chat log"
            "cot-translation" -> if (zh) "思考翻译" else "Thinking translation"
            "plugin-market" -> if (zh) "插件索引" else "Plugin indexes"
            "permission" -> if (zh) "权限" else "Permissions"
            "agent-preset-registry" -> if (zh) "Agent 预设" else "Agent presets"
            "agent-default-model" -> if (zh) "默认模型" else "Default model"
            "ui-theme" -> if (zh) "网页外观" else "Web appearance"
            "ui-chat", "ui-conversation", "ui-settings" -> if (zh) "网页通用设置" else "Web settings"
            "locale" -> if (zh) "语言" else "Language"
            "llm-pi-ai" -> if (zh) "模型渠道" else "Model providers"
            else -> humanize(ns.replace('-', ' '))
        }
    }

    fun fieldLabel(ns: String, path: List<String>, language: String): String {
        val key = "$ns:${path.joinToString(".")}"
        val zh = isChinese(language)
        val table = if (zh) zhFields else enFields
        table[key]?.let { return it }
        table[":${path.lastOrNull().orEmpty()}"]?.let { return it }
        return humanize(path.lastOrNull().orEmpty().ifEmpty { key })
    }

    fun fieldDescription(ns: String, path: List<String>, language: String): String? = null

    fun permissionLabel(value: String, language: String): String {
        val zh = isChinese(language)
        return when (value) {
            "default", "ask" -> if (zh) "询问" else "Ask"
            "auto" -> if (zh) "自动审查" else "Auto review"
            "danger-full-access" -> if (zh) "完全权限" else "Full access"
            "workspace-write" -> if (zh) "工作区写入" else "Workspace write"
            "read-only" -> if (zh) "只读" else "Read only"
            "custom" -> if (zh) "自定义" else "Custom"
            else -> humanize(value.replace('-', ' '))
        }
    }

    fun permissionHint(value: String, language: String): String? {
        val zh = isChinese(language)
        return when (value) {
            "read-only" -> if (zh) "只能读取，修改前需要确认" else "Read only; changes need confirmation"
            "workspace-write" -> if (zh) "可以读写工作区，其他位置需要确认" else "Write inside the workspace; confirm elsewhere"
            "danger-full-access" -> if (zh) "不询问直接执行命令，风险最高" else "Run commands without asking; highest risk"
            "auto" -> if (zh) "自动审查，部分确认会被跳过" else "Auto review; some confirmations are skipped"
            "custom" -> if (zh) "由引擎自定义的权限组合" else "A custom permission set from the engine"
            else -> null
        }
    }

    fun humanize(raw: String): String {
        if (raw.isBlank()) return raw
        val out = StringBuilder()
        raw.forEachIndexed { index, ch ->
            when {
                ch == '_' || ch == '-' -> out.append(' ')
                ch.isUpperCase() && index > 0 && !raw[index - 1].isUpperCase() && !raw[index - 1].isWhitespace() -> {
                    out.append(' ')
                    out.append(ch)
                }
                else -> out.append(ch)
            }
        }
        val text = out.toString().trim()
        return text.replaceFirstChar { it.uppercase() }
    }

    private val zhFields = mapOf(
        "subagent:maxDepth" to "子代理层级",
        "subagent:maxActiveSubagents" to "同时运行的子代理",
        "subagent-model-selection-settings:enabled" to "允许选择模型",
        "subagent-model-selection-settings:allowedModels" to "允许的模型",
        "agent-loop:maxParallelToolCalls" to "并行工具调用",
        "bash-sandbox:timeoutMs" to "命令超时",
        "bash-sandbox:maxOutputBytes" to "最大输出",
        "bash-sandbox:maxTimeoutMs" to "最长超时",
        "bash-sandbox:maxSpillBytes" to "溢出上限",
        "bash-sandbox:graceMs" to "结束宽限期",
        "bash-sandbox:cwd" to "默认工作目录",
        "pwsh-sandbox:timeoutMs" to "命令超时",
        "pwsh-sandbox:maxOutputBytes" to "最大输出",
        "pwsh-sandbox:maxTimeoutMs" to "最长超时",
        "pwsh-sandbox:maxSpillBytes" to "溢出上限",
        "pwsh-sandbox:graceMs" to "结束宽限期",
        "pwsh-sandbox:cwd" to "默认工作目录",
        "web:searchProvider" to "搜索服务",
        "web:fetchProvider" to "抓取服务",
        "web-search-deepseek:apiKey" to "API Key",
        "web-search-deepseek:baseURL" to "服务地址",
        "web-search-deepseek:maxUses" to "每次对话次数",
        "web-search-deepseek:model" to "辅助模型",
        "web-search-deepseek:apiVersion" to "API 版本",
        "web-search-deepseek:maxTokens" to "最大 Token",
        "web-deepseek-api:apiKey" to "API Key",
        "web-deepseek-api:baseURL" to "服务地址",
        "web-deepseek-api:count" to "每次结果数",
        "web-deepseek-api:sendOriginalQuestion" to "发送原问题",
        "session-log-deepseek:enabled" to "上传对话日志",
        "cot-translation:provider" to "翻译服务",
        "cot-translation:targetLanguage" to "目标语言",
        "permission:defaultPreset" to "新会话默认权限",
        "agent-default-model:model" to "默认模型",
        "subagent-model-selection-settings:models" to "模型清单",
    )

    private val enFields = mapOf(
        "subagent:maxDepth" to "Max depth",
        "subagent:maxActiveSubagents" to "Active subagents",
        "subagent-model-selection-settings:enabled" to "Allow model choice",
        "subagent-model-selection-settings:allowedModels" to "Allowed models",
        "agent-loop:maxParallelToolCalls" to "Parallel tool calls",
        "bash-sandbox:timeoutMs" to "Command timeout",
        "bash-sandbox:maxOutputBytes" to "Max output",
        "bash-sandbox:maxTimeoutMs" to "Max timeout",
        "bash-sandbox:maxSpillBytes" to "Max spill",
        "bash-sandbox:graceMs" to "Grace period",
        "bash-sandbox:cwd" to "Working directory",
        "pwsh-sandbox:timeoutMs" to "Command timeout",
        "pwsh-sandbox:maxOutputBytes" to "Max output",
        "pwsh-sandbox:maxTimeoutMs" to "Max timeout",
        "pwsh-sandbox:maxSpillBytes" to "Max spill",
        "pwsh-sandbox:graceMs" to "Grace period",
        "pwsh-sandbox:cwd" to "Working directory",
        "web:searchProvider" to "Search provider",
        "web:fetchProvider" to "Fetch provider",
        "web-search-deepseek:apiKey" to "API Key",
        "web-search-deepseek:baseURL" to "Base URL",
        "web-search-deepseek:maxUses" to "Uses per chat",
        "web-search-deepseek:model" to "Helper model",
        "web-search-deepseek:apiVersion" to "API version",
        "web-search-deepseek:maxTokens" to "Max tokens",
        "web-deepseek-api:apiKey" to "API Key",
        "web-deepseek-api:baseURL" to "Base URL",
        "web-deepseek-api:count" to "Results per search",
        "web-deepseek-api:sendOriginalQuestion" to "Send original question",
        "session-log-deepseek:enabled" to "Upload chat log",
        "cot-translation:provider" to "Translation provider",
        "cot-translation:targetLanguage" to "Target language",
        "permission:defaultPreset" to "Default permission",
        "agent-default-model:model" to "Default model",
        "subagent-model-selection-settings:models" to "Models",
    )
}
