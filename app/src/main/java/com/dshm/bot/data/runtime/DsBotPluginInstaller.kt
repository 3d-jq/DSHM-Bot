package com.dshm.bot.data.runtime

import android.content.Context
import java.io.File

/**
 * 把 APK 内置的 `ds-bot` 包装进 rootfs 的 DSH node_modules，并在启动 overlay 里
 * 插入 `bot` 行。这样不需要重新构建/下载 rootfs 就能让 `/api/bot` 生效；
 * 每次启动都会校验版本，runtime 升级后补丁文件被覆盖时也会重新追加。
 */
object DsBotPluginInstaller {

    private const val ASSET_ROOT = "ds-bot"
    private const val TARGET = "opt/dsh/node_modules/ds-bot"
    private const val MARKER = ".dshm-version"
    private const val PATCH = "etc/dsh-app/dsh-web.patch.yml"
    private const val ENTRY = "/opt/dsh/node_modules/ds-bot/index.js"

    fun install(context: Context, rootfs: File): Boolean {
        if (!rootfs.isDirectory) return false
        val assets = context.assets
        val version = runCatching {
            assets.open("$ASSET_ROOT/package.json").bufferedReader().use { it.readText() }
                .let { org.json.JSONObject(it).optString("version") }
        }.getOrNull() ?: return false

        val target = File(rootfs, TARGET)
        val marker = File(target, MARKER)
        // 版本号只在跟随上游时才变，而这份包被我们改过（browser 动作等）。
        // 用「插件版本 + 这次安装的时间」做指纹：APK 一更新就重新覆盖，改动的 JS 才进得去。
        val fingerprint = fingerprint(context)
        if (!marker.isFile || marker.readText().trim() != fingerprint) {
            target.deleteRecursively()
            if (!target.mkdirs()) return false
            if (!copyTree(assets, ASSET_ROOT, target)) return false
            marker.writeText(fingerprint)
        }
        ensurePatch(rootfs)
        return true
    }

    /**
     * 当前 APK 里这份插件的指纹（插件版本 + APK 更新时间）。
     *
     * APK 一换，指纹就变 —— 引擎里跑的还是旧代码，得重启一次才会加载新的。
     */
    fun fingerprint(context: Context): String {
        val version = runCatching {
            context.assets.open("$ASSET_ROOT/package.json").bufferedReader().use { it.readText() }
                .let { org.json.JSONObject(it).optString("version") }
        }.getOrNull() ?: return ""
        val stamp = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        return "$version@$stamp"
    }

    /** 磁盘上那份插件的指纹；没装过是 null。 */
    fun installedFingerprint(rootfs: File): String? {
        val marker = File(File(rootfs, TARGET), MARKER)
        if (!marker.isFile) return null
        return marker.readText().trim().takeIf { it.isNotEmpty() }
    }

    private fun copyTree(assets: android.content.res.AssetManager, path: String, target: File): Boolean {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            return runCatching {
                assets.open(path).use { input ->
                    target.parentFile?.mkdirs()
                    target.outputStream().use { input.copyTo(it) }
                }
                true
            }.getOrDefault(false)
        }
        if (!target.isDirectory && !target.mkdirs()) return false
        return children.all { child ->
            copyTree(assets, "$path/$child", File(target, child))
        }
    }

    private fun ensurePatch(rootfs: File) {
        val patch = File(rootfs, PATCH)
        patch.parentFile?.mkdirs()
        val text = if (patch.isFile) patch.readText() else ""
        if (text.contains("id: bot")) {
            // 旧版可能写的是裸包名；Node 不会从 profile 目录解析它，改成绝对路径。
            if (!text.contains(ENTRY)) {
                patch.writeText(text.replace("name: ds-bot", "name: $ENTRY"))
            }
            return
        }
        val row = buildString {
            if (text.isNotEmpty() && !text.endsWith("\n")) append('\n')
            append("- insert:\n")
            append("    - id: bot\n")
            append("      name: $ENTRY\n")
        }
        patch.appendText(row)
    }
}
