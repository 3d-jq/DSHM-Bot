package com.dshm.bot.data.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 解包安装的安全防线（此前无测试覆盖）：
 * - 哈希以 APK 内置清单为准，zip 内嵌 manifest 不得自证（缺哈希 / 改哈希 / 改版本都拒绝）；
 * - tar 解包拒绝路径穿越、符号链接 / 硬链接越界；
 * - 解压总量超过清单记录即中止（zip bomb 兜底）。
 *
 * 纯 JVM：tar/zip 用 commons-compress 现场构造，installer 走 baseDir 注入构造。
 */
class RuntimeInstallerTest {

    // ------------------------------------------------------------------ 工具

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "dshm-test-$name-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }

    private class TarDirSpec(val name: String)

    private class TarFileSpec(val name: String, val bytes: ByteArray, val mode: Int = 0b110100100)

    private class TarSymlinkSpec(val name: String, val target: String)

    private class TarHardlinkSpec(val name: String, val target: String)

    private fun buildTarXz(entries: List<Any>): ByteArray {
        val raw = ByteArrayOutputStream()
        XZOutputStream(raw, LZMA2Options()).use { xz ->
            TarArchiveOutputStream(xz).use { tar ->
                for (entry in entries) {
                    when (entry) {
                        is TarDirSpec -> tar.putArchiveEntry(TarArchiveEntry(entry.name))
                        is TarFileSpec -> {
                            val archived = TarArchiveEntry(entry.name)
                            archived.size = entry.bytes.size.toLong()
                            archived.setMode(entry.mode)
                            tar.putArchiveEntry(archived)
                            tar.write(entry.bytes)
                        }
                        is TarSymlinkSpec -> {
                            val archived = TarArchiveEntry(entry.name, TarConstants.LF_SYMLINK)
                            archived.setLinkName(entry.target)
                            tar.putArchiveEntry(archived)
                        }
                        is TarHardlinkSpec -> {
                            val archived = TarArchiveEntry(entry.name, TarConstants.LF_LINK)
                            archived.setLinkName(entry.target)
                            tar.putArchiveEntry(archived)
                        }
                    }
                    tar.closeArchiveEntry()
                }
            }
        }
        return raw.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun zipOf(dir: File, entries: List<Pair<String, ByteArray>>): File {
        val zip = File(dir, "runtime.zip")
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return zip
    }

    /** APK 内置清单（校验基准）。 */
    private fun bundledManifest(
        sha: String? = null,
        version: String = "r1",
        uncompressed: Long = -1L,
    ): RuntimeManifest = RuntimeManifest(
        runtimeVersion = version,
        abi = "arm64-v8a",
        rootfsAsset = "rootfs.tar.xz",
        rootfsBytes = -1,
        rootfsSha256 = sha,
        uncompressedBytes = uncompressed,
        tarBytes = -1,
        dshVersion = "",
        nodeVersion = "",
        launchArgv = emptyList(),
        launchEnv = emptyMap(),
        entriesTotal = 0,
    )

    /** zip 内嵌 manifest（随包而来，不再作为校验基准）。 */
    private fun embeddedManifestJson(sha: String?, version: String = "r1"): String = buildString {
        append('{')
        append("\"runtimeVersion\":\"$version\",")
        append("\"abi\":\"arm64-v8a\",")
        append("\"rootfs\":\"rootfs.tar.xz\",")
        if (sha != null) append("\"rootfsSha256\":\"$sha\",")
        append("\"entries\":{\"dir\":1,\"file\":2,\"symlink\":0}")
        append('}')
    }

    private inline fun assertThrowsContaining(fragment: String, block: () -> Unit) {
        val result = runCatching(block)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(
            "期望抛出包含「$fragment」的异常，实际：${result.exceptionOrNull()?.let { it.javaClass.simpleName + ": " + it.message } ?: "没有抛出"}",
            result.isFailure && message.contains(fragment),
        )
    }

    // ------------------------------------------------------------ 哈希权威（P1）

    @Test
    fun installsZipWithoutEmbeddedManifestAgainstBundledHash() {
        val base = tempDir("base")
        val cache = tempDir("cache")
        val tar = buildTarXz(
            listOf(
                TarDirSpec("bin/"),
                TarFileSpec("bin/dsh", "#!/bin/sh\necho dsh\n".toByteArray(), 0b111101101),
            ),
        )
        val zip = zipOf(base, listOf("rootfs.tar.xz" to tar))
        val installer = RuntimeInstaller(base)

        val version = installer.installFromZip(zip, bundledManifest(sha = sha256(tar)), cache)

        assertEquals("r1", version)
        assertEquals("r1", File(base, "rootfs.version").readText())
        assertEquals("#!/bin/sh\necho dsh\n", File(base, "rootfs/bin/dsh").readText())
        assertFalse(File(base, "staging").exists()) // staging 已整体换入 rootfs
    }

    @Test
    fun installsZipWithMatchingEmbeddedManifest() {
        val base = tempDir("base-match")
        val cache = tempDir("cache-match")
        val tar = buildTarXz(listOf(TarFileSpec("etc/version", "r1".toByteArray())))
        val zip = zipOf(
            base,
            listOf(
                "manifest.json" to embeddedManifestJson(sha = sha256(tar)).toByteArray(),
                "rootfs.tar.xz" to tar,
            ),
        )
        val installer = RuntimeInstaller(base)

        installer.installFromZip(zip, bundledManifest(sha = sha256(tar)), cache)

        assertEquals("r1", File(base, "rootfs/etc/version").readText())
    }

    @Test
    fun rejectsInstallWhenBundledManifestHasNoHash() {
        val base = tempDir("base-nosha")
        val cache = tempDir("cache-nosha")
        val tar = buildTarXz(listOf(TarFileSpec("bin/dsh", "x".toByteArray())))
        val zip = zipOf(base, listOf("rootfs.tar.xz" to tar))

        assertThrowsContaining("无法校验") {
            RuntimeInstaller(base).installFromZip(zip, bundledManifest(sha = null), cache)
        }
    }

    @Test
    fun rejectsEmbeddedManifestWithoutHashInsteadOfSkippingCheck() {
        val base = tempDir("base-embedded-nosha")
        val cache = tempDir("cache-embedded-nosha")
        val tar = buildTarXz(listOf(TarFileSpec("bin/dsh", "x".toByteArray())))
        val zip = zipOf(
            base,
            listOf(
                // 旧实现：内嵌 manifest 不带哈希 → 校验静默跳过 → 直接装。现在必须拒绝。
                "manifest.json" to embeddedManifestJson(sha = null).toByteArray(),
                "rootfs.tar.xz" to tar,
            ),
        )

        assertThrowsContaining("缺少 rootfsSha256") {
            RuntimeInstaller(base).installFromZip(zip, bundledManifest(sha = sha256(tar)), cache)
        }
    }

    @Test
    fun rejectsEmbeddedManifestWithForgedHash() {
        val base = tempDir("base-forge")
        val cache = tempDir("cache-forge")
        val tar = buildTarXz(listOf(TarFileSpec("bin/dsh", "malicious".toByteArray())))
        val forged = sha256(tar) // 攻击者连哈希一起改：与内置清单不一致
        val zip = zipOf(
            base,
            listOf(
                "manifest.json" to embeddedManifestJson(sha = "0".repeat(64)).toByteArray(),
                "rootfs.tar.xz" to tar,
            ),
        )

        assertThrowsContaining("哈希不一致") {
            RuntimeInstaller(base).installFromZip(zip, bundledManifest(sha = forged), cache)
        }
        assertFalse(File(base, "rootfs/bin/dsh").exists())
    }

    @Test
    fun rejectsEmbeddedManifestWithForeignVersion() {
        val base = tempDir("base-version")
        val cache = tempDir("cache-version")
        val tar = buildTarXz(listOf(TarFileSpec("bin/dsh", "x".toByteArray())))
        val zip = zipOf(
            base,
            listOf(
                "manifest.json" to embeddedManifestJson(sha = sha256(tar), version = "r99").toByteArray(),
                "rootfs.tar.xz" to tar,
            ),
        )

        assertThrowsContaining("版本") {
            RuntimeInstaller(base).installFromZip(zip, bundledManifest(sha = sha256(tar), version = "r1"), cache)
        }
    }

    @Test
    fun rejectsContentThatDoesNotMatchBundledHash() {
        val base = tempDir("base-content")
        val cache = tempDir("cache-content")
        val tar = buildTarXz(listOf(TarFileSpec("bin/dsh", "actual".toByteArray())))
        val zip = zipOf(base, listOf("rootfs.tar.xz" to tar))

        assertThrowsContaining("rootfs 校验失败") {
            RuntimeInstaller(base).installFromZip(zip, bundledManifest(sha = "ab".repeat(32)), cache)
        }
        assertFalse(File(base, "rootfs").exists())
    }

    // ---------------------------------------------------------- 解包越界（回归）

    @Test
    fun rejectsTarEntryWithPathTraversal() {
        val base = tempDir("base-slip")
        val cache = tempDir("cache-slip")
        val tar = buildTarXz(listOf(TarFileSpec("../evil.txt", "pwn".toByteArray())))
        val zip = zipOf(base, listOf("rootfs.tar.xz" to tar))

        assertThrowsContaining("tar 路径") {
            RuntimeInstaller(base).installFromZip(zip, bundledManifest(sha = sha256(tar)), cache)
        }
        // 穿越 staging 的「..」本应落在 base 下；被拒后什么都不该写出来。
        assertFalse(File(base, "evil.txt").exists())
        assertFalse(File(base, "staging/evil.txt").exists())
    }

    @Test
    fun rejectsSymlinkPointingOutsideRootfs() {
        val base = tempDir("base-symlink")
        val cache = tempDir("cache-symlink")
        val tar = buildTarXz(
            listOf(TarSymlinkSpec("opt/evil", "../../../etc/passwd")),
        )
        val zip = zipOf(base, listOf("rootfs.tar.xz" to tar))

        assertThrowsContaining("符号链接越界") {
            RuntimeInstaller(base).installFromZip(zip, bundledManifest(sha = sha256(tar)), cache)
        }
    }

    @Test
    fun rejectsHardlinkPointingOutsideRootfs() {
        val base = tempDir("base-hardlink")
        val cache = tempDir("cache-hardlink")
        val tar = buildTarXz(
            listOf(TarHardlinkSpec("root/evil", "../outside.txt")),
        )
        val zip = zipOf(base, listOf("rootfs.tar.xz" to tar))

        assertThrowsContaining("tar 路径") {
            RuntimeInstaller(base).installFromZip(zip, bundledManifest(sha = sha256(tar)), cache)
        }
    }

    // ---------------------------------------------------------- 解压限额（P2）

    @Test
    fun abortsExtractionOverUncompressedBudget() {
        val base = tempDir("base-bomb")
        val cache = tempDir("cache-bomb")
        val bomb = buildTarXz(listOf(TarFileSpec("root/big.bin", ByteArray(2 shl 20))))
        val zip = zipOf(base, listOf("rootfs.tar.xz" to bomb))

        assertThrowsContaining("超出限额") {
            RuntimeInstaller(base).installFromZip(
                zip,
                bundledManifest(sha = sha256(bomb), uncompressed = 100),
                cache,
            )
        }
        // 超限即中止：不留下解了一半的 rootfs。
        assertFalse(File(base, "rootfs/root/big.bin").exists())
    }

    @Test
    fun keepsExtractingWithinUncompressedBudget() {
        val base = tempDir("base-budget-ok")
        val cache = tempDir("cache-budget-ok")
        val payload = "payload".toByteArray()
        val tar = buildTarXz(listOf(TarFileSpec("root/data.bin", payload)))
        val zip = zipOf(base, listOf("rootfs.tar.xz" to tar))
        // 清单总量给得宽松（真实 rootfs 场景），正常解包不受影响。
        val uncompressed = (payload.size * 8).toLong()

        RuntimeInstaller(base).installFromZip(
            zip,
            bundledManifest(sha = sha256(tar), uncompressed = uncompressed),
            cache,
        )

        assertEquals("payload", File(base, "rootfs/root/data.bin").readText())
    }
}
