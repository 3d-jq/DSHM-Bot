package com.dshm.bot.data.settings

import android.content.Context
import java.io.File

/**
 * 个人偏好（对齐 dsh-Go `GuestFs.readImportedAgentsMd` / `writeImportedAgentsMd`）：
 * 只读写引擎 rootfs 里的 `/root/.agents/AGENTS.md`，也就是 share-code 导入
 * 会替换的同一个文件。清空即删除文件，不留下空文件。
 */
class PersonalizationStore(context: Context) {

    private val rootfs = File(context.applicationContext.filesDir, "runtime/rootfs")
    private val imported = File(rootfs, "root/.agents/AGENTS.md")

    fun rootfsAvailable(): Boolean = rootfs.isDirectory

    fun read(): String? = runCatching {
        if (!imported.isFile || imported.length() > MAX_CHARS * 4) null else imported.readText()
    }.getOrNull()

    /** 设置首页展示的第一行；没有内容时返回 null。 */
    fun summary(): String? = read()?.trim()?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /** 写临时文件再 rename，避免半截文件被引擎读到。 */
    fun save(text: String): Boolean {
        if (!rootfsAvailable()) return false
        return runCatching {
            if (text.trim().isEmpty()) {
                if (imported.exists()) imported.delete()
                return@runCatching true
            }
            val parent = imported.parentFile ?: return@runCatching false
            if (!parent.isDirectory && !parent.mkdirs()) return@runCatching false
            val tmp = File(parent, "AGENTS.md.dsh-app-tmp")
            tmp.writeText(text.take(MAX_CHARS))
            if (imported.exists()) imported.delete()
            tmp.renameTo(imported)
        }.getOrDefault(false)
    }

    companion object {
        /** dsh-Go `ProfileShare.maxAgentsMdChars`。 */
        const val MAX_CHARS = 64 * 1024
    }
}
