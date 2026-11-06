package com.dshm.bot.data.runtime

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎运行时相关的纯逻辑：状态名、诊断报告、重置保留区。
 * 都不依赖 Android，可以直接跑 JVM 单测。
 */
class EngineRuntimeLogicTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "dshm-test-$name-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }

    @Test
    fun shortRuntimeVersionKeepsOnlyTheMeaningfulParts() {
        // dsh-Go 的规则：rN-dsh…-HASH → rN · HASH8。
        assertEquals(
            "r14 · a1b2c3d4",
            shortRuntimeVersion("r14-dsh0.2.1-rc.4-node24.22.1-ubuntu24.04-a1b2c3d4e5f6"),
        )
        // 不认得的格式原样返回。
        assertEquals("0.2.27.0", shortRuntimeVersion("0.2.27.0"))
        assertEquals("—", shortRuntimeVersion(null))
        assertEquals("—", shortRuntimeVersion(""))
    }

    @Test
    fun phaseNamesAreStable() {        assertEquals("notInstalled", RuntimeState.NotInstalled.phaseName())
        assertEquals("installing", RuntimeState.Installing(10, "下载中").phaseName())
        assertEquals("installed", RuntimeState.Installed("r1").phaseName())
        assertEquals("starting", RuntimeState.Starting.phaseName())
        assertEquals("running", RuntimeState.Running(RuntimeEndpoint("http://127.0.0.1:1/", "t")).phaseName())
        assertEquals("stopped", RuntimeState.Stopped.phaseName())
        assertEquals("error", RuntimeState.Failed("boom").phaseName())
    }

    @Test
    fun diagnosticsReportCarriesRuntimeState() {
        val json = JSONObject(
            buildDiagnosticsReport(
                phase = "running",
                abi = "arm64-v8a",
                bundledVersion = "r1",
                installedVersion = "r2",
                dshVersion = "0.2.27.0",
                nodeVersion = "22.14.0",
                baseUrl = "http://127.0.0.1:3083/",
                error = null,
                logLines = 42,
                hasApiKey = true,
                connection = "已连接",
            ),
        )
        val runtime = json.getJSONObject("runtime")
        assertEquals("running", runtime.getString("phase"))
        assertEquals("arm64-v8a", runtime.getString("abi"))
        assertEquals("r2", runtime.getString("installedVersion"))
        assertEquals("0.2.27.0", runtime.getString("dshVersion"))
        assertEquals("http://127.0.0.1:3083/", runtime.getString("baseUrl"))
        assertEquals(42, runtime.getInt("logLines"))
        assertEquals("已连接", json.getString("connection"))
        assertTrue(json.getJSONObject("settings").getBoolean("hasApiKey"))
    }

    @Test
    fun diagnosticsReportNeverLeaksSecrets() {
        val json = JSONObject(
            buildDiagnosticsReport(
                phase = "error",
                abi = "arm64-v8a",
                bundledVersion = "r1",
                installedVersion = null,
                dshVersion = "0.2.27.0",
                nodeVersion = "22.14.0",
                baseUrl = null,
                error = "启动失败",
                logLines = 3,
                hasApiKey = false,
                connection = "未连接",
            ),
        )
        val runtime = json.getJSONObject("runtime")
        // token 永远是 null，不可能有明文。
        assertTrue(runtime.isNull("token"))
        assertTrue(runtime.isNull("baseUrl"))
        assertTrue(runtime.isNull("installedVersion"))
        assertEquals("启动失败", runtime.getString("error"))
        assertFalse(json.getJSONObject("settings").getBoolean("hasApiKey"))
    }

    @Test
    fun stashKeepsSelectedPathsAndRestoresThem() {
        val root = tempDir("rootfs")
        val stash = tempDir("stash")
        try {
            File(root, "root/.dsh/bot").mkdirs()
            File(root, "root/.dsh/bot/state.json").writeText("{\"v\":1}")
            File(root, "root/.dsh/sessions").mkdirs()
            File(root, "root/.dsh/sessions/a.json").writeText("session")
            File(root, "root/.dsh/AGENTS.md").writeText("# agents")
            File(root, "root/.dsh/logs").mkdirs()
            File(root, "root/.dsh/logs/a.log").writeText("noise")

            RuntimeStash.stash(root, stash, RuntimeInstaller.KEEP_PATHS)

            // 保留区里有对话、工作区、个性化；日志不在保留名单里。
            assertTrue(File(stash, "root/.dsh/bot/state.json").isFile)
            assertTrue(File(stash, "root/.dsh/sessions/a.json").isFile)
            assertTrue(File(stash, "root/.dsh/AGENTS.md").isFile)
            assertFalse(File(stash, "root/.dsh/logs").exists())
            assertTrue(RuntimeStash.hasStash(stash))

            // 删掉运行时，再装回来。
            root.deleteRecursively()
            root.mkdirs()
            assertFalse(File(root, "root/.dsh/bot/state.json").exists())

            RuntimeStash.restore(stash, root)

            assertEquals("{\"v\":1}", File(root, "root/.dsh/bot/state.json").readText())
            assertEquals("session", File(root, "root/.dsh/sessions/a.json").readText())
            assertEquals("# agents", File(root, "root/.dsh/AGENTS.md").readText())
            assertFalse(RuntimeStash.hasStash(stash))
        } finally {
            root.deleteRecursively()
            stash.deleteRecursively()
        }
    }

    @Test
    fun stashIgnoresMissingPaths() {
        val root = tempDir("rootfs-empty")
        val stash = tempDir("stash-empty")
        try {
            RuntimeStash.stash(root, stash, listOf("root/.dsh/nope"))
            assertFalse(RuntimeStash.hasStash(stash))
            // 空的保留区恢复时什么也不做。
            RuntimeStash.restore(stash, root)
            assertTrue(root.isDirectory)
        } finally {
            root.deleteRecursively()
            stash.deleteRecursively()
        }
    }

    @Test
    fun stashReplacesPreviousContent() {
        val root = tempDir("rootfs-replace")
        val stash = tempDir("stash-replace")
        try {
            File(root, "root/.dsh").mkdirs()
            File(root, "root/.dsh/AGENTS.md").writeText("new")
            RuntimeStash.stash(root, stash, RuntimeInstaller.KEEP_PATHS)
            File(root, "root/.dsh/AGENTS.md").writeText("newer")
            RuntimeStash.stash(root, stash, RuntimeInstaller.KEEP_PATHS)
            assertEquals("newer", File(stash, "root/.dsh/AGENTS.md").readText())
        } finally {
            root.deleteRecursively()
            stash.deleteRecursively()
        }
    }
}
