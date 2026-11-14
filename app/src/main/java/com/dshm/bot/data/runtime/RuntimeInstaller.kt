package com.dshm.bot.data.runtime

import android.content.Context
import android.os.SystemClock
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 解包 rootfs：从运行时 zip 里取出 `effective.rootfsAsset`（tar.xz），
 * 校验 SHA-256 后解到 `filesDir/runtime/rootfs`，并写版本文件。
 *
 * 目录布局：
 *   runtime/rootfs/          解压后的 Linux 根
 *   runtime/rootfs.version   当前版本
 *   runtime/staging/         解压中转，完成后整体换入
 */
class RuntimeInstaller private constructor(
    private val appContext: Context?,
    val baseDir: File,
) {
    val rootfsDir = File(baseDir, "rootfs")
    private val versionFile = File(baseDir, "rootfs.version")
    private val stagingDir = File(baseDir, "staging")
    /** 重置运行时时暂存「要保留的东西」的地方（在 rootfs 之外）。 */
    private val stashDir = File(baseDir, "keep")

    /** 常规构造：日志与解压中转都挂在应用私有目录下。 */
    constructor(context: Context) : this(
        context.applicationContext,
        File(context.applicationContext.filesDir, "runtime"),
    )

    /** 单测构造：baseDir 指向临时目录，不触碰任何 Android API（EngineLog 未初始化时全部空转）。 */
    internal constructor(baseDir: File) : this(null, baseDir)

    fun isInstalled(): Boolean =
        versionFile.isFile && rootfsDir.isDirectory && !rootfsDir.list().isNullOrEmpty()

    fun installedVersion(): String? =
        if (versionFile.isFile) versionFile.readText().trim().ifEmpty { null } else null

    /**
     * 安装 [zipFile] 里的 rootfs。[onProgress]：(0..100, 文案)；不确定进度时会持续给 0。
     */
    fun installFromZip(
        zipFile: File,
        manifest: RuntimeManifest,
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ): String = installFromZip(zipFile, manifest, appContext?.cacheDir, onProgress)

    /** [cacheDir] 可注入的版本：单测用它绕开 Android 上下文。 */
    internal fun installFromZip(
        zipFile: File,
        manifest: RuntimeManifest,
        cacheDir: File?,
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ): String {
        // 校验基准以 APK 内置清单为准：zip 里自带的 manifest 是随包而来的，
        // 攻击者连哈希一起改就能「自证合格」——拿它校验等于没校验。
        // 旧实现里「内嵌 manifest 缺哈希就静默跳过校验」正是从这里被绕过的，一并堵上。
        val embedded = readEmbeddedManifest(zipFile)
        val expected = manifest.rootfsSha256?.takeIf { it.isNotBlank() }
            ?: error("内置清单缺少 rootfsSha256，无法校验运行时包，拒绝安装")
        if (embedded != null) {
            val embeddedSha = embedded.rootfsSha256?.takeIf { it.isNotBlank() }
                ?: error("运行时包 manifest 缺少 rootfsSha256，拒绝安装")
            require(embeddedSha.equals(expected, ignoreCase = true)) {
                "运行时包 manifest 与内置清单的哈希不一致：$embeddedSha / $expected"
            }
            require(embedded.runtimeVersion == manifest.runtimeVersion) {
                "运行时包版本 ${embedded.runtimeVersion} 与内置清单 ${manifest.runtimeVersion} 不一致"
            }
        }
        val effective = embedded ?: manifest
        appContext?.let(EngineLog::init)
        EngineLog.reset()
        EngineLog.log("unpacking runtime ${effective.runtimeVersion} (${effective.abi})")
        val startedAt = clockMs()
        baseDir.mkdirs()
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        val tarFile = File(cacheDir ?: baseDir, "rootfs.tar.xz")
        try {
            ZipFile(zipFile).use { zip ->
                val entry = findRootfsEntry(zip, effective.rootfsAsset)
                val digest = MessageDigest.getInstance("SHA-256")
                var read = 0L
                var lastReported = 0L
                zip.getInputStream(entry).use { input ->
                    tarFile.outputStream().use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            read += n
                            if (entry.size > 0 && read - lastReported > 4L * 1024 * 1024) {
                                lastReported = read
                                onProgress(((read * 100) / entry.size).toInt().coerceIn(0, 100), "解压准备中")
                            }
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                require(actual.equals(expected, ignoreCase = true)) {
                    "rootfs 校验失败：期望 $expected，实际 $actual"
                }
            }

            val verifyAt = clockMs()
            EngineLog.log("install verify: ${verifyAt - startedAt} ms")
            onProgress(0, "解压中")
            extractTarXz(tarFile, stagingDir, effective.entriesTotal, effective.uncompressedBytes, onProgress)
            EngineLog.log("install extract: ${clockMs() - verifyAt} ms")
        } finally {
            tarFile.delete()
        }

        val syncAt = clockMs()
        rootfsDir.deleteRecursively()
        if (!stagingDir.renameTo(rootfsDir)) {
            stagingDir.copyRecursively(rootfsDir, overwrite = true)
            stagingDir.deleteRecursively()
        }
        EngineLog.log("install sync: ${clockMs() - syncAt} ms")
        val activateAt = clockMs()
        versionFile.writeText(effective.runtimeVersion)
        EngineLog.log("install activate: ${clockMs() - activateAt} ms")
        // 之前重置时保留的东西这时搬回新装的运行时。
        restoreStash()
        EngineLog.log("runtime ready in ${(clockMs() - startedAt) / 1000}s")
        onProgress(100, "已安装")
        return effective.runtimeVersion
    }

    /**
     * 单测环境没有 SystemClock（Android stub 会抛异常）；时间戳只喂给 EngineLog，
     * 取不到时给 0，不影响安装逻辑。
     */
    private fun clockMs(): Long = runCatching { SystemClock.elapsedRealtime() }.getOrDefault(0L)

    private fun readEmbeddedManifest(zipFile: File): RuntimeManifest? = runCatching {
        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry("manifest.json") ?: return@use null
            RuntimeManifest.parse(zip.getInputStream(entry).bufferedReader().use { it.readText() })
        }
    }.getOrNull()

    fun delete() {
        rootfsDir.deleteRecursively()
        stagingDir.deleteRecursively()
        versionFile.delete()
    }

    /**
     * 重置运行时前，把 [paths]（相对 rootfs）复制到保留区。
     * 删除 rootfs 不会带走它们，下次装好运行时再搬回去。
     */
    fun stash(paths: List<String>) = RuntimeStash.stash(rootfsDir, stashDir, paths)

    /** 装好运行时后把保留区搬回去。 */
    fun restoreStash() = RuntimeStash.restore(stashDir, rootfsDir)

    /** 保留区里还有东西（重置后还没重新装运行时）。 */
    fun hasStash(): Boolean = RuntimeStash.hasStash(stashDir)

    private fun findRootfsEntry(zip: ZipFile, asset: String) =
        listOf(asset, "assets/runtime/$asset")
            .firstNotNullOfOrNull { zip.getEntry(it) }
            ?: error("运行时包缺少 rootfs：$asset")

    private fun extractTarXz(
        tarFile: File,
        dest: File,
        totalEntries: Int,
        uncompressedBytes: Long,
        onProgress: (Int, String) -> Unit,
    ) {
        // zip bomb 兜底：manifest 记录了本次构建解压后的文件总字节数（rtbuild 精确统计），
        // 超过它 + 容差就中止 —— 哈希已经挡住了大多数伪造包，这里是最后一条防线。
        val cap = if (uncompressedBytes > 0) {
            uncompressedBytes + uncompressedBytes / 8 + SIZE_TOLERANCE_BYTES
        } else {
            0L // 清单没给总量（旧格式）时不设限，由哈希校验把关。
        }
        var written = 0L
        BufferedInputStream(tarFile.inputStream()).use { buffered ->
            XZInputStream(buffered).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    var entry = tar.nextEntry
                    var count = 0
                    while (entry != null) {
                        val target = resolveSafely(dest, entry.name)
                        when {
                            entry.isDirectory -> target.mkdirs()

                            entry.isSymbolicLink -> {
                                target.parentFile?.mkdirs()
                                val resolved = resolveLinkTarget(dest, entry.name, entry.linkName)
                                require(resolved.startsWith(dest.canonicalPath)) { "符号链接越界：${entry.name} -> ${entry.linkName}" }
                                Files.deleteIfExists(target.toPath())
                                runCatching { Files.createSymbolicLink(target.toPath(), Paths.get(entry.linkName)) }
                            }

                            entry.isLink -> {
                                if (cap > 0 && written + entry.size > cap) failOverCap(written, cap, entry.name)
                                val source = resolveSafely(dest, entry.linkName)
                                if (source.isFile) {
                                    target.parentFile?.mkdirs()
                                    source.copyTo(target, overwrite = true)
                                    written += source.length()
                                }
                            }

                            else -> {
                                // tar 的 entry.size 就是该成员在流里的真实字节数，写前先卡总量。
                                if (cap > 0 && written + entry.size > cap) failOverCap(written, cap, entry.name)
                                target.parentFile?.mkdirs()
                                target.outputStream().use { out -> written += tar.copyTo(out) }
                                if (cap > 0 && written > cap) failOverCap(written, cap, entry.name)
                                if (entry.mode and EXECUTABLE_MASK != 0) target.setExecutable(true, true)
                            }
                        }
                        count++
                        if (totalEntries > 0 && count % 200 == 0) {
                            onProgress((count * 100 / totalEntries).coerceIn(0, 99), "解压中")
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }

    private fun failOverCap(written: Long, cap: Long, name: String): Nothing =
        error("解压超出限额：累计 $written 字节 / 上限 $cap（条目 $name）")

    private fun resolveSafely(root: File, name: String): File {
        val relative = name.replace('\\', '/').trimStart('/')
        require(relative.split('/').none { it == ".." }) { "非法的 tar 路径：$name" }
        val file = File(root, relative)
        val rootPath = root.canonicalPath + File.separator
        require(file.canonicalPath.startsWith(rootPath) || file.canonicalPath == root.canonicalPath) {
            "tar 路径越界：$name"
        }
        return file
    }

    private fun resolveLinkTarget(root: File, entryName: String, linkName: String): String {
        val parent = File(root, entryName).parentFile ?: root
        return File(parent, linkName).canonicalPath
    }

    companion object {
        private const val DEFAULT_BUFFER = 1 shl 20
        private const val EXECUTABLE_MASK = 0b001_001_001

        /** 解压总量容差：哈希钉死的包与清单数字一致，容差只吸收统计口径上的零头。 */
        private const val SIZE_TOLERANCE_BYTES = 1L shl 20

        /**
         * 重置运行时时「保留」的东西（相对 rootfs）：
         * 对话、工作区、Bot 记忆与个性化配置 —— 用户写下的东西不该被一次重置抹掉。
         */
        val KEEP_PATHS = listOf(
            "root/.dsh/bot",
            "root/.dsh/sessions",
            "root/.dsh/AGENTS.md",
        )
    }
}



