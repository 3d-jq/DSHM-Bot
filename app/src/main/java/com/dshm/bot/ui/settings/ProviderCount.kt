package com.dshm.bot.ui.settings

import com.dshm.bot.data.remote.DshEngineSettingsRepository
import com.dshm.bot.data.remote.ProviderProfile

/**
 * 设置首页「几个渠道」的计数口径 = 模型页实际显示的渠道数。
 *
 * 自定义渠道按 profile 一条算一条；`listedProviders` 里 DeepSeek 的两个内置来源
 * （official / account）在模型页被合并成一张「DeepSeek」卡，所以内置来源不管有
 * 几条都只算 1。之前直接 `profiles.size + listedProviders.size`，有 3 个自定义
 * 且两条 DeepSeek 内置都在时会显示 5，页面却只有 4 张卡。
 */
internal fun providerDisplayCount(
    profiles: List<ProviderProfile>,
    listedProviders: List<DshEngineSettingsRepository.ListedProvider>,
): Int {
    val customRoutes = profiles.map { it.route }.toSet()
    val builtIn = listedProviders.filter { it.id !in customRoutes }
    val deepSeek = builtIn.count { it.id.contains("deepseek", ignoreCase = true) }
    val other = builtIn.size - deepSeek
    return profiles.size + other + if (deepSeek > 0) 1 else 0
}
