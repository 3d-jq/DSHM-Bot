package com.dshm.bot.data.runtime

import java.io.File

/**
 * 启动前写入 rootfs 的环境提示（对齐 dsh-Go `GuestSetup.writeAgentsMd`）。
 *
 * Android 上的 DSH 跑在 proot 里，没有 bubblewrap / Landlock，`workspace-write`
 * 沙箱一定失败；提前告诉模型直接用 danger-full-access，避免它先试沙箱再报错。
 * 用户自己改过 `/root/.dsh/AGENTS.md`（没有我们的标记）时不覆盖。
 */
object GuestSetup {
    private const val MARKER = "<!-- dshm-bot:managed"

    private val AGENTS_MD = """
        |$MARKER — 删除这一行后 DSHM Bot 不再覆盖本文件 -->
        |# 运行环境（DSHM Bot）
        |
        |- 你运行在 Android 手机的 Ubuntu（proot，免 Root）环境里。这里没有可用的沙箱后端（bubblewrap / Landlock 都不可用），`workspace-write` 沙箱模式一定会被拒绝。
        |- 需要执行 bash 命令时，直接用 `sandbox_permissions: "danger-full-access"` 运行，并用一句话说明理由；不要先试沙箱模式。
        |- 引擎工作目录是 `/root/.dsh/bot/workspace`。
        """.trimMargin()

    fun apply(rootfs: File) {
        runCatching {
            val file = File(rootfs, "root/.dsh/AGENTS.md")
            if (file.isFile && !file.readText().startsWith(MARKER)) return
            val parent = file.parentFile ?: return
            if (!parent.isDirectory && !parent.mkdirs()) return
            val temp = File(parent, "AGENTS.md.dshm-tmp")
            temp.writeText(AGENTS_MD)
            if (file.exists()) file.delete()
            temp.renameTo(file)
        }
    }
}
