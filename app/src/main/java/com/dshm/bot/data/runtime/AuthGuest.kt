package com.dshm.bot.data.runtime

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * 登录桥插件（移植自 dsh-Go `AppAuthGuest`）：
 * 每次启动把 auth bridge 写进 rootfs 的 `/opt/dsh-app/auth`，并在 `dsh web` 参数里追加
 * `--patch /opt/dsh-app/auth/auth.patch.yml`，让 DSH 挂上账号登录能力。
 */
object AuthGuest {
    const val GUEST_DIR = "/opt/dsh-app/auth"
    const val PATCH = "$GUEST_DIR/auth.patch.yml"
    const val ENTRY = "$GUEST_DIR/index.mjs"

    private const val ASSET_DIR = "engine/auth"
    private val MODULES = listOf("index.mjs", "bridge.mjs")
    private const val WEB_PATCH_ARG = "--patch /etc/dsh-app/dsh-web.patch.yml"
    private const val DSH_WEB = "/usr/local/bin/dsh web"

    private val PATCH_YAML = """
        # dshm-bot: account sign-in bridge. Rewritten at every engine start; edits do not survive.
        - insert:
            - id: dshm-auth
              name: $ENTRY
    """.trimIndent() + "\n"

    /** 在 `dsh web` 启动命令里插入 auth 的 `--patch`；不是 web 启动时返回 null。 */
    fun launchArg(arg: String): String? {
        val web = arg.indexOf("$DSH_WEB ")
        if (web < 0 || arg.indexOf("$DSH_WEB ", web + 1) >= 0 || arg.contains("--profile")) return null
        val patch = arg.indexOf(WEB_PATCH_ARG, web)
        val at = if (patch >= 0 && arg.indexOf(WEB_PATCH_ARG, patch + 1) < 0) patch + WEB_PATCH_ARG.length else web + DSH_WEB.length
        return arg.substring(0, at) + " --patch $PATCH" + arg.substring(at)
    }

    fun patchFile(rootfs: File): File = File(rootfs, PATCH.removePrefix("/"))

    /** 写入桥模块和 overlay；失败时返回 false（调用方就不加 --patch，避免 DSH 启动失败）。 */
    fun writeFiles(context: Context, rootfs: File): Boolean {
        val patch = patchFile(rootfs)
        return try {
            val dir = File(rootfs, GUEST_DIR.removePrefix("/"))
            if (Files.isSymbolicLink(dir.toPath()) || (dir.exists() && !dir.isDirectory)) dir.delete()
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create ${dir.path}")
            for (name in MODULES) {
                val bytes = context.assets.open("$ASSET_DIR/$name").use { it.readBytes() }
                writeIfChanged(File(dir, name), bytes)
            }
            writeIfChanged(patch, PATCH_YAML.toByteArray())
            true
        } catch (_: Exception) {
            patch.delete()
            false
        }
    }

    private fun writeIfChanged(target: File, bytes: ByteArray) {
        if (Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) && target.readBytes().contentEquals(bytes)) return
        val tmp = File(target.parentFile, ".${target.name}.dshm-${System.nanoTime()}.tmp")
        try {
            tmp.writeBytes(bytes)
            tmp.setReadable(true, false)
            if (!tmp.renameTo(target)) throw IOException("could not replace ${target.path}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
