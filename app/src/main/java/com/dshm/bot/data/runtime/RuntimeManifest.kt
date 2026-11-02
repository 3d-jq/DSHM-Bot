package com.dshm.bot.data.runtime

import android.content.Context
import org.json.JSONObject

/**
 * 运行时清单（`app/src/main/assets/runtime/manifest.json`，由 runtime/build-runtime.sh 生成）。
 * 描述 rootfs 归档、校验值、proot 启动 argv 与环境。
 */
data class RuntimeManifest(
    val runtimeVersion: String,
    val abi: String,
    val rootfsAsset: String,
    val rootfsBytes: Long,
    val rootfsSha256: String?,
    val uncompressedBytes: Long,
    val tarBytes: Long,
    val dshVersion: String,
    val nodeVersion: String,
    val launchArgv: List<String>,
    val launchEnv: Map<String, String>,
) {
    companion object {
        private const val ASSET_PATH = "runtime/manifest.json"

        fun load(context: Context): RuntimeManifest {
            val text = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            return parse(text)
        }

        /** 解析 JSON（单独抽出来，便于测试）。 */
        fun parse(text: String): RuntimeManifest {
            val json = JSONObject(text)
            val launch = json.optJSONObject("launch") ?: JSONObject()
            val argvJson = launch.optJSONArray("argv")
            val argv = if (argvJson != null) List(argvJson.length()) { argvJson.getString(it) } else emptyList()
            val envJson = launch.optJSONObject("env") ?: JSONObject()
            val env = envJson.keys().asSequence().associateWith { envJson.getString(it) }
            return RuntimeManifest(
                runtimeVersion = json.getString("runtimeVersion"),
                abi = json.optString("abi"),
                rootfsAsset = json.getString("rootfs"),
                rootfsBytes = json.optLong("rootfsBytes", -1L),
                rootfsSha256 = json.optString("rootfsSha256").ifEmpty { null },
                uncompressedBytes = json.optLong("uncompressedBytes", -1L),
                tarBytes = json.optLong("tarBytes", -1L),
                dshVersion = json.optString("dshVersion"),
                nodeVersion = json.optString("nodeVersion"),
                launchArgv = argv,
                launchEnv = env,
            )
        }
    }
}
