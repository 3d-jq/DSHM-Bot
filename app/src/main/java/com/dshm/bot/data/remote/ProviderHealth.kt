package com.dshm.bot.data.remote

/**
 * 渠道健康度，对齐 dsh-Go `StatusTone` 的四种状态：
 * 可用 / 缺少 API Key / 配置有误 / 未启用。
 *
 * dsh-Go 由引擎返回 health，本项目尚未接这个字段，这里按渠道档案 + 凭据状态本地推断，
 * 判定口径集中在这里，方便单测和后续换成引擎值。
 */
enum class ProviderHealth { READY, MISSING_KEY, MISCONFIGURED, DISABLED }

object ProviderHealthEvaluator {
    fun evaluate(profile: ProviderProfile, keyConfigured: Boolean): ProviderHealth = when {
        profile.api.isNullOrBlank() || profile.baseUrl.isNullOrBlank() -> ProviderHealth.MISCONFIGURED
        !keyConfigured -> ProviderHealth.MISSING_KEY
        profile.models.isEmpty() -> ProviderHealth.DISABLED
        else -> ProviderHealth.READY
    }
}
