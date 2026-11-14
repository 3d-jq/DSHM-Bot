package com.dshm.bot.data.runtime

import java.io.File

/**
 * 启动前写入 rootfs 的环境提示（对齐 dsh-Go `GuestSetup.writeAgentsMd`）。
 *
 * Android 上的 DSH 跑在 proot 里，没有 bubblewrap / Landlock，`workspace-write`
 * 沙箱一定失败；提前告诉模型直接用 danger-full-access，避免它先试沙箱再报错。
 * 交付规则（`present`）也在这里：模型不主动声明交付，产物卡就没有数据源。
 *
 * 写入策略（幂等，每次引擎启动都会跑一遍）：
 * - 文件不存在 / 以我们的标记开头（我们管理的文件）：写入 / 规则更新时整体重写；
 * - 用户文件里已经带我们的块（此前追加过）：不动；
 * - 用户文件里自己写了交付说明：尊重，不重复追加；
 * - 其余情况（宿主基础文件、运行时升级冲回的旧文件——此前只在首次 provision
 *   时写入，升级后规则就丢了）：**末尾追加**我们的块，用户已有内容原样保留。
 */
object GuestSetup {
    private const val MARKER = "<!-- dshm-bot:managed"

    /** 交付规则小节的标题：用户的文件里已经有自己的交付说明时就不再追加。 */
    private const val DELIVERY_SECTION = "# 交付文件（DSHM Bot）"

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
            val parent = file.parentFile ?: return
            if (!parent.isDirectory && !parent.mkdirs()) return
            val existing = if (file.isFile) file.readText() else null
            when {
                // 还没有文件，或整个文件都是我们管理的：写入；规则更新过就重写。
                existing == null || existing.startsWith(MARKER) ->
                    if (existing != AGENTS_MD) writeAtomically(file, AGENTS_MD)
                // 用户文件里已经带我们的块（此前追加过）：幂等，不动。
                existing.contains(MARKER) -> Unit
                // 用户自己写了交付说明：尊重，不重复追加。
                existing.contains(DELIVERY_SECTION) -> Unit
                // 宿主基础文件 / 升级后冲掉的旧文件：末尾追加我们的块，原文保留。
                else -> writeAtomically(file, existing.trimEnd('\n') + "\n\n" + AGENTS_MD + "\n")
            }
        }
    }

    private fun writeAtomically(file: File, content: String) {
        val temp = File(file.parentFile, "AGENTS.md.dshm-tmp")
        temp.writeText(content)
        if (file.exists()) file.delete()
        temp.renameTo(file)
    }
}
