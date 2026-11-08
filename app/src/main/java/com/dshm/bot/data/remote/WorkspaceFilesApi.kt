package com.dshm.bot.data.remote

import org.json.JSONObject

/** `workspaceFiles/stat` 的结果：Host 世界里的绝对路径、版本号与大小。 */
data class DshFileStat(
    val absolutePath: String,
    val version: String,
    val bytes: Long?,
)

/**
 * 读 Session 工作区里的文件（交付产物、生成的图片）。
 *
 * 路径交给 Host 解析（绝对路径，或相对 Session 工作目录），字节走 [DshClient.readFileBytes]。
 * 对齐 dsh-Go `packages/dsh_client/lib/src/files_api.dart` 的 `statFile` + `readFile`。
 */
class WorkspaceFilesApi(private val client: DshClient) {

    suspend fun stat(sessionId: String, path: String): DshFileStat {
        val value = client.rpc(
            "workspaceFiles/stat",
            JSONObject()
                .put("workspaceFileScopeId", sessionId)
                .put("path", path),
        )
        return DshFileStat(
            absolutePath = value.optString("absolutePath"),
            version = value.optString("version"),
            bytes = if (value.has("bytes") && !value.isNull("bytes")) value.optLong("bytes") else null,
        )
    }

    /** 读整个文件；超过 Host 的字节上限时抛 `workspace-file/too-large`。 */
    suspend fun read(sessionId: String, path: String): ByteArray {
        val stat = stat(sessionId, path)
        return client.readFileBytes(stat.absolutePath.ifEmpty { path })
    }
}
