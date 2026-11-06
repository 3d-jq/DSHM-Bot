package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * `session/modelCatalog`：引擎当前可用的模型目录，对齐 dsh-Go
 * `packages/dsh_client/lib/src/models.dart` 的 `ModelCatalog`。
 *
 * 只有配好凭据的渠道才会出现在目录里（没有 DeepSeek Key 时目录是空的）。
 */
data class ModelCatalog(
    val groups: List<ProviderGroup> = emptyList(),
) {
    /** 目录里一共多少个模型。 */
    val modelCount: Int get() = groups.sumOf { it.models.size }

    companion object {
        fun fromJson(raw: JSONObject?): ModelCatalog {
            if (raw == null) return ModelCatalog()
            val groups = mutableListOf<ProviderGroup>()
            raw.optJSONArray("groups")?.let { array ->
                for (index in 0 until array.length()) {
                    val group = array.optJSONObject(index) ?: continue
                    val providerId = group.optString("id")
                    val providerName = group.optString("name").ifEmpty { providerId }
                    val models = mutableListOf<ModelInfo>()
                    group.optJSONArray("models")?.let { list ->
                        for (i in 0 until list.length()) {
                            val model = list.optJSONObject(i) ?: continue
                            val id = model.optString("id")
                            if (id.isEmpty()) continue
                            models += ModelInfo(
                                provider = providerId,
                                id = id,
                                name = model.optString("name").ifEmpty { id },
                                description = model.optString("description").takeIf { it.isNotBlank() },
                            )
                        }
                    }
                    groups += ProviderGroup(providerId, providerName, models)
                }
            }
            return ModelCatalog(groups)
        }
    }
}

/** 一个渠道（provider）下的模型集合。 */
data class ProviderGroup(
    val providerId: String,
    val name: String,
    val models: List<ModelInfo>,
)

/** 一个模型。 */
data class ModelInfo(
    val provider: String,
    val id: String,
    val name: String,
    val description: String? = null,
)

/** 「子代理可选模型」里的一条：渠道 + 模型。 */
data class ModelRoute(val provider: String, val model: String)

/** 读引擎存下来的允许列表（`[{provider, model}]`），容错跳过坏项。 */
fun routesOf(value: Any?): List<ModelRoute> {
    val array = value as? JSONArray ?: return emptyList()
    val out = mutableListOf<ModelRoute>()
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val provider = item.optString("provider")
        val model = item.optString("model")
        if (provider.isEmpty() || model.isEmpty()) continue
        out += ModelRoute(provider, model)
    }
    return out
}

/** 写回引擎的允许列表。 */
fun routesJson(routes: List<ModelRoute>): JSONArray = JSONArray().apply {
    routes.forEach { route ->
        put(JSONObject().put("provider", route.provider).put("model", route.model))
    }
}

/** 模型候选：目录里的分组 + 「已保存但当前不可用」那一组。 */
data class ModelCandidate(
    val route: ModelRoute,
    val name: String,
    val providerName: String,
    val available: Boolean,
)

/**
 * 目录里的模型按渠道分组；已保存但目录不再列出的模型单独放进
 * 「已保存但当前不可用」那一组（对齐 dsh-Go 的 `modelCandidates`）。
 */
fun modelCandidates(catalog: ModelCatalog?, saved: List<ModelRoute>): List<Pair<String, List<ModelCandidate>>> {
    val left = saved.toMutableSet()
    val groups = mutableListOf<Pair<String, List<ModelCandidate>>>()
    for (group in catalog?.groups.orEmpty()) {
        if (group.models.isEmpty()) continue
        groups += group.name to group.models.map { model ->
            val route = ModelRoute(group.providerId, model.id)
            left.remove(route)
            ModelCandidate(route, model.name, group.name, available = true)
        }
    }
    if (left.isNotEmpty()) {
        groups += SAVED_UNAVAILABLE_GROUP to left.map { route ->
            ModelCandidate(route, route.model, route.provider, available = false)
        }
    }
    return groups
}

/** 「已保存但当前不可用」这一组的标题。 */
const val SAVED_UNAVAILABLE_GROUP = "已保存但当前不可用"
