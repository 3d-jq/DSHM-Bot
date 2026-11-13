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
        |
        |# 交付文件（DSHM Bot）
        |
        |- 把文件交给用户时，调用 `present` 声明交付：一次调用给出最终文件路径和一句说明，不要只在回复里贴路径。
        |- **文档、表格、演示、图片、视频、音频都算交付物**（`present` 默认描述偏向 Office 文档，图片/视频同样要声明）。
        |- 只声明真正要交给用户的成品，不要把中间文件、临时脚本列进去；文件生成完成后再调用。
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
