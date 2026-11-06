package com.dshm.bot.data.runtime

import java.io.File

/**
 * 重置运行时时的「保留区」：把 rootfs 里要留下的路径挪到 rootfs 之外，
 * 删掉运行时后再搬回去。
 *
 * 纯文件逻辑，不依赖 Android，便于 JVM 单测覆盖。
 */
object RuntimeStash {

    /**
     * 把 [rootfs] 里 [paths]（相对 rootfs）复制到 [stashDir]。
     * 已经在保留区里的旧内容会先清掉。
     */
    fun stash(rootfs: File, stashDir: File, paths: List<String>) {
        stashDir.deleteRecursively()
        for (path in paths) {
            val source = File(rootfs, path)
            if (!source.exists()) continue
            val target = File(stashDir, path)
            target.parentFile?.mkdirs()
            if (source.isDirectory) {
                source.copyRecursively(target, overwrite = true)
            } else {
                source.copyTo(target, overwrite = true)
            }
        }
    }

    /** 把保留区搬回 [rootfs]（装好运行时后调用）。 */
    fun restore(stashDir: File, rootfs: File) {
        if (!stashDir.isDirectory) return
        stashDir.copyRecursively(rootfs, overwrite = true)
        stashDir.deleteRecursively()
    }

    /** 保留区里还有东西（重置后还没重新装运行时）。 */
    fun hasStash(stashDir: File): Boolean =
        stashDir.isDirectory && !stashDir.list().isNullOrEmpty()
}
