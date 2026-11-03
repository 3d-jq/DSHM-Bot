package com.dshm.bot.data.settings

import android.content.Context
import java.io.File

/**
 * 个人偏好（dsh-Go 的「个人偏好」）：
 * - 读：优先 `/root/.agents/AGENTS.md`（导入的），其次 `/root/.dsh/AGENTS.md`（非 App 托管时）；
 * - 写：写 `/root/.agents/AGENTS.md`，清空则删文件。
 * 路径对应当前本机引擎 rootfs。
 */
class PersonalizationStore(context: Context) {

    private val rootfs = File(context.applicationContext.filesDir, "runtime/rootfs")
    private val imported = File(rootfs, "root/.agents/AGENTS.md")
    private val dshHome = File(rootfs, "root/.dsh/AGENTS.md")

    fun rootfsAvailable(): Boolean = rootfs.isDirectory

    fun read(): String? {
        imported.takeIf { it.isFile }?.let { file ->
            val text = file.readText()
            if (text.isNotBlank()) return text
        }
        dshHome.takeIf { it.isFile }?.let { file ->
            val text = file.readText()
            if (text.isNotBlank() && !text.trimStart().startsWith(MANAGED_MARKER)) return text
        }
        return null
    }

    fun save(text: String) {
        if (text.trim().isEmpty()) {
            imported.delete()
            return
        }
        imported.parentFile?.mkdirs()
        imported.writeText(text.take(MAX_CHARS))
    }

    private companion object {
        const val MANAGED_MARKER = "<!-- dsh-app:managed"
        const val MAX_CHARS = 20_000
    }
}
