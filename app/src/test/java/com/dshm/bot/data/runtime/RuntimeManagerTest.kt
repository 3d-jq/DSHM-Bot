package com.dshm.bot.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** 本地 runtime.zip 开发旁路的门禁：只在 debug 构建生效，release 一律走下载 + 校验。 */
class RuntimeManagerTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "dshm-test-$name-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }

    @Test
    fun debugBuildPicksUpLocalZip() {
        val dir = tempDir("devzip-debug")
        val zip = File(dir, "runtime.zip").apply { writeText("zip") }

        assertEquals(zip, RuntimeManager.devZipOrNull(isDebugBuild = true, externalFilesDir = dir))
    }

    @Test
    fun releaseBuildNeverPicksUpLocalZip() {
        val dir = tempDir("devzip-release")
        // 外部目录里的 runtime.zip 在 release 构建里必须被无视 —— 它可能被其它应用写入。
        File(dir, "runtime.zip").apply { writeText("planted") }

        assertNull(RuntimeManager.devZipOrNull(isDebugBuild = false, externalFilesDir = dir))
    }

    @Test
    fun debugBuildWithNoLocalZipFallsBackToDownload() {
        assertNull(RuntimeManager.devZipOrNull(isDebugBuild = true, externalFilesDir = tempDir("devzip-empty")))
        assertNull(RuntimeManager.devZipOrNull(isDebugBuild = true, externalFilesDir = null))
    }
}
