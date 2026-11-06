package com.dshm.bot.ui.settings

/**
 * 哪些 namespace 算「插件的设置」——对齐 dsh-Go
 * `app/lib/ui/engine_settings/engine_settings_page.dart` 的
 * `curatedNamespaces` / `internalNamespaces`，以及
 * `app/lib/state/plugin_config/plugin_config_scope.dart` 的 `PluginConfigScope`。
 *
 * dsh-Go 的规则是**白名单**：一个 namespace 算插件设置，当且仅当
 * 1. 它不是 App 自己有专门页的（`curatedNamespaces`）；
 * 2. 它不是引擎内部或**别的宿主**的私有状态（`internalNamespaces`）；
 * 3. 它被**已启用的组合包声明**（`PluginConfigScope`）。
 *
 * 第 2 条最要紧：本机 rootfs 里预装了 dsh-go-bridge（网页客户端的桥）和
 * dsh-app，它们的 UI 设置（界面主题 / 账号设置界面 / 默认模型 / DeepSeek 渠道…）
 * 在引擎里也是 namespace，但对手机端毫无意义 —— 界面上会露出
 * `file:///opt/dsh-go/dsh-go-bridge/lib/index.js` 这类别的宿主的入口路径。
 */
object PluginConfigScope {

    /** App 自己有专门页的 namespace（dsh-Go 的 `curatedNamespaces`）。 */
    val CURATED_NAMESPACES = setOf(
        "subagent",
        "subagent-model-selection-settings",
        "agent-loop",
        "bash-sandbox",
        "pwsh-sandbox",
        "web",
        "web-search-deepseek",
        "web-deepseek-api",
        "ui-settings",
        "ui-chat",
        "ui-conversation",
        "session-log-deepseek",
        "dsh-media",
        "media",
    )

    /**
     * 引擎自己的管道与**网页客户端的私有状态**（dsh-Go 的 `internalNamespaces`）。
     * 手机上这些没有对应的页面，也不该被当插件列出来。
     */
    val INTERNAL_NAMESPACES = setOf(
        "permission",
        "agent-preset-registry",
        "agent-default-model",
        "locale",
        // dsh-go-bridge（网页客户端）的界面设置
        "ui-theme",
        "ui-settings-account",
        "ui-settings-general",
        // dsh-go-bridge 的模型渠道设置
        "llm-pi-ai",
        "llm-deepseek",
        "llm-deepseek-account",
    )

    /**
     * 这个 namespace 是不是 App 内部/别的宿主的（不当插件列）。
     */
    fun isInternal(ns: String): Boolean =
        ns in CURATED_NAMESPACES || ns in INTERNAL_NAMESPACES

    /**
     * 「已启用的组合包声明了哪些 row」，按 rowId 计数。
     *
     * 一个 rowId 被两个包声明时算「歧义」，引擎也无法寻址它（`ambiguous-id`），
     * 所以那种不算任何包的。
     */
    fun declaredRows(enabledBundleRows: List<List<String>>): Map<String, Int> {
        val declared = mutableMapOf<String, Int>()
        enabledBundleRows.forEach { rows -> rows.forEach { row -> declared[row] = (declared[row] ?: 0) + 1 } }
        return declared
    }

    /**
     * 这个 namespace 是不是某个**已启用的组合包**带来的插件设置。
     *
     * @param declared 「已启用的组合包声明了哪些 row」的计数表。
     */
    fun isPluginNamespace(ns: String, declared: Map<String, Int>): Boolean =
        !isInternal(ns) && declared[ns] == 1
}
