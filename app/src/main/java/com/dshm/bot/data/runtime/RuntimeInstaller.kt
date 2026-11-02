package com.dshm.bot.data.runtime

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 解包 rootfs：从运行时 zip 里取出 `manifest.rootfsAsset`（tar.xz），
 * 校验 SHA-256 后解到 `filesDir/runtime/rootfs`，并写版本文件。
 *
 * 目录布局：
 *   runtime/rootfs/          解压后的 Linux 根
 *   runtime/rootfs.version   当前版本
 *   runtime/staging/         解压中转，完成后整体换入
 */
class RuntimeInstaller(context: Context) {

    private val appContext = context.applicationContext
    val baseDir = File(appContext.filesDir, "runtime")
    val rootfsDir = File(baseDir, "rootfs")
    private val versionFile = File(baseDir, "rootfs.version")
    private val stagingDir = File(baseDir, "staging")

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
    ) {
        baseDir.mkdirs()
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        val tarFile = File(appContext.cacheDir, "rootfs.tar.xz")
        try {
            ZipFile(zipFile).use { zip ->
                val entry = findRootfsEntry(zip, manifest.rootfsAsset)
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
                val expected = manifest.rootfsSha256
                require(expected == null || actual.equals(expected, ignoreCase = true)) {
                    "rootfs 校验失败：期望 $expected，实际 $actual"
                }
            }

            onProgress(0, "解压中")
            extractTarXz(tarFile, stagingDir, onProgress)
        } finally {
            tarFile.delete()
        }

        rootfsDir.deleteRecursively()
        if (!stagingDir.renameTo(rootfsDir)) {
            stagingDir.copyRecursively(rootfsDir, overwrite = true)
            stagingDir.deleteRecursively()
        }
        versionFile.writeText(manifest.runtimeVersion)
        onProgress(100, "已安装")
    }

    fun delete() {
        rootfsDir.deleteRecursively()
        stagingDir.deleteRecursively()
        versionFile.delete()
    }

    private fun findRootfsEntry(zip: ZipFile, asset: String) =
        listOf(asset, "assets/runtime/$asset")
            .firstNotNullOfOrNull { zip.getEntry(it) }
            ?: error("运行时包缺少 rootfs：$asset")

    private fun extractTarXz(tarFile: File, dest: File, onProgress: (Int, String) -> Unit) {
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
                                val linkTarget = resolveLinkTarget(dest, entry.name, entry.linkName)
                                Files.deleteIfExists(target.toPath())
                                runCatching { Files.createSymbolicLink(target.toPath(), Paths.get(linkTarget)) }
                            }

                            entry.isLink -> {
                                val source = resolveSafely(dest, entry.linkName)
                                if (source.isFile) {
                                    target.parentFile?.mkdirs()
                                    source.copyTo(target, overwrite = true)
                                }
                            }

                            else -> {
                                target.parentFile?.mkdirs()
                                target.outputStream().use { out -> tar.copyTo(out) }
                                if (entry.mode and EXECUTABLE_MASK != 0) target.setExecutable(true, true)
                            }
                        }
                        if (++count % 500 == 0) onProgress(0, "解压中（$count 项）")
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }

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

    private companion object {
        const val DEFAULT_BUFFER = 1 shl 20
        const val EXECUTABLE_MASK = 0b001_001_001
    }
}
