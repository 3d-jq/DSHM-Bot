package com.dshm.bot.data.runtime

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 用 proot 启动 rootfs 里的 `dsh web`，从 stdout 解析 token 得到连接信息。
 * 目前用固定端口 3080；后续可改为探测空闲端口。
 */
class RuntimeLauncher(
    context: Context,
    private val installer: RuntimeInstaller = RuntimeInstaller(context),
    private val port: Int = 3080,
) {
    private val appContext = context.applicationContext
    @Volatile
    private var process: Process? = null

    /** 启动并等待 token；[guestEnv] 是可选的 KEY=VALUE（如 TZ、API Key）。 */
    fun start(manifest: RuntimeManifest, guestEnv: List<String> = emptyList()): RuntimeEndpoint {
        val rootfs = installer.rootfsDir
        require(rootfs.isDirectory) { "运行时尚未安装" }

        val tmp = File(appContext.cacheDir, "proot-tmp").apply { mkdirs() }
        val placeholders = mapOf(
            "{NATIVE_LIB_DIR}" to appContext.applicationInfo.nativeLibraryDir,
            "{ROOTFS}" to rootfs.absolutePath,
            "{TMP}" to tmp.absolutePath,
            "{PORT}" to port.toString(),
        )
        val argv = ProotCommand.buildArgv(manifest.launchArgv, placeholders, guestEnv)
        val env = ProotCommand.buildEnv(manifest.launchEnv, placeholders)

        val builder = ProcessBuilder(argv).redirectErrorStream(true)
        builder.environment().putAll(env)
        builder.environment()["PROOT_NO_SECCOMP"] = "1"
        val started = builder.start()
        process = started

        val logFile = File(appContext.filesDir, "engine.log")
        logFile.writeText("")
        val queue = java.util.concurrent.LinkedBlockingQueue<String>()
        val tail = ArrayDeque<String>()
        val reader = Thread {
            runCatching {
                started.inputStream.bufferedReader().forEachLine { line ->
                    logFile.appendText(line + "\n")
                    queue.put(line)
                }
            }
        }.apply { isDaemon = true; start() }

        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val line = queue.poll(500, TimeUnit.MILLISECONDS)
            if (line == null) {
                if (!started.isAlive) break
                continue
            }
            tail.addLast(line)
            if (tail.size > 30) tail.removeFirst()
            ProotCommand.parseToken(line)?.let { return it }
        }
        val exit = runCatching { started.exitValue() }.getOrNull()
        throw IllegalStateException(
            "引擎启动失败：未收到 token（exit=$exit）\n" + tail.joinToString("\n"),
        )
    }

    /** 停止引擎：先结束 dsh 进程，再强制结束 proot；同时清理引用。 */
    fun stop() {
        val running = process ?: return
        runCatching {
            running.destroy()
            if (!running.waitFor(5, TimeUnit.SECONDS)) running.destroyForcibly()
        }
        process = null
    }

    val isRunning: Boolean
        get() = process?.isAlive == true
}

